package ai.guiji.duix.test.service

import android.content.Context
import android.net.Uri
import java.util.Locale
import java.util.UUID

class DeviceIdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateDeviceUuid(): String {
        val current = prefs.getString(KEY_DEVICE_UUID, null)?.trim()?.lowercase(Locale.US)
        if (!current.isNullOrBlank()) {
            return current
        }

        val deviceUuid = UUID.randomUUID().toString().lowercase(Locale.US)
        prefs.edit().putString(KEY_DEVICE_UUID, deviceUuid).apply()
        return deviceUuid
    }

    fun getBindUrl(): String {
        val deviceUuid = getOrCreateDeviceUuid()
        return prefs.getString(KEY_BIND_URL, null)?.takeIf { it.isNotBlank() }
            ?: buildFallbackBindUrl(deviceUuid)
    }

    fun saveBindUrl(bindUrl: String) {
        prefs.edit().putString(KEY_BIND_URL, bindUrl.trim()).apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveDeviceToken(token: String) {
        prefs.edit().putString(KEY_DEVICE_TOKEN, token.trim()).apply()
    }

    fun buildFallbackBindUrl(deviceUuid: String = getOrCreateDeviceUuid()): String {
        val normalized = deviceUuid.trim().lowercase(Locale.US)
        return DashScopeConfig.MONITOR_BASE_URL.trimEnd('/') +
            "/bind?deviceUuid=" + Uri.encode(normalized)
    }

    companion object {
        private const val PREFS_NAME = "device_identity_store"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_BIND_URL = "bind_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
