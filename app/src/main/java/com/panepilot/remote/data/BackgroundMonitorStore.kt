package com.panepilot.remote.data

import android.content.Context

class BackgroundMonitorStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_monitor", Context.MODE_PRIVATE)

    fun activeProfileId(): String? =
        preferences.getString(ACTIVE_PROFILE_ID, null)?.takeIf { it.isNotBlank() }

    fun setActiveProfile(profileId: String) {
        preferences.edit().putString(ACTIVE_PROFILE_ID, profileId).apply()
    }

    fun clear(profileId: String? = null) {
        if (profileId == null || activeProfileId() == profileId) {
            preferences.edit().remove(ACTIVE_PROFILE_ID).apply()
        }
    }

    private companion object {
        const val ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
