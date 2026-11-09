#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>
#include <sstream>
#include <cmath>
#include <algorithm>
#include <unistd.h>

#include "llama.h"
#include "common.h"
#include "sampling.h"

#define TAG "llama_android"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

struct LlamaContextWrapper {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 4096;
    int n_threads = 4;
};

static void jni_log_callback(ggml_log_level level, const char * text, void * user_data) {
    (void) user_data;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_codecore_ai_localai_LlamaBridge_nativeLoadModel(
    JNIEnv * env,
    jobject /* this */,
    jstring jmodel_path,
    jint context_size,
    jint threads
) {
    llama_log_set(jni_log_callback, nullptr);
    llama_backend_init();

    const char * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (!model_path) {
        LOGE("Failed to get model path string UTF chars");
        return 0;
    }

    LOGI("Loading GGUF model: path=%s, context=%d, threads=%d", model_path, context_size, threads);

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;

    llama_model * model = llama_load_model_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGE("llama_load_model_from_file returned null for path");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_size > 0 ? context_size : 4096;
    ctx_params.n_threads = threads > 0 ? threads : 4;
    ctx_params.n_threads_batch = threads > 0 ? threads : 4;

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(model);
        return 0;
    }

    auto * wrapper = new LlamaContextWrapper();
    wrapper->model = model;
    wrapper->ctx = ctx;
    wrapper->n_ctx = ctx_params.n_ctx;
    wrapper->n_threads = ctx_params.n_threads;

    LOGI("Model loaded successfully into RAM, wrapper=%p", wrapper);
    return reinterpret_cast<jlong>(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_com_codecore_ai_localai_LlamaBridge_nativeFreeModel(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong handle
) {
    if (handle <= 0) return;
    auto * wrapper = reinterpret_cast<LlamaContextWrapper *>(handle);
    if (wrapper) {
        LOGI("Freeing native llama model wrapper=%p", wrapper);
        if (wrapper->ctx) {
            llama_free(wrapper->ctx);
            wrapper->ctx = nullptr;
        }
        if (wrapper->model) {
            llama_free_model(wrapper->model);
            wrapper->model = nullptr;
        }
        delete wrapper;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_codecore_ai_localai_LlamaBridge_nativeGenerate(
    JNIEnv * env,
    jobject /* this */,
    jlong handle,
    jstring jprompt,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jfloat repeat_penalty,
    jint max_tokens,
    jobject on_token_callback
) {
    if (handle <= 0) {
        LOGE("nativeGenerate: invalid handle");
        return env->NewStringUTF("");
    }
    auto * wrapper = reinterpret_cast<LlamaContextWrapper *>(handle);
    if (!wrapper || !wrapper->ctx || !wrapper->model) {
        LOGE("nativeGenerate: null wrapper, ctx or model");
        return env->NewStringUTF("");
    }

    const char * prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt_cstr) {
        return env->NewStringUTF("");
    }
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jprompt, prompt_cstr);

    jclass callback_class = nullptr;
    jmethodID invoke_method = nullptr;
    jclass boolean_class = nullptr;
    jmethodID boolean_value_method = nullptr;

    if (on_token_callback != nullptr) {
        callback_class = env->GetObjectClass(on_token_callback);
        invoke_method = env->GetMethodID(callback_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        boolean_class = env->FindClass("java/lang/Boolean");
        boolean_value_method = env->GetMethodID(boolean_class, "booleanValue", "()Z");
    }

    const std::vector<llama_token> prompt_tokens = common_tokenize(wrapper->ctx, prompt, true, true);
    if (prompt_tokens.empty()) {
        LOGE("Tokenization returned 0 tokens for prompt");
        return env->NewStringUTF("");
    }

    const int n_ctx = llama_n_ctx(wrapper->ctx);
    if ((int)prompt_tokens.size() >= n_ctx - 4) {
        LOGE("Prompt length (%zu) exceeds context window (%d)", prompt_tokens.size(), n_ctx);
        return env->NewStringUTF("[Error: Prompt exceeds context window]");
    }

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    if (repeat_penalty > 1.0f) {
        const int n_vocab = llama_n_vocab(wrapper->model);
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            n_vocab,
            llama_token_eos(wrapper->model),
            llama_token_nl(wrapper->model),
            64,
            repeat_penalty,
            0.0f,
            0.0f,
            false,
            false
        ));
    }
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        }
        if (top_p < 1.0f && top_p > 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_kv_cache_clear(wrapper->ctx);
    llama_batch batch = llama_batch_init(n_ctx, 0, 1);

    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        common_batch_add(batch, prompt_tokens[i], (llama_pos)i, { 0 }, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(wrapper->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return env->NewStringUTF("[Error: llama_decode failed on prompt]");
    }

    std::string full_response;
    int n_cur = (int)prompt_tokens.size();
    const int max_target = max_tokens > 0 ? (n_cur + max_tokens) : (n_ctx - 1);
    const int limit_tokens = std::min(max_target, n_ctx - 1);

    while (n_cur < limit_tokens) {
        const llama_token new_token_id = llama_sampler_sample(smpl, wrapper->ctx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_token_is_eog(wrapper->model, new_token_id)) {
            break;
        }

        std::string piece = common_token_to_piece(wrapper->ctx, new_token_id);
        full_response += piece;

        if (on_token_callback != nullptr && invoke_method != nullptr) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            jobject jres = env->CallObjectMethod(on_token_callback, invoke_method, jpiece);
            env->DeleteLocalRef(jpiece);

            if (jres != nullptr && boolean_value_method != nullptr) {
                jboolean should_continue = env->CallBooleanMethod(jres, boolean_value_method);
                env->DeleteLocalRef(jres);
                if (!should_continue) {
                    LOGI("Generation cancelled by caller callback");
                    break;
                }
            }
        }

        common_batch_clear(batch);
        common_batch_add(batch, new_token_id, (llama_pos)n_cur, { 0 }, true);
        n_cur++;

        if (llama_decode(wrapper->ctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    return env->NewStringUTF(full_response.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_codecore_ai_localai_LlamaBridge_nativeSystemInfo(
    JNIEnv * env,
    jobject /* this */
) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_codecore_ai_localai_LlamaBridge_nativeIsReady(
    JNIEnv * /* env */,
    jobject /* this */
) {
    return JNI_TRUE;
}
