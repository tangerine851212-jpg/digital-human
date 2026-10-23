package ai.guiji.duix.test.service

import ai.guiji.duix.test.model.AudienceSegment
import android.content.Context

class AudiencePreferenceStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSegment(): AudienceSegment? {
        return AudienceSegment.fromStorageKey(preferences.getString(KEY_SEGMENT, null))
    }

    fun saveSegment(segment: AudienceSegment) {
        preferences.edit().putString(KEY_SEGMENT, segment.storageKey).apply()
    }

    private companion object {
        const val PREFS_NAME = "audience_profile"
        const val KEY_SEGMENT = "audience_segment"
    }
}
