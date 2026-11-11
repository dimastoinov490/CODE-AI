package com.codecore.ai.github

import android.content.Context
import android.util.Base64
import android.util.Log
import com.codecore.ai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class GitHubClient(private val context: Context) {
    private val TAG = "GitHubClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val prefs = context.getSharedPreferences("codecore_github_registry", Context.MODE_PRIVATE)

    /**
     * Local registry of repos created by this app.
     */
    fun getCreatedRepos(): List<CreatedRepoInfo> {
        val raw = prefs.getString("registered_repos", "[]") ?: "[]"
        val array = JSONArray(raw)
        val list = mutableListOf<CreatedRepoInfo>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                CreatedRepoInfo(
                    owner = obj.getString("owner"),
                    repo = obj.getString("repo"),
                    projectId = obj.getString("projectId"),
                    createdAt = obj.optLong("createdAt", 0L)
                )
            )
        }
        return list
    }

    private fun registerCreatedRepo(owner: String, repo: String, projectId: String) {
        val current = getCreatedRepos().filter { !(it.owner == owner && it.repo == repo) }
        val updated = current + CreatedRepoInfo(owner, repo, projectId)
        val array = JSONArray()
        for (item in updated) {
            array.put(JSONObject().apply {
                put("owner", item.owner)
                put("repo", item.repo)
                put("projectId", item.projectId)
                put("createdAt", item.createdAt)
            })
        }
        prefs.edit().putString("registered_repos", array.toString()).apply()
    }

    /**
     * CODE-LEVEL SECURITY GUARD:
     * Write actions (commit, push, workflow dispatch, self-healing) are strictly permitted
     * ONLY on repositories with "cc-" prefix created by CodeCore AI in the local registry.
     */
    fun assertRepoSafe(owner: String, repo: String) {
        if (!repo.startsWith("cc-")) {
            throw SecurityException("Защита: Репозиторий '$repo' не имеет обязательного префикса 'cc-'. Операции записи запрещены.")
        }
        val isRegistered = getCreatedRepos().any { it.owner.equals(owner, ignoreCase = true) && it.repo.equals(repo, ignoreCase = true) }
        if (!isRegistered) {
            throw SecurityException("Защита: Репозиторий '$owner/$repo' отсутствует в локальном реестре созданных этим приложением. Запись в чужие репозитории строго заблокирована.")
        }
    }

    /**
     * Verifies GitHub Personal Access Token via GET /user and inspects X-OAuth-Scopes header.
     */
    suspend fun verifyToken(token: String): Result<Pair<String, List<String>>> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(IllegalArgumentException("Токен не может быть пустым"))

        try {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        val login = json.getString("login")
                        val scopesHeader = response.header("X-OAuth-Scopes") ?: ""
                        val scopes = scopesHeader.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        Result.success(Pair(login, scopes))
                    }
                    401 -> Result.failure(Exception("HTTP 401 Unauthorized: Токен недействителен или отозван на GitHub"))
                    403 -> {
                        val remaining = response.header("x-ratelimit-remaining") ?: ""
                        if (remaining == "0") {
                            Result.failure(Exception("HTTP 403: Превышен лимит запросов GitHub API (Rate Limit)"))
                        } else {
                            Result.failure(Exception("HTTP 403 Forbidden: Доступ запрещён (проверьте права токена)"))
                        }
                    }
                    else -> Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying token", e)
            Result.failure(e)
        }
    }

    /**
     * Creates new repository under user's account with auto_init=true and prefix "cc-".
     */
    suspend fun createRepository(
        token: String,
        projectSlug: String,
        projectId: String,
        isPrivate: Boolean
    ): Result<CreatedRepoInfo> = withContext(Dispatchers.IO) {
        try {
            val userRes = verifyToken(token)
            if (userRes.isFailure) return@withContext Result.failure(userRes.exceptionOrNull()!!)
            val owner = userRes.getOrNull()!!.first

            val sanitizedSlug = projectSlug.lowercase().replace("[^a-z0-9-]".toRegex(), "-").take(25).trim('-')
            var baseName = "cc-${if (sanitizedSlug.isBlank()) "app" else sanitizedSlug}"
            var targetName = baseName
            var attempt = 1

            while (attempt <= 10) {
                val payload = JSONObject().apply {
                    put("name", targetName)
                    put("description", "Built autonomously by CodeCore AI Mobile IDE")
                    put("private", isPrivate)
                    put("auto_init", true)
                }

                val request = Request.Builder()
                    .url("https://api.github.com/user/repos")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 201) {
                        val body = response.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        val repoName = json.getString("name")
                        registerCreatedRepo(owner, repoName, projectId)
                        return@withContext Result.success(CreatedRepoInfo(owner, repoName, projectId))
                    } else if (response.code == 422) {
                        // Name already exists, increment suffix
                        targetName = "$baseName-$attempt"
                        attempt++
                    } else {
                        throw Exception("Ошибка создания репозитория (HTTP ${response.code}): ${response.body?.string() ?: response.message}")
                    }
                }
            }
            Result.failure(Exception("Не удалось подобрать уникальное имя репозитория после 10 попыток"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create repo", e)
            Result.failure(e)
        }
    }

    /**
     * Determines default branch of repository via GET /repos/{owner}/{repo}.
     */
    suspend fun getDefaultBranch(token: String, owner: String, repo: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("default_branch", "main").ifBlank { "main" }
                } else {
                    "main"
                }
            }
        } catch (e: Exception) {
            "main"
        }
    }

    /**
     * Pushes all project files into created repo in a single commit using Git Data API.
     */
    suspend fun pushProjectFiles(
        token: String,
        owner: String,
        repo: String,
        files: List<ProjectFile>,
        commitMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            assertRepoSafe(owner, repo)

            // 1. Get default branch and HEAD reference
            val defaultBranch = getDefaultBranch(token, owner, repo)
            val refUrl = "https://api.github.com/repos/$owner/$repo/git/refs/heads/$defaultBranch"
            val refReq = Request.Builder()
                .url(refUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            var parentCommitSha: String
            httpClient.newCall(refReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Не удалось получить $defaultBranch ref (HTTP ${response.code}): ${response.body?.string() ?: response.message}")
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                parentCommitSha = json.getJSONObject("object").getString("sha")
            }

            // 2. Upload blobs for each file
            val treeEntries = JSONArray()
            for (file in files) {
                if (file.name == "project_meta.json") continue

                val blobUrl = "https://api.github.com/repos/$owner/$repo/git/blobs"
                val isBinary = file.extension.equals("jar", ignoreCase = true) || file.extension.equals("png", ignoreCase = true)

                val blobPayload = JSONObject().apply {
                    if (isBinary) {
                        put("content", Base64.encodeToString(file.content.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP))
                        put("encoding", "base64")
                    } else {
                        put("content", file.content)
                        put("encoding", "utf-8")
                    }
                }

                val blobReq = Request.Builder()
                    .url(blobUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .post(blobPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                var blobSha: String
                httpClient.newCall(blobReq).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Ошибка создания blob для ${file.path} (HTTP ${response.code})")
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    blobSha = json.getString("sha")
                }

                val mode = if (file.name == "gradlew") "100755" else "100644"
                treeEntries.put(JSONObject().apply {
                    put("path", file.path)
                    put("mode", mode)
                    put("type", "blob")
                    put("sha", blobSha)
                })
            }

            // 3. Create Tree
            val treeUrl = "https://api.github.com/repos/$owner/$repo/git/trees"
            val treePayload = JSONObject().apply {
                put("base_tree", parentCommitSha)
                put("tree", treeEntries)
            }
            val treeReq = Request.Builder()
                .url(treeUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(treePayload.toString().toRequestBody(jsonMediaType))
                .build()

            var newTreeSha: String
            httpClient.newCall(treeReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Ошибка создания git tree (HTTP ${response.code}): ${response.body?.string()}")
                }
                newTreeSha = JSONObject(response.body?.string() ?: "{}").getString("sha")
            }

            // 4. Create Commit
            val commitUrl = "https://api.github.com/repos/$owner/$repo/git/commits"
            val commitPayload = JSONObject().apply {
                put("message", commitMessage.ifBlank { "feat: update CodeCore AI Android project" })
                put("tree", newTreeSha)
                put("parents", JSONArray().put(parentCommitSha))
            }
            val commitReq = Request.Builder()
                .url(commitUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(commitPayload.toString().toRequestBody(jsonMediaType))
                .build()

            var newCommitSha: String
            httpClient.newCall(commitReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Ошибка создания git commit (HTTP ${response.code}): ${response.body?.string()}")
                }
                newCommitSha = JSONObject(response.body?.string() ?: "{}").getString("sha")
            }

            // 5. Update main ref
            val updateRefPayload = JSONObject().apply {
                put("sha", newCommitSha)
                put("force", false)
            }
            val updateRefReq = Request.Builder()
                .url(refUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .patch(updateRefPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(updateRefReq).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Ошибка обновления main ref (HTTP ${response.code}): ${response.body?.string()}")
                }
            }

            Result.success(newCommitSha)
        } catch (e: Exception) {
            Log.e(TAG, "Push failed", e)
            Result.failure(e)
        }
    }

    /**
     * Polls workflow runs for the repository.
     */
    suspend fun getWorkflowRuns(
        token: String,
        owner: String,
        repo: String,
        headSha: String? = null
    ): Result<List<WorkflowRunInfo>> = withContext(Dispatchers.IO) {
        try {
            val url = if (headSha != null) {
                "https://api.github.com/repos/$owner/$repo/actions/runs?head_sha=$headSha&per_page=5"
            } else {
                "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=5"
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val runsArray = json.optJSONArray("workflow_runs") ?: JSONArray()
                val list = mutableListOf<WorkflowRunInfo>()

                for (i in 0 until runsArray.length()) {
                    val r = runsArray.getJSONObject(i)
                    list.add(
                        WorkflowRunInfo(
                            id = r.getLong("id"),
                            name = r.optString("name", "Build Android APK"),
                            status = r.optString("status", "unknown"),
                            conclusion = if (r.isNull("conclusion")) null else r.getString("conclusion"),
                            htmlUrl = r.optString("html_url", ""),
                            headSha = r.optString("head_sha", ""),
                            runNumber = r.optInt("run_number", 0),
                            createdAt = r.optString("created_at", "")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves jobs for a specific workflow run.
     */
    suspend fun getWorkflowJobs(
        token: String,
        owner: String,
        repo: String,
        runId: Long
    ): Result<List<WorkflowJobInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val json = JSONObject(response.body?.string() ?: "{}")
                val jobsArray = json.optJSONArray("jobs") ?: JSONArray()
                val list = mutableListOf<WorkflowJobInfo>()

                for (i in 0 until jobsArray.length()) {
                    val jobObj = jobsArray.getJSONObject(i)
                    val stepsArray = jobObj.optJSONArray("steps") ?: JSONArray()
                    val stepsList = mutableListOf<String>()
                    for (s in 0 until stepsArray.length()) {
                        val step = stepsArray.getJSONObject(s)
                        stepsList.add("${step.optString("name")}: ${step.optString("status")}/${step.optString("conclusion", "-")}")
                    }

                    list.add(
                        WorkflowJobInfo(
                            id = jobObj.getLong("id"),
                            name = jobObj.optString("name", "Build Debug APK"),
                            status = jobObj.optString("status", "unknown"),
                            conclusion = if (jobObj.isNull("conclusion")) null else jobObj.getString("conclusion"),
                            stepNames = stepsList
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads raw logs from a failed workflow job (follows redirect).
     */
    suspend fun getJobLogs(
        token: String,
        owner: String,
        repo: String,
        jobId: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/jobs/$jobId/logs")
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code} fetching logs"))
                val text = response.body?.string() ?: ""
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads app-debug.apk artifact from a successful run and extracts it.
     */
    suspend fun downloadArtifact(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        destinationApkFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val artifactsReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .build()

            var downloadUrl: String? = null
            httpClient.newCall(artifactsReq).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code} listing artifacts")
                val json = JSONObject(response.body?.string() ?: "{}")
                val artifacts = json.optJSONArray("artifacts") ?: JSONArray()
                for (i in 0 until artifacts.length()) {
                    val art = artifacts.getJSONObject(i)
                    if (art.optString("name").contains("app-debug", ignoreCase = true)) {
                        downloadUrl = art.getString("archive_download_url")
                        break
                    }
                }
            }

            if (downloadUrl == null) {
                throw Exception("Артефакт app-debug не найден в завершённом пайплайне")
            }

            val dlReq = Request.Builder()
                .url(downloadUrl!!)
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(dlReq).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code} downloading zip archive")
                val body = response.body ?: throw Exception("Empty artifact response body")

                destinationApkFile.parentFile?.mkdirs()
                ZipInputStream(body.byteStream()).use { zis ->
                    var entry = zis.nextEntry
                    var foundApk = false
                    while (entry != null) {
                        if (entry.name.endsWith(".apk", ignoreCase = true)) {
                            FileOutputStream(destinationApkFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            foundApk = true
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    if (!foundApk) throw Exception("Архив артефакта не содержит .apk файла")
                }
            }

            Result.success(destinationApkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading artifact", e)
            Result.failure(e)
        }
    }
}
