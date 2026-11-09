package com.codecore.ai.training

import android.content.Context
import com.codecore.ai.model.ProjectFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

class DatasetManager(context: Context) {
    private val datasetFile = File(context.filesDir, "dataset_android_codecore.jsonl")

    fun getDatasetFile(): File = datasetFile

    fun getPairCount(): Int {
        if (!datasetFile.exists()) return 0
        return datasetFile.useLines { it.count { line -> line.isNotBlank() } }
    }

    fun getFileSizeFormatted(): String {
        if (!datasetFile.exists()) return "0 КБ"
        val bytes = datasetFile.length()
        return when {
            bytes >= 1_000_000 -> "%.2f МБ".format(bytes / 1_000_000f)
            else -> "%d КБ".format(bytes / 1000)
        }
    }

    suspend fun recordVerifiedPair(prompt: String, files: List<ProjectFile>) = withContext(Dispatchers.IO) {
        val filesArray = JSONArray()
        for (f in files) {
            filesArray.put(JSONObject().apply {
                put("path", f.path)
                put("content", f.content)
            })
        }
        val record = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("prompt", prompt)
            put("files", filesArray)
        }
        datasetFile.appendText(record.toString() + "\n")
    }

    suspend fun exportDataset(outputStream: OutputStream): Boolean = withContext(Dispatchers.IO) {
        if (!datasetFile.exists() || datasetFile.length() == 0L) return@withContext false
        try {
            datasetFile.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
