package com.panepilot.remote.data

import android.content.Context

class BackgroundMonitorStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("panepilot_remote_monitor", Context.MODE_PRIVATE)

    fun monitoredProfileIds(): Set<String> {
        val stored = preferences.getStringSet(MONITORED_PROFILE_IDS, emptySet())
            .orEmpty()
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        if (stored.isNotEmpty()) return stored

        val legacy = preferences.getString(LEGACY_ACTIVE_PROFILE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return emptySet()
        preferences.edit()
            .putStringSet(MONITORED_PROFILE_IDS, setOf(legacy))
            .putString(LAST_ACTIVE_PROFILE_ID, legacy)
            .remove(LEGACY_ACTIVE_PROFILE_ID)
            .apply()
        return setOf(legacy)
    }

    fun lastActiveProfileId(): String? =
        preferences.getString(LAST_ACTIVE_PROFILE_ID, null)
            ?.takeIf { it.isNotBlank() && it in monitoredProfileIds() }

    fun addProfile(profileId: String, makeActive: Boolean = true) {
        val updated = monitoredProfileIds() + profileId
        preferences.edit()
            .putStringSet(MONITORED_PROFILE_IDS, updated)
            .apply {
                if (makeActive) putString(LAST_ACTIVE_PROFILE_ID, profileId)
            }
            .apply()
    }

    fun makeActive(profileId: String) {
        if (profileId in monitoredProfileIds()) {
            preferences.edit().putString(LAST_ACTIVE_PROFILE_ID, profileId).apply()
        }
    }

    fun removeProfile(profileId: String) {
        val updated = monitoredProfileIds() - profileId
        val replacement = if (lastActiveProfileId() == profileId) updated.firstOrNull()
        else lastActiveProfileId()
        preferences.edit()
            .putStringSet(MONITORED_PROFILE_IDS, updated)
            .apply {
                if (replacement == null) remove(LAST_ACTIVE_PROFILE_ID)
                else putString(LAST_ACTIVE_PROFILE_ID, replacement)
            }
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(MONITORED_PROFILE_IDS)
            .remove(LAST_ACTIVE_PROFILE_ID)
            .remove(LEGACY_ACTIVE_PROFILE_ID)
            .apply()
    }

    private companion object {
        const val MONITORED_PROFILE_IDS = "monitored_profile_ids"
        const val LAST_ACTIVE_PROFILE_ID = "last_active_profile_id"
        const val LEGACY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
