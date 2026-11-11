package com.codecore.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(context: Context) {
    private val TAG = "SecureStorage"
    private val prefs = context.getSharedPreferences("codecore_secure_vault", Context.MODE_PRIVATE)
    private val keyAlias = "codecore_master_key"
    private val androidKeyStore = "AndroidKeyStore"

    init {
        initKeyStore()
    }

    private fun initKeyStore() {
        try {
            val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidKeyStore", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    fun encryptAndSave(key: String, plainText: String) {
        if (plainText.isEmpty()) {
            prefs.edit().remove("${key}_iv").remove("${key}_data").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            prefs.edit()
                .putString("${key}_iv", ivB64)
                .putString("${key}_data", dataB64)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting key: $key", e)
        }
    }

    fun decryptAndGet(key: String): String {
        val ivB64 = prefs.getString("${key}_iv", null) ?: return ""
        val dataB64 = prefs.getString("${key}_data", null) ?: return ""
        return try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(dataB64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting key: $key", e)
            ""
        }
    }

    fun getGitHubToken(): String = decryptAndGet("github_pat")
    fun saveGitHubToken(token: String) = encryptAndSave("github_pat", token)

    fun getGeminiApiKey(): String = decryptAndGet("gemini_key")
    fun saveGeminiApiKey(key: String) = encryptAndSave("gemini_key", key)

    fun getGeminiModel(): String = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    fun saveGeminiModel(model: String) = prefs.edit().putString("gemini_model", model).apply()
}
