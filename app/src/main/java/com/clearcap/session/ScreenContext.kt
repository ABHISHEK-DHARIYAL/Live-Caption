package com.clearcap.session

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.clearcap.text.ContextVocabulary

/**
 * Process-wide hand-off between the optional screen reader ([ContextAccessibilityService]) and the recording.
 * The words live in memory only, are cleared when a recording starts and again when it ends, and are never saved,
 * logged or sent anywhere. Reading happens only while [active] is true, i.e. while a recording is running and the
 * user has turned the feature on.
 */
object ScreenContext {
    val vocab = ContextVocabulary(maxTerms = 150)
    @Volatile var active: Boolean = false
        private set

    fun begin(enabled: Boolean) { vocab.clear(); active = enabled }
    /** Stops reading the screen but keeps the words, so captions still being finished use them. */
    fun stopReading() { active = false }
    fun end() { active = false; vocab.clear() }

    /** True when the user has switched the service on in Android's Accessibility settings. */
    fun serviceEnabled(ctx: Context): Boolean {
        val want = ComponentName(ctx, ContextAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == want }
    }
}
