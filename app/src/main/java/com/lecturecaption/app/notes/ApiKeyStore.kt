package com.lecturecaption.app.notes

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user-supplied Gemini API key locally, encrypted at rest via Android's Keystore
 * (EncryptedSharedPreferences). The key never leaves the device except in the direct HTTPS
 * request this app makes to Google's Gemini API when the user taps "Generate Notes" — it is
 * not sent anywhere else, logged, or bundled into exports/backups.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "lecturecaption_secure_prefs"
    private const val KEY_GEMINI = "gemini_api_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getGeminiKey(context: Context): String? =
        prefs(context).getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    fun setGeminiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI, key.trim()).apply()
    }

    fun clearGeminiKey(context: Context) {
        prefs(context).edit().remove(KEY_GEMINI).apply()
    }
}
