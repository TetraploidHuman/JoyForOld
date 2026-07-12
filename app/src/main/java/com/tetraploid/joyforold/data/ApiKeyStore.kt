package com.tetraploid.joyforold.data

import android.content.Context
import com.tetraploid.joyforold.BuildConfig

class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(): String {
        val saved = prefs.getString(KEY_API_KEY, "").orEmpty()
            .ifBlank { prefs.getString(LEGACY_KEY_API_KEY, "").orEmpty() }
        return saved.ifBlank { BuildConfig.LLM_API_KEY }
    }

    fun getModel(): String = BuildConfig.LLM_MODEL

    fun getAsrApiKey(): String {
        val saved = prefs.getString(KEY_ASR_API_KEY, "").orEmpty()
        return saved.ifBlank { BuildConfig.VOLC_ASR_API_KEY }
    }

    fun getAsrAppId(): String {
        val saved = prefs.getString(KEY_ASR_APP_ID, "").orEmpty()
        return saved.ifBlank { BuildConfig.VOLC_ASR_APP_ID }
    }

    fun getAsrAccessToken(): String {
        val saved = prefs.getString(KEY_ASR_ACCESS_TOKEN, "").orEmpty()
        return saved.ifBlank { BuildConfig.VOLC_ASR_ACCESS_TOKEN }
    }

    fun getAsrResourceId(): String {
        val saved = prefs.getString(KEY_ASR_RESOURCE_ID, "").orEmpty()
        return saved.ifBlank { BuildConfig.VOLC_ASR_RESOURCE_ID }
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun saveAsrConfig(
        apiKey: String,
        appId: String,
        accessToken: String,
        resourceId: String,
    ) {
        prefs.edit()
            .putString(KEY_ASR_API_KEY, apiKey.trim())
            .putString(KEY_ASR_APP_ID, appId.trim())
            .putString(KEY_ASR_ACCESS_TOKEN, accessToken.trim())
            .putString(KEY_ASR_RESOURCE_ID, resourceId.trim())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_API_KEY = "llm_api_key"
        private const val LEGACY_KEY_API_KEY = "deepseek_api_key"
        private const val KEY_ASR_API_KEY = "volc_asr_api_key"
        private const val KEY_ASR_APP_ID = "volc_asr_app_id"
        private const val KEY_ASR_ACCESS_TOKEN = "volc_asr_access_token"
        private const val KEY_ASR_RESOURCE_ID = "volc_asr_resource_id"
    }
}
