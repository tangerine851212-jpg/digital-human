package ai.guiji.duix.test.service

import ai.guiji.duix.test.model.VoiceCloneProfile
import android.content.Context
import org.json.JSONObject

class VoiceCloneProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): VoiceCloneProfile? {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            VoiceCloneProfile(
                voice = json.optString("voice"),
                targetModel = json.optString("targetModel"),
                preferredName = json.optString("preferredName"),
                samplePath = json.optString("samplePath"),
                avatarName = json.optString("avatarName"),
                avatarVoice = json.optString("avatarVoice"),
                avatarModelUrl = json.optString("avatarModelUrl"),
                avatarRes = json.optInt("avatarRes"),
                createdAt = json.optLong("createdAt")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(profile: VoiceCloneProfile) {
        val json = JSONObject()
        json.put("voice", profile.voice)
        json.put("targetModel", profile.targetModel)
        json.put("preferredName", profile.preferredName)
        json.put("samplePath", profile.samplePath)
        json.put("avatarName", profile.avatarName)
        json.put("avatarVoice", profile.avatarVoice)
        json.put("avatarModelUrl", profile.avatarModelUrl)
        json.put("avatarRes", profile.avatarRes)
        json.put("createdAt", profile.createdAt)
        prefs.edit().putString(KEY_PROFILE, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_clone_profile"
        private const val KEY_PROFILE = "active_profile"
    }
}