package com.lecturecaption.app.stt

import android.content.Context

/** User switch for the "accurate transcript" (Whisper) pass. On by default once a model is installed. */
object RefinerPrefs {
    private const val PREFS = "refiner_prefs"
    private const val KEY_ENABLED = "whisper_refine_enabled"
    private const val KEY_ONLINE = "online_transcribe_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Optional online mode (Gemini). OFF by default and only ever turned on by the user after a
     * warning that speech audio is sent to Google. Never enabled implicitly.
     */
    fun isOnlineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONLINE, false)

    fun setOnlineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONLINE, enabled).apply()
    }
}
