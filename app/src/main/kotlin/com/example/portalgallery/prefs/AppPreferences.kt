package com.example.portalgallery.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.portalgallery.data.album.AlbumList

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

    /**
     * Configured albums, in order, capped by [AlbumList.MAX_ALBUMS].
     *
     * Reading migrates the old single-album key on first access, so an existing install
     * keeps its album across the upgrade instead of silently emptying.
     */
    var albumUrls: List<String>
        get() {
            prefs.getString(KEY_ALBUM_URLS, null)?.let { return AlbumList.parse(it).urls }
            val legacy = prefs.getString(KEY_ALBUM_URL, null)
            if (!legacy.isNullOrBlank()) {
                val migrated = AlbumList.parse(legacy).urls
                prefs.edit()
                    .putString(KEY_ALBUM_URLS, AlbumList.serialise(migrated))
                    .remove(KEY_ALBUM_URL)
                    .apply()
                return migrated
            }
            return emptyList()
        }
        set(value) = prefs.edit()
            .putString(KEY_ALBUM_URLS, AlbumList.serialise(value.take(AlbumList.MAX_ALBUMS)))
            .apply()

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

    /**
     * Show only photos taken on today's day of the week.
     *
     * Off by default: it can thin the rotation dramatically — roughly a seventh of the
     * library, and nothing at all from an album shot over a single weekend.
     */
    var weekdayFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEEKDAY_FILTER, false)
        set(value) = prefs.edit().putBoolean(KEY_WEEKDAY_FILTER, value).apply()

    /**
     * Show several photos at once in a grid rather than one full-screen.
     *
     * On by default, unlike the filters above. Those default off because each can thin the
     * rotation to nothing; collage cannot — CollageSelector fills every slot or repeats a
     * photo rather than leaving a black tile. It also puts the orientation filter's
     * discards back on screen: a landscape panel drops every portrait photo, which is a
     * third of the frame album and over half of the trip fixture.
     */
    var collageEnabled: Boolean
        get() = prefs.getBoolean(KEY_COLLAGE, true)
        set(value) = prefs.edit().putBoolean(KEY_COLLAGE, value).apply()

    /**
     * Milliseconds between single-tile swaps in the living wall.
     *
     * One tile at a time, not the whole grid: six tiles at 3.3s each turns the grid over
     * at roughly the same rate as a 20s whole-grid swap, without the visual reset.
     */
    var collageTileSwapMs: Int
        get() = prefs.getInt(KEY_COLLAGE_SWAP_MS, 3_300)
        set(value) = prefs.edit().putInt(KEY_COLLAGE_SWAP_MS, value).apply()

    /** Minutes of grid between full-screen hero interludes. Also the seam where the
     *  layout template changes, so the grid is never seen reflowing. */
    var heroIntervalMinutes: Int
        get() = prefs.getInt(KEY_HERO_INTERVAL_MIN, 5)
        set(value) = prefs.edit().putInt(KEY_HERO_INTERVAL_MIN, value).apply()

    /** Each grid spans several years rather than one afternoon. On by default: it
     *  restratifies the draw rather than narrowing it, so it cannot thin the rotation. */
    var curationEraMix: Boolean
        get() = prefs.getBoolean(KEY_CURATION_ERA, true)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_ERA, value).apply()

    /**
     * Boost photos taken on today's calendar date in previous years.
     *
     * Off by default, for the same reason as [weekdayFilterEnabled] and more so: this is
     * 1-in-365 where that is 1-in-7, and plenty of dates will match nothing at all.
     */
    var curationOnThisDay: Boolean
        get() = prefs.getBoolean(KEY_CURATION_OTD, false)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_OTD, value).apply()

    /**
     * Reserve one slot per grid for recently added photos.
     *
     * On by default, and load-bearing. With era mix on and no reservation, a specific new
     * photo competes against its whole year bucket and surfaces roughly once every 33
     * hours — against every ~40 minutes on today's 300-photo single-photo rotation. The
     * reservation makes "photos the family adds actually show up" structural rather than
     * a matter of probability.
     */
    var curationRecency: Boolean
        get() = prefs.getBoolean(KEY_CURATION_RECENCY, true)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_RECENCY, value).apply()

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
        private const val KEY_ALBUM_URL = "album_url"       // legacy, migrated on read
        private const val KEY_ALBUM_URLS = "album_urls"
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
        private const val KEY_WEEKDAY_FILTER = "weekday_filter"
        private const val KEY_COLLAGE = "collage_enabled"
        private const val KEY_COLLAGE_SWAP_MS = "collage_swap_ms"
        private const val KEY_HERO_INTERVAL_MIN = "hero_interval_min"
        private const val KEY_CURATION_ERA = "curation_era_mix"
        private const val KEY_CURATION_OTD = "curation_on_this_day"
        private const val KEY_CURATION_RECENCY = "curation_recency"
        private const val KEY_PRESENCE = "presence_enabled"
        private const val KEY_ABSENCE_TIMEOUT = "absence_timeout_min"
    }
}
