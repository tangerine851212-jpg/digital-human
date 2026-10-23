package ai.guiji.duix.test.service

import android.content.Context

class DebugSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isChatDebugEnabled(): Boolean {
        return prefs.getBoolean(KEY_CHAT_DEBUG_ENABLED, false)
    }

    fun setChatDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHAT_DEBUG_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_debug_settings"
        private const val KEY_CHAT_DEBUG_ENABLED = "chat_debug_enabled"
    }
}