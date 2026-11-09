package com.codecore.ai.localai

import android.util.Log

/**
 * Native bridge interface to llama.cpp compiled via Android NDK/JNI.
 * Supports genuine GGUF architecture, CPU thread mapping, context windows, and real token streaming.
 */
object LlamaBridge {
    private const val TAG = "LlamaBridge"
    private var isNativeLibraryLoaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("llama_android")
            isNativeLibraryLoaded = true
            loadError = null
            Log.i(TAG, "llama_android native JNI library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library libllama_android.so", e)
            isNativeLibraryLoaded = false
            loadError = e
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error loading libllama_android.so", t)
            isNativeLibraryLoaded = false
            loadError = t
        }
    }

    fun isNativeReady(): Boolean = isNativeLibraryLoaded

    fun getLoadError(): Throwable? = loadError

    /**
     * Loads a GGUF model from device filesystem into allocated RAM.
     * Returns real pointer handle (> 0) or throws on failure.
     */
    fun loadModel(
        modelPath: String,
        contextSize: Int = 4096,
        threads: Int = 4
    ): Long {
        if (!isNativeLibraryLoaded) {
            throw IllegalStateException(
                "Native library llama_android is not loaded: ${loadError?.message ?: "UnsatisfiedLinkError"}"
            )
        }
        Log.d(TAG, "Calling nativeLoadModel: path=$modelPath, context=$contextSize, threads=$threads")
        val handle = nativeLoadModel(modelPath, contextSize, threads)
        if (handle <= 0L) {
            throw IllegalStateException("Failed to load GGUF model from path: $modelPath (native handle is 0)")
        }
        return handle
    }

    /**
     * Releases model RAM allocations using real native handle.
     */
    fun freeModel(handle: Long) {
        Log.d(TAG, "Freeing native model handle: $handle")
        if (isNativeLibraryLoaded && handle > 0L) {
            nativeFreeModel(handle)
        }
    }

    /**
     * Executes real streaming inference with token callback.
     * @param onToken callback receiving each generated string piece. Return false to abort generation.
     */
    fun generate(
        handle: Long,
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        maxTokens: Int = 2048,
        onToken: (String) -> Boolean
    ): String {
        if (!isNativeLibraryLoaded) {
            throw IllegalStateException("Native library llama_android is not loaded")
        }
        if (handle <= 0L) {
            throw IllegalArgumentException("Invalid native model handle: $handle")
        }

        Log.d(TAG, "Generating: handle=$handle, temp=$temperature, topP=$topP, maxTokens=$maxTokens")
        return nativeGenerate(
            handle = handle,
            prompt = prompt,
            temp = temperature,
            topP = topP,
            topK = topK,
            repeatPenalty = repeatPenalty,
            maxTokens = maxTokens,
            onToken = onToken
        )
    }

    fun getSystemInfo(): String {
        return if (isNativeLibraryLoaded) {
            try {
                nativeSystemInfo()
            } catch (e: Exception) {
                "Error retrieving native system info: ${e.message}"
            }
        } else {
            "Native library not loaded: ${loadError?.message ?: "N/A"}"
        }
    }

    // JNI External Declarations
    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        temp: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        maxTokens: Int,
        onToken: (String) -> Boolean
    ): String
    private external fun nativeSystemInfo(): String
    private external fun nativeIsReady(): Boolean
}
