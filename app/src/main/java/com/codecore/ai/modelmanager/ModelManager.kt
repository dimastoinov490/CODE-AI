package com.codecore.ai.modelmanager

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.codecore.ai.localai.LlamaBridge
import com.codecore.ai.model.CatalogModel
import com.codecore.ai.model.DownloadProgress
import com.codecore.ai.model.EngineStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ModelManager(private val context: Context) {
    private val TAG = "ModelManager"

    val modelsDir: File = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Real curated catalog of coding & chat models from official HuggingFace repositories
    val catalog: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen25_coder_15b",
            name = "Qwen2.5-Coder-1.5B-Instruct",
            developer = "Qwen Team (Alibaba Cloud)",
            purpose = "Быстрая кодогенерация, архитектура функций Kotlin/Compose. Минимальный расход батареи.",
            supportedLanguages = "Kotlin, Java, XML, Gradle, Python, C++",
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 986_000_000L,
            formattedSize = "986 МБ",
            requiredRamGb = 1.8f,
            quantization = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            isRecommended = false,
            isCustom = false
        ),
        CatalogModel(
            id = "qwen25_coder_3b",
            name = "Qwen2.5-Coder-3B-Instruct",
            developer = "Qwen Team (Alibaba Cloud)",
            purpose = "Основная рекомендованная модель: глубокая архитектура Android-приложений, Jetpack Compose и исправление ошибок Gradle.",
            supportedLanguages = "Kotlin, Java, XML, Gradle, C/C++, Python",
            fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            sizeBytes = 1_980_000_000L,
            formattedSize = "1.98 ГБ",
            requiredRamGb = 3.4f,
            quantization = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            isRecommended = true,
            isCustom = false
        ),
        CatalogModel(
            id = "deepseek_coder_13b",
            name = "DeepSeek-Coder-1.3B-Instruct",
            developer = "DeepSeek AI",
            purpose = "Специализированная модель для чистой программной логики и исправления синтаксических ошибок.",
            supportedLanguages = "Kotlin, Java, Python, Gradle, C++",
            fileName = "deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
            sizeBytes = 874_000_000L,
            formattedSize = "874 МБ",
            requiredRamGb = 1.6f,
            quantization = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
            isRecommended = false,
            isCustom = false
        ),
        CatalogModel(
            id = "tinyllama_11b_chat",
            name = "TinyLlama-1.1B-Chat-v1.0",
            developer = "TinyLlama Team",
            purpose = "Сверхкомпактная базовая чат-модель для тестирования и устройств с малым объёмом RAM.",
            supportedLanguages = "Текст, базовый код",
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            sizeBytes = 638_000_000L,
            formattedSize = "638 МБ",
            requiredRamGb = 1.2f,
            quantization = "GGUF Q4_K_M",
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            isRecommended = false,
            isCustom = false
        )
    )

    // Dynamic list including custom imported models
    private val _customModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    val customModels: StateFlow<List<CatalogModel>> = _customModels.asStateFlow()

    // Real state of installed files on disk (map of modelId -> File)
    private val _installedFiles = MutableStateFlow<Map<String, File>>(emptyMap())
    val installedFiles: StateFlow<Map<String, File>> = _installedFiles.asStateFlow()

    // Real engine status
    private val _engineStatus = MutableStateFlow(EngineStatus.NOT_INSTALLED)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    // Real active loaded model
    private val _activeModel = MutableStateFlow<CatalogModel?>(null)
    val activeModel: StateFlow<CatalogModel?> = _activeModel.asStateFlow()

    // Real native pointer handle
    private var activeModelHandle: Long = 0L

    // Last engine error message
    private val _lastEngineError = MutableStateFlow<String?>(null)
    val lastEngineError: StateFlow<String?> = _lastEngineError.asStateFlow()

    // Real download progress map (modelId -> DownloadProgress)
    private val _downloadProgressMap = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, DownloadProgress>> = _downloadProgressMap.asStateFlow()

    // Active download jobs
    private val downloadJobs = mutableMapOf<String, Job>()

    // Inference parameters (defaults suited for ~6GB RAM device)
    private val _contextWindow = MutableStateFlow(4096)
    val contextWindow: StateFlow<Int> = _contextWindow.asStateFlow()

    private val _cpuThreads = MutableStateFlow(4)
    val cpuThreads: StateFlow<Int> = _cpuThreads.asStateFlow()

    private val _temperature = MutableStateFlow(0.7f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _topP = MutableStateFlow(0.9f)
    val topP: StateFlow<Float> = _topP.asStateFlow()

    private val _topK = MutableStateFlow(40)
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _repeatPenalty = MutableStateFlow(1.1f)
    val repeatPenalty: StateFlow<Float> = _repeatPenalty.asStateFlow()

    private val _maxTokens = MutableStateFlow(2048)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _needsReload = MutableStateFlow(false)
    val needsReload: StateFlow<Boolean> = _needsReload.asStateFlow()

    private val _ramWarning = MutableStateFlow<String?>(null)
    val ramWarning: StateFlow<String?> = _ramWarning.asStateFlow()

    init {
        rescanInstalledModels()
    }

    fun getActiveModelHandle(): Long = activeModelHandle

    fun getAllCatalogModels(): List<CatalogModel> {
        return catalog + _customModels.value
    }

    /**
     * Rescans device storage for real valid GGUF files.
     */
    fun rescanInstalledModels() {
        val found = mutableMapOf<String, File>()
        val custom = mutableListOf<CatalogModel>()

        val filesOnDisk = modelsDir.listFiles { file ->
            file.isFile && (file.extension.equals("gguf", ignoreCase = true) || file.name.endsWith(".gguf"))
        } ?: emptyArray()

        for (file in filesOnDisk) {
            val valid = validateGgufHeader(file)
            if (valid) {
                val matched = catalog.find { it.fileName.equals(file.name, ignoreCase = true) }
                if (matched != null) {
                    found[matched.id] = file
                } else {
                    val customId = "custom_${file.name.replace(".", "_")}"
                    found[customId] = file
                    val sizeBytes = file.length()
                    val ramRequired = (sizeBytes / (1024f * 1024f * 1024f)) * 1.25f
                    custom.add(
                        CatalogModel(
                            id = customId,
                            name = file.nameWithoutExtension,
                            developer = "Пользовательский импорт",
                            purpose = "Локальный GGUF файл из хранилища устройства",
                            supportedLanguages = "Код / Текст",
                            fileName = file.name,
                            sizeBytes = sizeBytes,
                            formattedSize = formatBytes(sizeBytes),
                            requiredRamGb = ramRequired,
                            quantization = "GGUF",
                            downloadUrl = "",
                            isRecommended = false,
                            isCustom = true
                        )
                    )
                }
            } else {
                Log.w(TAG, "File ${file.name} is not a valid GGUF binary, ignoring.")
            }
        }

        _customModels.value = custom
        _installedFiles.value = found

        if (_activeModel.value != null) {
            val activeId = _activeModel.value?.id
            if (activeId == null || !found.containsKey(activeId)) {
                unloadActiveModel()
            }
        } else {
            if (_engineStatus.value != EngineStatus.LOADING_TO_RAM && _engineStatus.value != EngineStatus.DOWNLOADING) {
                _engineStatus.value = if (found.isEmpty()) EngineStatus.NOT_INSTALLED else EngineStatus.INSTALLED
            }
        }
    }

    /**
     * Validates that file starts with GGUF magic bytes (0x47, 0x47, 0x55, 0x46)
     */
    fun validateGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                read == 4 &&
                    magic[0] == 0x47.toByte() && // G
                    magic[1] == 0x47.toByte() && // G
                    magic[2] == 0x55.toByte() && // U
                    magic[3] == 0x46.toByte()    // F
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GGUF header for ${file.name}", e)
            false
        }
    }

    /**
     * Imports a user-selected GGUF file via Storage Access Framework (SAF)
     */
    suspend fun importCustomGguf(uri: Uri): Result<CatalogModel> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "imported_model_${System.currentTimeMillis()}.gguf"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        fileName = if (displayName.endsWith(".gguf", ignoreCase = true)) displayName else "$displayName.gguf"
                    }
                }
            }

            val targetFile = File(modelsDir, fileName)
            val tempFile = File(modelsDir, "$fileName.tmp")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось открыть поток чтения для URI")

            if (!validateGgufHeader(tempFile)) {
                tempFile.delete()
                throw IllegalArgumentException("Выбранный файл не является валидным бинарником GGUF (неверная сигнатура заголовка)")
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            rescanInstalledModels()
            val custom = _customModels.value.find { it.fileName == targetFile.name }
                ?: throw IllegalStateException("Модель импортирована, но не обнаружена при сканировании")

            Result.success(custom)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom GGUF", e)
            Result.failure(e)
        }
    }

    /**
     * Real download of GGUF file with HTTP Range support for resume and streaming to disk.
     */
    fun startRealDownload(model: CatalogModel, scope: CoroutineScope) {
        val targetFile = File(modelsDir, model.fileName)
        val tempFile = File(modelsDir, "${model.fileName}.download")

        val freeSpaceBytes = getAvailableStorageBytes()
        if (freeSpaceBytes < model.sizeBytes + 200_000_000L) {
            _downloadProgressMap.value = _downloadProgressMap.value + (model.id to DownloadProgress(
                isFailed = true,
                errorMessage = "Недостаточно свободного места. Требуется: ${model.formattedSize}"
            ))
            return
        }

        val existingDownloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        val job = scope.launch(Dispatchers.IO) {
            _engineStatus.value = EngineStatus.DOWNLOADING
            _downloadProgressMap.value = _downloadProgressMap.value + (model.id to DownloadProgress(
                downloadedBytes = existingDownloadedBytes,
                totalBytes = model.sizeBytes,
                progressPercent = (existingDownloadedBytes.toFloat() / model.sizeBytes).coerceIn(0f, 1f)
            ))

            try {
                val requestBuilder = Request.Builder()
                    .url(model.downloadUrl)
                    .header("User-Agent", "CodeCore-AI-Android/1.0")

                if (existingDownloadedBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingDownloadedBytes-")
                }

                val request = requestBuilder.build()

                httpClient.newCall(request).execute().use { response ->
                    val isPartial = response.code == 206
                    val isOk = response.code == 200

                    if (!isPartial && !isOk) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Пустой ответ от сервера")
                    val contentLength = body.contentLength()
                    val totalExpected = if (isPartial) existingDownloadedBytes + contentLength else (if (contentLength > 0) contentLength else model.sizeBytes)

                    var downloaded = if (isPartial) existingDownloadedBytes else 0L
                    var lastTime = System.currentTimeMillis()
                    var lastDownloaded = downloaded

                    val outputMode = if (isPartial) "rw" else "rws"
                    RandomAccessFile(tempFile, outputMode).use { raf ->
                        if (isPartial) {
                            raf.seek(existingDownloadedBytes)
                        } else {
                            raf.setLength(0)
                        }

                        body.byteStream().use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (!isActive) {
                                    return@launch
                                }
                                raf.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 500) {
                                    val timeDeltaSec = (now - lastTime) / 1000.0
                                    val bytesDelta = downloaded - lastDownloaded
                                    val speed = if (timeDeltaSec > 0) (bytesDelta / timeDeltaSec).toLong() else 0L
                                    val remaining = totalExpected - downloaded
                                    val eta = if (speed > 0) remaining / speed else 0L

                                    _downloadProgressMap.value = _downloadProgressMap.value + (model.id to DownloadProgress(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalExpected,
                                        progressPercent = (downloaded.toFloat() / totalExpected).coerceIn(0f, 1f),
                                        speedBytesPerSec = speed,
                                        etaSeconds = eta
                                    ))
                                    lastTime = now
                                    lastDownloaded = downloaded
                                }
                            }
                        }
                    }

                    if (!validateGgufHeader(tempFile)) {
                        tempFile.delete()
                        throw IllegalStateException("Скачанный файл повреждён (заголовок GGUF не прошёл валидацию)")
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        rescanInstalledModels()
                        _downloadProgressMap.value = _downloadProgressMap.value - model.id
                        _engineStatus.value = EngineStatus.INSTALLED
                    } else {
                        throw Exception("Не удалось переименовать файл модели")
                    }
                }
            } catch (ce: CancellationException) {
                Log.i(TAG, "Download job cancelled for ${model.name}")
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Download error for ${model.name}", e)
                _downloadProgressMap.value = _downloadProgressMap.value + (model.id to DownloadProgress(
                    isFailed = true,
                    errorMessage = e.localizedMessage ?: "Сбой при загрузке"
                ))
                _engineStatus.value = if (_installedFiles.value.isEmpty()) EngineStatus.NOT_INSTALLED else EngineStatus.INSTALLED
            }
        }

        downloadJobs[model.id] = job
    }

    fun pauseDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        val currentProgress = _downloadProgressMap.value[modelId]
        if (currentProgress != null) {
            _downloadProgressMap.value = _downloadProgressMap.value + (modelId to currentProgress.copy(isPaused = true))
        }
    }

    fun cancelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadProgressMap.value = _downloadProgressMap.value - modelId

        val model = getAllCatalogModels().find { it.id == modelId }
        val fileName = model?.fileName ?: ""
        if (fileName.isNotEmpty()) {
            val tempFile = File(modelsDir, "$fileName.download")
            if (tempFile.exists()) tempFile.delete()
        }

        _engineStatus.value = if (_installedFiles.value.isEmpty()) EngineStatus.NOT_INSTALLED else EngineStatus.INSTALLED
    }

    /**
     * Loads model into RAM asynchronously.
     * Uses honest memory comparison: checks totalMem - margin and threshold without false blocking.
     */
    suspend fun loadModelIntoRam(model: CatalogModel) = withContext(Dispatchers.Default) {
        val file = _installedFiles.value[model.id]
        if (file == null || !file.exists()) {
            _engineStatus.value = EngineStatus.NOT_INSTALLED
            _lastEngineError.value = "Файл модели отсутствует на диске"
            return@withContext
        }

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val thresholdGb = memInfo.threshold / (1024f * 1024f * 1024f)
        val availGb = memInfo.availMem / (1024f * 1024f * 1024f)

        // Usable memory ceiling (total minus threshold and Android OS system margin)
        val usableCeilingGb = totalGb - thresholdGb - 0.8f
        if (model.requiredRamGb > usableCeilingGb) {
            _engineStatus.value = EngineStatus.LOW_MEMORY
            _lastEngineError.value = "Устройству не хватает ОЗУ: требуется ${model.requiredRamGb} ГБ, доступный предел: %.1f ГБ".format(usableCeilingGb)
            return@withContext
        }

        if (availGb < model.requiredRamGb) {
            _ramWarning.value = "Свободной RAM сейчас: %.1f ГБ. Модель использует mmap и вытеснит фоновый кэш.".format(availGb)
        } else {
            _ramWarning.value = null
        }

        _engineStatus.value = EngineStatus.LOADING_TO_RAM
        _lastEngineError.value = null

        try {
            if (activeModelHandle > 0L) {
                LlamaBridge.freeModel(activeModelHandle)
                activeModelHandle = 0L
            }

            val handle = LlamaBridge.loadModel(
                modelPath = file.absolutePath,
                contextSize = _contextWindow.value,
                threads = _cpuThreads.value
            )

            if (handle > 0L) {
                activeModelHandle = handle
                _activeModel.value = model
                _engineStatus.value = EngineStatus.READY
                _needsReload.value = false
                Log.i(TAG, "Model ${model.name} loaded into RAM successfully with handle=$handle")
            } else {
                _engineStatus.value = EngineStatus.ERROR
                _lastEngineError.value = "Нативный движок вернул нулевой дескриптор"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model into RAM", e)
            _engineStatus.value = EngineStatus.ERROR
            _lastEngineError.value = e.localizedMessage ?: "Ошибка инициализации native llama.cpp"
        }
    }

    /**
     * Unloads model from RAM and frees native resources.
     */
    fun unloadActiveModel() {
        if (activeModelHandle > 0L) {
            LlamaBridge.freeModel(activeModelHandle)
            activeModelHandle = 0L
        }
        _activeModel.value = null
        _engineStatus.value = if (_installedFiles.value.isEmpty()) EngineStatus.NOT_INSTALLED else EngineStatus.INSTALLED
        _needsReload.value = false
        _ramWarning.value = null
    }

    /**
     * Deletes GGUF file from disk.
     */
    fun deleteModelFile(modelId: String) {
        val file = _installedFiles.value[modelId]
        if (file != null && file.exists()) {
            if (_activeModel.value?.id == modelId) {
                unloadActiveModel()
            }
            file.delete()
            rescanInstalledModels()
        }
    }

    fun setContextWindow(size: Int) {
        _contextWindow.value = size
        if (_activeModel.value != null) {
            _needsReload.value = true
        }
    }

    fun setCpuThreads(count: Int) {
        _cpuThreads.value = count
        if (_activeModel.value != null) {
            _needsReload.value = true
        }
    }

    fun setTemperature(temp: Float) {
        _temperature.value = temp
    }

    fun setTopP(p: Float) {
        _topP.value = p
    }

    fun setTopK(k: Int) {
        _topK.value = k
    }

    fun setRepeatPenalty(penalty: Float) {
        _repeatPenalty.value = penalty
    }

    fun setMaxTokens(tokens: Int) {
        _maxTokens.value = tokens
    }

    fun getMemoryInfo(): Pair<Float, Float> {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val availGb = memInfo.availMem / (1024f * 1024f * 1024f)
        return Pair(availGb, totalGb)
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            1_000_000_000L
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000L -> "%.2f ГБ".format(bytes / 1_000_000_000f)
            bytes >= 1_000_000L -> "%d МБ".format(bytes / 1_000_000L)
            else -> "%d КБ".format(bytes / 1000L)
        }
    }
}
