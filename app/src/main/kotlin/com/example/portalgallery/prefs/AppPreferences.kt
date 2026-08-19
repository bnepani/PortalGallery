package com.example.portalgallery.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("portal_gallery", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    var selectedAlbumIds: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED_ALBUMS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SELECTED_ALBUMS, value).apply()

    var slideshowIntervalSeconds: Int
        get() = prefs.getInt(KEY_SLIDESHOW_INTERVAL, 8)
        set(value) = prefs.edit().putInt(KEY_SLIDESHOW_INTERVAL, value).apply()

    var albumUrl: String?
        get() = prefs.getString(KEY_ALBUM_URL, null)
        set(value) = prefs.edit().putString(KEY_ALBUM_URL, value).apply()

    /** Health signal. Must be paired with a render timestamp — a fresh sync on a dead
     *  frame reports healthy, which is exactly the lie this pair exists to prevent. */
    var lastSyncMs: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastSyncSummary: String?
        get() = prefs.getString(KEY_LAST_SYNC_SUMMARY, null)
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_SUMMARY, value).apply()

    /** Quiet hours. Off by default — a frame that unexpectedly goes dark reads as broken. */
    var sleepEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SLEEP_ENABLED, value).apply()

    /** "HH:mm" local wall-clock. */
    var sleepStart: String
        get() = prefs.getString(KEY_SLEEP_START, "00:00") ?: "00:00"
        set(value) = prefs.edit().putString(KEY_SLEEP_START, value).apply()

    var sleepEnd: String
        get() = prefs.getString(KEY_SLEEP_END, "07:00") ?: "07:00"
        set(value) = prefs.edit().putString(KEY_SLEEP_END, value).apply()

    /** Stored as the enum name; see Transition.from() for the fallback. */
    var transition: String
        get() = prefs.getString(KEY_TRANSITION, "CROSSFADE") ?: "CROSSFADE"
        set(value) = prefs.edit().putString(KEY_TRANSITION, value).apply()

    var transitionMs: Int
        get() = prefs.getInt(KEY_TRANSITION_MS, 1500)
        set(value) = prefs.edit().putInt(KEY_TRANSITION_MS, value).apply()

    /** Slow pan-and-zoom across each photo while it is displayed. */
    var kenBurnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEN_BURNS, true)
        set(value) = prefs.edit().putBoolean(KEY_KEN_BURNS, value).apply()

    /** Download and play videos. Off by default: clips come down at original quality
     *  via =dv, so enabling this can multiply the library size several times over. */
    var videoEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO, value).apply()

    /** Audio is additionally gated on someone being in view; see AwakePolicy. */
    var videoAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_AUDIO, value).apply()

    /** 0-100. */
    var videoVolume: Int
        get() = prefs.getInt(KEY_VIDEO_VOLUME, 70)
        set(value) = prefs.edit().putInt(KEY_VIDEO_VOLUME, value).apply()

    /** Camera presence detection. Off by default — this turns on a camera in someone's
     *  living room and should be an explicit choice, never a surprise. */
    var presenceEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRESENCE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRESENCE, value).apply()

    /** Minutes of an empty room before the frame sleeps. */
    var absenceTimeoutMinutes: Int
        get() = prefs.getInt(KEY_ABSENCE_TIMEOUT, 5)
        set(value) = prefs.edit().putInt(KEY_ABSENCE_TIMEOUT, value).apply()

    /** Set by "sleep now" / "wake now" commands; cleared at the next scheduled boundary. */
    var manualSleepOverride: Boolean?
        get() = if (!prefs.contains(KEY_MANUAL_SLEEP)) null
        else prefs.getBoolean(KEY_MANUAL_SLEEP, false)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_MANUAL_SLEEP) else putBoolean(KEY_MANUAL_SLEEP, value)
        }.apply()

    val isSignedIn: Boolean get() = accessToken != null

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_SELECTED_ALBUMS = "selected_albums"
        private const val KEY_SLIDESHOW_INTERVAL = "slideshow_interval"
        private const val KEY_ALBUM_URL = "album_url"
        private const val KEY_LAST_SYNC = "last_sync_ms"
        private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_SLEEP_START = "sleep_start"
        private const val KEY_SLEEP_END = "sleep_end"
        private const val KEY_MANUAL_SLEEP = "manual_sleep"
        private const val KEY_TRANSITION = "transition"
        private const val KEY_TRANSITION_MS = "transition_ms"
        private const val KEY_KEN_BURNS = "ken_burns"
        private const val KEY_VIDEO = "video_enabled"
        private const val KEY_VIDEO_AUDIO = "video_audio_enabled"
        private const val KEY_VIDEO_VOLUME = "video_volume"
        private const val KEY_PRESENCE = "presence_enabled"
        private const val KEY_ABSENCE_TIMEOUT = "absence_timeout_min"
    }
}
