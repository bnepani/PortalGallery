package com.example.portalgallery.ui.slideshow

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.portalgallery.BuildConfig
import com.example.portalgallery.R
import com.example.portalgallery.data.album.AlbumList
import com.example.portalgallery.data.presence.PresenceDetector
import com.example.portalgallery.data.presence.PresenceService
import com.example.portalgallery.data.schedule.AwakePolicy
import com.example.portalgallery.data.schedule.CollageSelector
import com.example.portalgallery.data.schedule.PhotoSelector
import com.example.portalgallery.data.schedule.SleepSchedule
import com.example.portalgallery.data.schedule.WakeAlarm
import com.example.portalgallery.data.store.AlbumSync
import com.example.portalgallery.data.store.PhotoStore
import com.example.portalgallery.databinding.ActivitySlideshowBinding
import com.example.portalgallery.prefs.AppPreferences
import com.example.portalgallery.ui.AppForeground
import com.example.portalgallery.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * The frame.
 *
 * Core invariant: **this Activity never makes a blocking network call.** It renders
 * whatever is already on disk, immediately. Syncing happens in the background and only
 * ever adds to the library; a failed sync changes nothing on screen.
 */
class SlideshowActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PortalGallery"
        const val EXTRA_ALBUM_URL = "album_url"
        const val EXTRA_ALBUM_ADD = "album_add"
        const val EXTRA_ALBUM_REMOVE = "album_remove"
        const val EXTRA_SLEEP = "sleep"
        const val EXTRA_SLEEP_START = "sleep_start"
        const val EXTRA_SLEEP_END = "sleep_end"
        const val EXTRA_COMMAND = "command"

        private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val SLEEP_TICK_MS = 60_000L

        /** Faster tick when presence drives the frame — waiting up to a minute for the
         *  photos to come back after walking into the room would feel broken. */
        private const val PRESENCE_TICK_MS = 3_000L

        /**
         * Whether the slideshow specifically is on screen. Used locally to decide
         * whether waking needs to pull the Activity forward.
         *
         * Distinct from [AppForeground], which is process-wide and is what
         * PresenceService consults — the settings screen counts as "app visible" but
         * not as "slideshow visible".
         */
        @Volatile
        private var isInForeground: Boolean = false
        private const val HINT_VISIBLE_MS = 8_000L

        /** How far Ken Burns zooms over a full dwell. Subtle on purpose. */
        private const val KEN_BURNS_SCALE = 1.08f

        /**
         * How many whole-grid draws to try before accepting that a slot cannot be filled
         * with something not already on the wall.
         *
         * CollageSelector de-duplicates within one call but knows nothing about the five
         * tiles already up, so a single-slot draw can legitimately collide. Four attempts
         * because each is a cheap pure-Kotlin pass and the alternative — a duplicate
         * photograph in two tiles of a six-tile grid — is glaringly obvious on a wall.
         */
        private const val DEDUP_ATTEMPTS = 4

        /**
         * Consecutive watchdog retries of one stuck tile before it is left alone.
         *
         * The same reasoning as consecutiveFailures in showStill(): a library full of
         * unreadable files must not spin the frame at full speed. Unlike the full-screen
         * path there is nothing to fall back to, so it stops retrying and lets the tile
         * keep its last good photo.
         */
        private const val MAX_SLOT_RETRIES = 3
    }

    private lateinit var binding: ActivitySlideshowBinding
    private lateinit var prefs: AppPreferences
    private lateinit var store: PhotoStore
    private lateinit var presence: PresenceDetector

    private var library: List<PhotoStore.StoredPhoto> = emptyList()
    private var photos: List<PhotoStore.StoredPhoto> = emptyList()

    private var currentIndex = 0
    private var isPaused = false
    private var lastRenderedAtMs = 0L
    private var consecutiveFailures = 0

    private var isAsleep = false
    private var lastScheduledAsleep: Boolean? = null

    /** Date the current selection was made for; drives the midnight re-filter. */
    private var lastFilterDay: LocalDate? = null

    /** Which ImageView currently holds the visible photo. Flips on every advance. */
    private var frontIsA = true

    // --- collage state ---
    //
    // All of it inert while prefs.collageEnabled is false: collageMode() gates every
    // entry point, so the single-photo path below runs exactly as it did before collage
    // existed. That fallback is the reason the two modes are kept side by side rather
    // than unified.

    private lateinit var collage: CollageRenderer

    /**
     * The tile pool: the whole library, minus videos, optionally narrowed to today's
     * weekday. **Not** [photos] — that one is orientation-filtered, and slot tags do the
     * orientation matching now. Passing the filtered set would throw away the portrait
     * photographs the tags exist to put back on screen.
     */
    private var collagePool: List<PhotoStore.StoredPhoto> = emptyList()

    /** Advances only behind a hero, so the grid is never seen reflowing. */
    private var templateRotation = 0

    /** Feeds CollageSelector's per-grid bucket rotation; every draw gets a fresh value. */
    private var gridRotation = 0

    /** True from the moment a hero interlude starts until the grid is back. */
    private var heroActive = false

    /** Consecutive watchdog retries per slot; reset whenever the wall is healthy. */
    private val slotRetries = IntArray(CollageLayout.MAX_SLOTS)

    private val handler = Handler(Looper.getMainLooper())
    private val advanceRunnable = Runnable { advance() }
    private val heroRunnable = Runnable { startHero() }
    private val heroEndRunnable = Runnable { endHero() }

    private val front: ImageView get() = if (frontIsA) binding.ivPhotoA else binding.ivPhotoB
    private val back: ImageView get() = if (frontIsA) binding.ivPhotoB else binding.ivPhotoA

    /** Post this only through [restartWatchdog] — see its KDoc for why. */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (collageMode() && collage.visible && !heroActive) {
                checkCollageStall()
            } else {
                val interval = prefs.slideshowIntervalSeconds * 1000L
                val stalled = !isPaused && !isAsleep &&
                    photos.isNotEmpty() &&
                    lastRenderedAtMs > 0 &&
                    System.currentTimeMillis() - lastRenderedAtMs > interval * 3
                if (stalled) {
                    Log.w(TAG, "watchdog: no render in ${interval * 3}ms, forcing advance")
                    advance()
                }
            }
            // Via restartWatchdog() rather than a bare postDelayed, so that stays the
            // single post site — and so any stray duplicate loop is removed the next
            // time this one fires, instead of surviving until the next sleep or pause.
            restartWatchdog()
        }
    }

    private val sleepTickRunnable = object : Runnable {
        override fun run() {
            evaluateSleepState()
            handler.postDelayed(this, tickIntervalMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        prefs = AppPreferences(this)
        store = PhotoStore(this)
        // Shared with PresenceService, which owns its lifecycle. The Activity only reads
        // state from it — detection must outlive this Activity.
        presence = PresenceDetector.get(this)
        startPresenceIfEnabled()

        // `this` as the Context, not applicationContext: Glide ties its request manager to
        // the Activity lifecycle, and tile loads must not outlive it.
        collage = CollageRenderer(
            context = this,
            container = binding.flCollage,
            tiles = listOf(
                CollageRenderer.Tile(binding.flSlot0, binding.ivTile0a, binding.ivTile0b),
                CollageRenderer.Tile(binding.flSlot1, binding.ivTile1a, binding.ivTile1b),
                CollageRenderer.Tile(binding.flSlot2, binding.ivTile2a, binding.ivTile2b),
                CollageRenderer.Tile(binding.flSlot3, binding.ivTile3a, binding.ivTile3b),
                CollageRenderer.Tile(binding.flSlot4, binding.ivTile4a, binding.ivTile4b),
                CollageRenderer.Tile(binding.flSlot5, binding.ivTile5a, binding.ivTile5b),
            ),
            prefs = prefs,
            handler = handler,
        )
        collage.nextPhoto = { slot -> pickForSlot(slot) }

        applyConfigIntent(intent)

        binding.root.setOnClickListener { togglePause() }
        binding.root.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        handler.postDelayed({ binding.tvHint.visibility = View.GONE }, HINT_VISIBLE_MS)

        handler.post(sleepTickRunnable)
        loadFromDiskThenSync()
    }

    /**
     * launchMode is singleTask, so `am start` against a running app arrives here rather
     * than in onCreate. Without this, every adb config command would be silently
     * ignored unless the app happened to be dead.
     */
    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        newIntent?.let {
            setIntent(it)
            applyConfigIntent(it)
        }
    }

    private fun applyConfigIntent(source: Intent?) {
        val i = source ?: return

        // Accepts one link or several (newline- or comma-separated), replacing the
        // configured set. `album_add` appends instead, so a new album can be added
        // without re-typing the others.
        i.getStringExtra(EXTRA_ALBUM_URL)?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = AlbumList.parse(raw)
            parsed.rejected.forEach { (url, why) -> Log.w(TAG, "album rejected: $url — $why") }
            if (parsed.urls.isEmpty()) {
                Log.e(TAG, "album_url had no usable links — keeping the existing set")
            } else {
                prefs.albumUrls = parsed.urls
                Log.i(TAG, "albums set via intent: ${parsed.urls.size}")
            }
        }

        i.getStringExtra(EXTRA_ALBUM_ADD)?.takeIf { it.isNotBlank() }?.let { raw ->
            val merged = AlbumList.parse(
                AlbumList.serialise(prefs.albumUrls) + AlbumList.DELIMITER + raw
            )
            merged.rejected.forEach { (url, why) -> Log.w(TAG, "album rejected: $url — $why") }
            prefs.albumUrls = merged.urls
            Log.i(TAG, "albums after add: ${merged.urls.size}")
        }

        i.getStringExtra(EXTRA_ALBUM_REMOVE)?.takeIf { it.isNotBlank() }?.let { raw ->
            val drop = AlbumList.parse(raw).urls.toSet() + raw.trim()
            prefs.albumUrls = prefs.albumUrls.filterNot { it in drop }
            Log.i(TAG, "albums after remove: ${prefs.albumUrls.size}")
        }

        i.getStringExtra(EXTRA_SLEEP)?.lowercase()?.let {
            when (it) {
                "on", "true", "enabled" -> prefs.sleepEnabled = true
                "off", "false", "disabled" -> prefs.sleepEnabled = false
                else -> Log.w(TAG, "unrecognised sleep value: $it")
            }
        }

        // Setting a window implies wanting it on — otherwise you configure the hours,
        // see nothing happen, and have to discover a second flag.
        i.getStringExtra(EXTRA_SLEEP_START)?.let { raw ->
            SleepSchedule.parseTime(raw)
                ?.let { prefs.sleepStart = SleepSchedule.format(it); prefs.sleepEnabled = true }
                ?: Log.w(TAG, "bad sleep_start '$raw' — expected HH:mm, ignoring")
        }
        i.getStringExtra(EXTRA_SLEEP_END)?.let { raw ->
            SleepSchedule.parseTime(raw)
                ?.let { prefs.sleepEnd = SleepSchedule.format(it); prefs.sleepEnabled = true }
                ?: Log.w(TAG, "bad sleep_end '$raw' — expected HH:mm, ignoring")
        }

        i.getStringExtra(EXTRA_COMMAND)?.lowercase()?.let {
            when (it) {
                "sleep" -> prefs.manualSleepOverride = true
                "wake" -> prefs.manualSleepOverride = false
                else -> Log.w(TAG, "unrecognised command: $it")
            }
        }

        evaluateSleepState()
    }

    // --- library ------------------------------------------------------------

    private fun loadFromDiskThenSync() {
        lifecycleScope.launch {
            library = withContext(Dispatchers.IO) { store.load() }
            applyOrientationFilter()
            applyCollagePool()
            Log.i(TAG, "loaded ${library.size} photos, ${photos.size} match orientation, " +
                "${collagePool.size} in the tile pool")

            if (collageMode()) {
                binding.tvStatus.visibility = View.GONE
                startCollage()
                restartWatchdog()
            } else if (photos.isNotEmpty()) {
                binding.tvStatus.visibility = View.GONE
                show(0)
                scheduleNext()
                restartWatchdog()
            } else {
                binding.tvStatus.setText(
                    if (albumUrls().isEmpty()) R.string.status_no_album
                    else R.string.status_first_sync
                )
                binding.tvStatus.visibility = View.VISIBLE
            }

            refreshLoop()
        }
    }

    /** Configured albums, falling back to the build-time default. */
    private fun albumUrls(): List<String> =
        prefs.albumUrls.takeIf { it.isNotEmpty() }
            ?: AlbumList.parse(BuildConfig.DEFAULT_ALBUM_URL).urls

    private suspend fun refreshLoop() {
        val urls = albumUrls()
        if (urls.isEmpty()) {
            Log.w(TAG, "no album configured — not syncing")
            return
        }
        Log.i(TAG, "syncing ${urls.size} album(s)")
        val sync = AlbumSync(store)
        val metrics = resources.displayMetrics

        while (lifecycleScope.isActive) {
            val result = sync.sync(
                albumUrls(),
                metrics.widthPixels,
                metrics.heightPixels,
                includeVideos = prefs.videoEnabled,
            ) { done, total ->
                if (photos.isEmpty()) {
                    runOnUiThread { binding.tvStatus.text = "Syncing photos…  $done / $total" }
                }
            }
            prefs.lastSyncMs = System.currentTimeMillis()

            when (result) {
                is AlbumSync.Result.Success -> {
                    val failed = result.albums.count { !it.ok }
                    prefs.lastSyncSummary = buildString {
                        append("${result.total} items, ${result.bytes / 1_048_576}MB")
                        append(" from ${result.albums.count { it.ok }}/${result.albums.size} albums")
                        if (failed > 0) append(" — $failed unreachable, photos kept")
                        if (result.degraded) append(" (DEGRADED PARSE)")
                    }
                    Log.i(TAG, "sync ok: ${prefs.lastSyncSummary}")
                    result.albums.forEach {
                        Log.i(TAG, "  ${AlbumList.shortName(it.url)}: " +
                            (it.error ?: "${it.items} items${if (it.truncated) " (truncated)" else ""}"))
                    }
                    onLibraryChanged()
                }
                is AlbumSync.Result.Failure -> {
                    prefs.lastSyncSummary = "FAILED: ${result.reason}"
                    Log.e(TAG, "sync failed: ${result.reason} — keeping existing photos")
                    if (photos.isEmpty()) {
                        binding.tvStatus.text = "Sync failed:\n${result.reason}"
                    }
                }
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun onLibraryChanged() {
        val currentId = photos.getOrNull(currentIndex)?.id
        val fresh = withContext(Dispatchers.IO) { store.load() }
        if (fresh.isEmpty()) return

        library = fresh
        applyOrientationFilter(preserveId = currentId)
        applyCollagePool()

        if (collageMode()) {
            // A running wall needs no nudge: the driver draws from collagePool every tick,
            // so new photos enter the rotation on their own. Only the first-content case
            // has to do anything.
            if (binding.tvStatus.visibility == View.VISIBLE) {
                binding.tvStatus.visibility = View.GONE
            }
            if (!collage.visible && !isAsleep && !isPaused) {
                startCollage()
                restartWatchdog()
            }
            return
        }

        if (photos.isEmpty()) return

        if (binding.tvStatus.visibility == View.VISIBLE) {
            binding.tvStatus.visibility = View.GONE
            show(currentIndex)
            scheduleNext()
            restartWatchdog()
        }
    }

    // --- collage ------------------------------------------------------------

    /**
     * Whether the grid should be driving the frame right now.
     *
     * Read from prefs on every call rather than cached, so toggling collage in settings
     * takes effect on the next onResume without any extra plumbing. The pool check is what
     * makes an all-video or empty library fall back to the single-photo path instead of
     * showing an empty grid.
     */
    private fun collageMode(): Boolean = prefs.collageEnabled && collagePool.isNotEmpty()

    private fun isPanelPortrait(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun currentTemplate(): CollageLayout.Template =
        CollageLayout.templateAt(isPanelPortrait(), templateRotation)

    private fun heroIntervalMs(): Long =
        prefs.heroIntervalMinutes.coerceAtLeast(1) * 60_000L

    /**
     * Recomputes the tile pool. Call wherever [library] or the day changes.
     *
     * Videos are excluded: a clip plays as a hero, never in a tile. Six VideoViews is
     * heavy, and one corner of the wall talking while five photographs sit still reads as
     * a fault rather than a feature.
     *
     * The weekday filter still applies, and relaxes the way PhotoSelector's does — an
     * album shot over one weekend contributes nothing Monday to Friday, and an empty wall
     * is the outcome every filter here is written to avoid.
     */
    private fun applyCollagePool() {
        val stills = library.filterNot { it.isVideo }
        collagePool = if (prefs.weekdayFilterEnabled) {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now().dayOfWeek
            stills.filter { PhotoSelector.matchesWeekday(it.captureMs, today, zone) }
                .ifEmpty { stills }
        } else {
            stills
        }
    }

    private fun curationConfig() = CollageSelector.Config(
        eraMix = prefs.curationEraMix,
        onThisDay = prefs.curationOnThisDay,
        recency = prefs.curationRecency,
    )

    /** One photo per slot for the current template, in slot order. */
    private fun selectGrid(): List<PhotoStore.StoredPhoto> = CollageSelector.fill(
        candidates = collagePool,
        slots = currentTemplate().slots,
        config = curationConfig(),
        today = LocalDate.now(),
        zone = ZoneId.systemDefault(),
        rotation = gridRotation++,
        random = Random.Default,
        isPortrait = { it.isPortrait },
        captureMs = { it.captureMs },
        addedMs = { it.addedMs },
    )

    /**
     * A replacement photo for one tile, avoiding anything already on the wall.
     *
     * CollageSelector fills a whole grid and de-duplicates inside that one call, but it
     * has no idea what the other five tiles are currently showing — so a single-slot draw
     * can collide, and a photograph appearing twice in a six-tile grid is the first thing
     * anyone would notice. [CollageRenderer.photoAt] exists for exactly this check.
     *
     * Draw a whole grid and keep this slot's pick, retrying a bounded number of times.
     * That is more work than drawing one photo, but it keeps every curation rule — era
     * stratification, the reserved recency slot, the relax cascade — in one place rather
     * than reimplementing a lesser copy here.
     */
    private fun pickForSlot(slot: Int): PhotoStore.StoredPhoto? {
        if (collagePool.isEmpty()) return null
        val template = currentTemplate()
        if (slot !in template.slots.indices) return null

        val onScreen = buildSet {
            for (i in template.slots.indices) {
                if (i != slot) collage.photoAt(i)?.id?.let { add(it) }
            }
        }

        repeat(DEDUP_ATTEMPTS) {
            val pick = selectGrid().getOrNull(slot) ?: return null
            if (pick.id !in onScreen) return pick
        }

        // Every draw collided. Fall back to any resident photo that is not on the wall,
        // preferring the slot's shape — better a photograph the curation did not choose
        // than the same one twice.
        val want = template.slots[slot].wantPortrait
        return collagePool.firstOrNull { it.id !in onScreen && it.isPortrait == want }
            ?: collagePool.firstOrNull { it.id !in onScreen }
    }

    /**
     * Shows the grid and starts the driver. Safe to call when it is already running.
     *
     * The full setup only runs when the grid is not already up, so an onResume does not
     * reload six tiles for nothing.
     */
    private fun startCollage() {
        handler.removeCallbacks(heroRunnable)
        handler.removeCallbacks(heroEndRunnable)
        heroActive = false
        slotRetries.fill(0)

        if (!collage.visible) {
            val metrics = resources.displayMetrics
            collage.applyTemplate(currentTemplate(), metrics.widthPixels, metrics.heightPixels)
            collage.setSelection(selectGrid())
            // Fade the layer in rather than cutting: the tiles underneath are whatever the
            // last grid or hero left, and a hard swap to a new template shows them for a
            // frame in their new positions.
            binding.flCollage.alpha = 0f
            collage.visible = true
            binding.flCollage.animate()
                .alpha(1f)
                .setDuration(prefs.transitionMs.toLong())
                .start()
        }
        collage.start()
        handler.postDelayed(heroRunnable, heroIntervalMs())
    }

    /**
     * Takes the grid down and cancels everything it owns.
     *
     * Authoritative and idempotent, because it is what the going-dark paths call —
     * enterSleep, onPause, onDestroy — and each can arrive mid-crossfade or mid-hero. It
     * cancels the container animation explicitly: a withEndAction does not run on a
     * cancelled animation, so a fade interrupted by sleep would otherwise leave the layer
     * half-transparent and still marked visible.
     */
    private fun stopCollage() {
        handler.removeCallbacks(heroRunnable)
        handler.removeCallbacks(heroEndRunnable)
        heroActive = false
        binding.flCollage.animate().cancel()
        binding.flCollage.alpha = 1f
        collage.stop()
        collage.visible = false
    }

    /**
     * Interrupts the grid with one full-screen photograph.
     *
     * Reuses [show] and so the existing crossfade, full-screen Ken Burns and video path —
     * a hero is exactly what the frame did before collage existed. Hero selection goes
     * through [photos], not [collagePool], because [PhotoSelector]'s orientation filter is
     * the right rule for a photo that fills the whole panel.
     */
    private fun startHero() {
        if (isAsleep || isPaused || !collageMode()) return
        if (photos.isEmpty()) {
            // Nothing the full-screen path can show — an all-portrait library on a
            // landscape panel relaxes to something, so this is close to unreachable, but
            // skipping the interlude beats blanking the wall.
            handler.postDelayed(heroRunnable, heroIntervalMs())
            return
        }
        heroActive = true
        collage.stop()

        // Load underneath first, then fade the grid off it. show() drives its own
        // crossfade on the A/B pair, so the two overlap into one transition.
        show(currentIndex)
        binding.flCollage.animate()
            .alpha(0f)
            .setDuration(prefs.transitionMs.toLong())
            .withEndAction { collage.visible = false }
            .start()

        // A clip ends the hero when it finishes playing; a still gets one normal dwell.
        if (photos[currentIndex].isVideo) return
        handler.postDelayed(heroEndRunnable, prefs.slideshowIntervalSeconds * 1000L)
    }

    /**
     * Returns to the grid, changing the template on the way.
     *
     * **This is the only place the template changes.** Applying one in view moves every
     * tile at once; doing it while the hero still covers the panel means the grid is never
     * seen reflowing. [startCollage] does the applying, which is why the rotation is
     * bumped before it and the layer is forced hidden first.
     */
    private fun endHero() {
        handler.removeCallbacks(heroEndRunnable)
        handler.removeCallbacks(advanceRunnable)
        heroActive = false
        if (isAsleep || isPaused || !collageMode()) return

        stopVideo()
        templateRotation++
        collage.visible = false
        startCollage()
    }

    /**
     * Redraws the one tile that has gone stale, rather than disturbing the grid.
     *
     * The full-screen watchdog answers a stall with advance(). For a wall that is the
     * wrong response: one tile stuck on a file that will not load would restart all six.
     * [CollageRenderer.stalestSlot] names the culprit and [CollageRenderer.refresh] offers
     * it a different photo, bounded by [MAX_SLOT_RETRIES] for the same reason
     * consecutiveFailures bounds showStill() — a corrupt library must not spin the frame
     * at full speed.
     */
    private fun checkCollageStall() {
        if (isPaused || isAsleep) return
        val oldest = collage.oldestRenderMs
        // 0 means some slot has never drawn. The grid fills one tile per tick, so a
        // freshly started wall legitimately looks like this and it is not a stall.
        if (oldest == 0L) return

        // A tile is due once per round, and a round is one tick per slot. Three rounds of
        // slack before calling it stuck, matching the full-screen path's interval * 3.
        val round = prefs.collageTileSwapMs.toLong() * currentTemplate().slots.size
        if (System.currentTimeMillis() - oldest <= round * 3) {
            slotRetries.fill(0)
            return
        }

        val slot = collage.stalestSlot
        if (slot < 0 || slot >= slotRetries.size) return
        if (slotRetries[slot] >= MAX_SLOT_RETRIES) return
        slotRetries[slot]++
        Log.w(TAG, "watchdog: tile $slot stale for ${System.currentTimeMillis() - oldest}ms, " +
            "redrawing (attempt ${slotRetries[slot]}/$MAX_SLOT_RETRIES)")
        collage.refresh(slot)
    }

    // --- sleep --------------------------------------------------------------

    private fun currentSchedule() = SleepSchedule(
        enabled = prefs.sleepEnabled,
        start = SleepSchedule.parseTime(prefs.sleepStart) ?: LocalTime.MIDNIGHT,
        end = SleepSchedule.parseTime(prefs.sleepEnd) ?: LocalTime.of(7, 0),
    )

    /**
     * Current presence, or UNAVAILABLE when the camera cannot answer.
     *
     * UNAVAILABLE is load-bearing: a closed privacy shutter, a denied permission or a
     * device without a usable camera must fall back to the schedule. Reporting those as
     * "absent" would sleep the frame with nothing able to wake it.
     */
    private fun presenceStatus(): AwakePolicy.Presence {
        if (!prefs.presenceEnabled) return AwakePolicy.Presence.UNAVAILABLE
        if (presence.status != PresenceDetector.Status.RUNNING) {
            // Self-heal. A camera lost to a system disconnect would otherwise stay dead
            // until the app restarted — and while asleep, a dead camera means the frame
            // can never wake on presence.
            presence.recoverIfStopped()
            return AwakePolicy.Presence.UNAVAILABLE
        }
        val idleMs = System.currentTimeMillis() - presence.referenceMs
        return if (idleMs < prefs.absenceTimeoutMinutes * 60_000L) {
            AwakePolicy.Presence.PRESENT
        } else {
            AwakePolicy.Presence.ABSENT
        }
    }

    /**
     * Polls rather than scheduling exact boundary alarms. Polling is immune to DST
     * shifts, manual clock changes and a device sleeping through an alarm — and with
     * presence enabled it needs to be responsive anyway, so the tick runs every few
     * seconds rather than every minute.
     */
    /**
     * Re-applies the filters when the date rolls over.
     *
     * Without this, a frame left running would keep showing Tuesday's photos on
     * Wednesday — the weekday filter is only evaluated when the library changes, and on
     * a wall-mounted device that can be days apart.
     */
    private fun checkDayRollover() {
        if (!prefs.weekdayFilterEnabled) return
        val today = LocalDate.now()
        if (today == lastFilterDay) return

        val previous = lastFilterDay
        lastFilterDay = today
        if (previous == null || library.isEmpty()) return

        Log.i(TAG, "date rolled over to ${today.dayOfWeek} — re-selecting photos")
        applyOrientationFilter(preserveId = photos.getOrNull(currentIndex)?.id)
        applyCollagePool()
        if (isAsleep) return

        if (collageMode()) {
            // The pool has changed under the grid, so replace what is up rather than
            // waiting for six ticks to cycle yesterday's photos out one at a time. No
            // template change: this is not a hero, and the grid must not be seen reflowing.
            collage.setSelection(selectGrid())
            return
        }

        if (photos.isNotEmpty()) {
            show(currentIndex)
            scheduleNext()
        }
    }

    private fun evaluateSleepState() {
        checkDayRollover()
        val scheduled = currentSchedule().isAsleepAt(LocalTime.now())

        // A manual "sleep now" lasts until the next scheduled transition, then normal
        // behaviour resumes. Otherwise a one-off command would silently persist and
        // look like a broken frame days later.
        if (lastScheduledAsleep != null && lastScheduledAsleep != scheduled &&
            prefs.manualSleepOverride != null
        ) {
            Log.i(TAG, "scheduled boundary reached — clearing manual override")
            prefs.manualSleepOverride = null
        }
        lastScheduledAsleep = scheduled

        val detected = presenceStatus()
        val shouldSleep = !AwakePolicy.shouldBeAwake(scheduled, detected, prefs.manualSleepOverride)

        if (shouldSleep != isAsleep) {
            Log.i(TAG, AwakePolicy.explain(scheduled, detected, prefs.manualSleepOverride))
            if (shouldSleep) enterSleep() else exitSleep()
        }
    }

    private fun tickIntervalMs(): Long =
        if (prefs.presenceEnabled) PRESENCE_TICK_MS else SLEEP_TICK_MS

    private fun startPresenceIfEnabled() {
        // The service starts the detector itself, after foregrounding the process —
        // order matters, because Android 9 refuses a camera connection to a background
        // process. Starting it here too would open the camera before that guarantee
        // holds.
        if (prefs.presenceEnabled) PresenceService.start(this) else PresenceService.stop(this)
    }

    private fun enterSleep() {
        isAsleep = true
        Log.i(TAG, "sleeping (${currentSchedule().describe()})")

        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(watchdogRunnable)
        // Before the view resets below: this cancels the tile animations and the hero
        // timers, so nothing is left mid-crossfade behind a dark panel.
        stopCollage()
        binding.ivPhotoA.animate().cancel()
        binding.ivPhotoB.animate().cancel()
        // Critical: a clip left running would keep playing audio into a dark room.
        stopVideo()

        binding.ivPhotoA.visibility = View.INVISIBLE
        binding.ivPhotoB.visibility = View.INVISIBLE
        binding.tvStatus.visibility = View.GONE
        binding.ivPauseOverlay.visibility = View.GONE

        // Two mechanisms, because neither is sufficient alone on Portal:
        //
        //  1. Release KEEP_SCREEN_ON so screen_off_timeout can switch the panel off.
        //     On Portal+ that timeout is 5 minutes. This ONLY works if the system
        //     screensaver is disabled — otherwise the device starts its dream (Portal's
        //     own ambient screen) instead of powering down. See tools/deploy-portal.sh.
        //
        //  2. Drive brightness to the floor immediately, so the intervening minutes
        //     show black rather than a lit screen.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }

        // Schedule a wake alarm only when the schedule is actually governing. With
        // presence running, the schedule is inert, and an alarm would light the panel at
        // 07:00 in an empty room just for the tick to sleep it again — which reads as a
        // bug. Presence-driven waking comes from the tick, which keeps running while the
        // Activity is paused; if the camera later fails, the tick sees UNAVAILABLE and
        // falls back to the schedule on its own.
        if (prefs.sleepEnabled && presenceStatus() == AwakePolicy.Presence.UNAVAILABLE) {
            WakeAlarm.schedule(this, currentSchedule().nextWake(LocalDateTime.now()))
        }
    }

    private fun exitSleep() {
        isAsleep = false
        Log.i(TAG, "waking")
        WakeAlarm.cancel(this)

        // The panel may already be off — presence can wake the frame at any time, and
        // re-adding KEEP_SCREEN_ON below only prevents a screen from sleeping, it cannot
        // turn a dark one back on. No-op when the screen is already lit.
        WakeAlarm.powerScreenOn(this)

        // Cycling the panel hands the foreground to Portal's launcher, so waking up
        // while invisible is the normal case, not an edge case: the frame would render
        // correctly into a view hierarchy nobody can see. PresenceService also does this
        // on its tick; doing it here too makes waking feel immediate rather than taking
        // up to three seconds.
        if (!isInForeground) {
            runCatching {
                startActivity(
                    Intent(this, SlideshowActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }.onFailure { Log.w(TAG, "could not bring the frame forward: ${it.message}") }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        binding.ivPhotoA.visibility = View.VISIBLE
        binding.ivPhotoB.visibility = View.VISIBLE
        if (collageMode()) {
            startCollage()
            restartWatchdog()
        } else if (photos.isNotEmpty()) {
            show(currentIndex)
            scheduleNext()
            restartWatchdog()
        }
    }

    // --- orientation --------------------------------------------------------

    /**
     * Narrows the library by orientation and, optionally, by today's day of the week.
     *
     * The cascade lives in [PhotoSelector] so the empty-match cases are unit tested.
     * Both filters can legitimately match nothing — a landscape frame against a mostly
     * portrait album, or a Monday against a trip album shot over one weekend — so
     * filters are dropped in order rather than allowed to blank the frame.
     */
    private fun applyOrientationFilter(preserveId: String? = null) {
        val wantPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val selection = PhotoSelector.select(
            library = library,
            wantPortrait = wantPortrait,
            weekdayFilterEnabled = prefs.weekdayFilterEnabled,
            today = LocalDate.now().dayOfWeek,
            zone = ZoneId.systemDefault(),
            isPortrait = { it.isPortrait },
            captureMs = { it.captureMs },
        )

        if (selection.relaxed && library.isNotEmpty()) {
            Log.w(TAG, "filters relaxed to ${selection.applied} — " +
                "${selection.items.size} of ${library.size} in rotation")
        }

        photos = selection.items.shuffled()
        lastFilterDay = LocalDate.now()
        currentIndex = photos.indexOfFirst { it.id == preserveId }.takeIf { it >= 0 } ?: 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()

        val before = photos.size
        val currentId = photos.getOrNull(currentIndex)?.id
        applyOrientationFilter(preserveId = currentId)
        applyCollagePool()
        val now = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
            "portrait" else "landscape"
        Log.i(TAG, "rotated to $now: $before -> ${photos.size} photos in rotation")

        if (isAsleep) return

        if (collageMode()) {
            // The two orientations have different template sets — CollageLayout.forPanel
            // returns 5 landscape and 4 portrait — so a counter that meant one template
            // before the rotation means an unrelated one after. templateAt uses floorMod
            // so any value is safe to index with, but carrying it across is meaningless;
            // starting the new orientation at its first template is at least predictable.
            templateRotation = 0
            collage.visible = false
            startCollage()
            return
        }

        if (photos.isNotEmpty()) {
            show(currentIndex)
            scheduleNext()
        }
    }

    // --- rendering ----------------------------------------------------------

    private fun show(index: Int) {
        if (isAsleep) return
        val photo = photos.getOrNull(index) ?: return

        if (photo.isVideo) {
            showVideo(photo)
            return
        }
        showStill(photo)
    }

    /**
     * Plays a clip to completion, then advances.
     *
     * Audio is gated on presence: sound only when someone is actually in the room to
     * hear it. A wall frame that starts talking to an empty house is the behaviour this
     * avoids — and since an empty room sleeps the frame anyway, it also means no audio
     * at night without needing a separate rule.
     */
    private fun showVideo(photo: PhotoStore.StoredPhoto) {
        // The advance timer is cancelled: the clip's own completion drives the next
        // item, so playback is never cut off mid-action.
        handler.removeCallbacks(advanceRunnable)

        val audible = prefs.videoAudioEnabled &&
            presenceStatus() == AwakePolicy.Presence.PRESENT
        val volume = if (audible) prefs.videoVolume / 100f else 0f

        binding.vvVideo.apply {
            visibility = View.VISIBLE
            setOnPreparedListener { mp ->
                mp.setVolume(volume, volume)
                mp.isLooping = false
                lastRenderedAtMs = System.currentTimeMillis()
                consecutiveFailures = 0
                // Hide the stills only once the first frame is ready, so there is no
                // black flash between the previous photo and the clip.
                binding.ivPhotoA.visibility = View.INVISIBLE
                binding.ivPhotoB.visibility = View.INVISIBLE
            }
            setOnCompletionListener { endVideoAndAdvance() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "video ${photo.id} failed ($what/$extra) — skipping")
                endVideoAndAdvance()
                true
            }
            setVideoPath(photo.file.absolutePath)
            start()
        }
        Log.i(TAG, "playing video ${photo.id}, audio=${if (audible) "on" else "muted"}")
    }

    private fun endVideoAndAdvance() {
        stopVideo()
        // A clip shown as a hero ends the interlude when it finishes, rather than handing
        // over to the single-photo advance loop that is not running in collage mode.
        if (heroActive) endHero() else advance()
    }

    private fun stopVideo() {
        if (binding.vvVideo.visibility == View.GONE) return
        runCatching { binding.vvVideo.stopPlayback() }
        binding.vvVideo.visibility = View.GONE
        binding.ivPhotoA.visibility = View.VISIBLE
        binding.ivPhotoB.visibility = View.VISIBLE
    }

    private fun showStill(photo: PhotoStore.StoredPhoto) {
        stopVideo()
        val effect = resolveTransition()
        val incoming = back
        val outgoing = front

        incoming.animate().cancel()
        prepareIncoming(incoming, effect)

        Glide.with(this)
            .load(photo.file)
            // Our own transition runs below; Glide's would fight it.
            .dontAnimate()
            // The file is already on our disk. AUTOMATIC would re-encode a second copy of
            // every photo into Glide's cache — a duplicate of the whole ~92MB library, and
            // a steady stream of flash writes on a device that runs for months. NONE
            // decodes from our own file each time instead: a little more CPU per slide,
            // which is the cheaper side of that trade here.
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    Log.w(TAG, "load failed for ${photo.id}: ${e?.message}")
                    // Bounded: a corrupt library must not spin the frame at full speed.
                    if (++consecutiveFailures < photos.size) handler.post { advance() }
                    else Log.e(TAG, "every photo failed to load — stopping")
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    consecutiveFailures = 0
                    // post() so the drawable is actually attached before we animate —
                    // Glide sets it into the view after this listener returns false.
                    incoming.post { runTransition(outgoing, incoming, effect) }
                    return false
                }
            })
            // ViewTarget is keyed on the view, so Glide clears the previous request on
            // every load. An earlier CustomTarget approach leaked one target per advance.
            .into(incoming)
    }

    private fun resolveTransition(): Transition =
        Transition.from(prefs.transition).let {
            if (it == Transition.RANDOM) Transition.randomConcrete() else it
        }

    /** Places the incoming view at its starting pose, before it becomes visible. */
    private fun prepareIncoming(v: ImageView, effect: Transition) {
        v.translationX = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        when (effect) {
            Transition.SLIDE -> {
                v.alpha = 1f
                v.translationX = resources.displayMetrics.widthPixels.toFloat()
            }
            Transition.ZOOM -> {
                v.alpha = 0f
                v.scaleX = 1.12f
                v.scaleY = 1.12f
            }
            else -> v.alpha = 0f
        }
    }

    private fun runTransition(outgoing: ImageView, incoming: ImageView, effect: Transition) {
        val duration = prefs.transitionMs.toLong()
        val width = resources.displayMetrics.widthPixels.toFloat()
        val ease = AccelerateDecelerateInterpolator()

        val settle = Runnable {
            // Park the outgoing view fully hidden and untransformed, ready for reuse.
            outgoing.alpha = 0f
            outgoing.translationX = 0f
            outgoing.scaleX = 1f
            outgoing.scaleY = 1f
            frontIsA = !frontIsA
            lastRenderedAtMs = System.currentTimeMillis()
            startKenBurns(incoming)
        }

        when (effect) {
            Transition.CUT -> {
                incoming.alpha = 1f
                settle.run()
            }
            Transition.SLIDE -> {
                incoming.animate().translationX(0f).setDuration(duration)
                    .setInterpolator(ease).start()
                outgoing.animate().translationX(-width).setDuration(duration)
                    .setInterpolator(ease).withEndAction(settle).start()
            }
            Transition.ZOOM -> {
                incoming.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(duration).setInterpolator(ease).start()
                outgoing.animate().alpha(0f).setDuration(duration)
                    .setInterpolator(ease).withEndAction(settle).start()
            }
            else -> { // CROSSFADE, and RANDOM already resolved
                incoming.animate().alpha(1f).setDuration(duration).start()
                outgoing.animate().alpha(0f).setDuration(duration)
                    .withEndAction(settle).start()
            }
        }
    }

    /**
     * Slow pan-and-zoom across the photo for the length of its dwell.
     *
     * Besides looking better than a static image, it keeps pixels moving on a panel
     * that would otherwise display near-identical frames for months.
     */
    private fun startKenBurns(v: ImageView) {
        if (!prefs.kenBurnsEnabled) return
        val dwell = prefs.slideshowIntervalSeconds * 1000L
        val drift = resources.displayMetrics.widthPixels * 0.02f
        v.animate()
            .scaleX(KEN_BURNS_SCALE)
            .scaleY(KEN_BURNS_SCALE)
            .translationX(if ((currentIndex % 2) == 0) drift else -drift)
            .setDuration(dwell)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun advance() {
        if (photos.isEmpty()) return
        stopVideo()
        currentIndex++
        if (currentIndex >= photos.size) {
            currentIndex = 0
            // Reshuffle each pass, rotating if it would repeat across the boundary.
            val last = photos.lastOrNull()?.id
            photos = photos.shuffled().let {
                if (it.firstOrNull()?.id == last && it.size > 1) it.drop(1) + it.first() else it
            }
        }
        show(currentIndex)
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advanceRunnable)
        if (isAsleep) return
        // A playing clip advances on completion, not on a timer.
        if (binding.vvVideo.visibility == View.VISIBLE) return
        // In collage mode the grid drives itself and a hero ends on its own timer, so this
        // loop must stay parked. show() and advance() are still reachable — a hero uses
        // them, and so does a failed hero load — and without this guard either would start
        // the full-screen slideshow running underneath the wall.
        if (collageMode()) return
        if (!isPaused) {
            handler.postDelayed(advanceRunnable, prefs.slideshowIntervalSeconds * 1000L)
        }
    }

    /**
     * The only place watchdogRunnable is posted — including its own self-repost.
     *
     * The runnable re-posts itself, so every bare postDelayed() starts another
     * self-sustaining loop, each checking for a stall — and forcing an advance — on its own
     * schedule. That was the bug: exitSleep() posted it, then its own
     * startActivity(REORDER_TO_FRONT) delivered onResume, which posted it again, for two
     * loops. Not forever, though: removeCallbacks(Runnable) drops *all* pending posts of a
     * runnable, and enterSleep(), onPause() and this method all call it, so a stray loop
     * lives only until the next sleep, pause, or watchdog tick.
     */
    private fun restartWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        // Remove first, then bail while asleep — the same shape as scheduleNext(). Without
        // this, a first sync landing during quiet hours re-posts the loop that enterSleep()
        // had just removed: loadFromDiskThenSync() suspends on the disk read, so its
        // restartWatchdog() runs after onCreate has already put the frame to sleep.
        if (isAsleep) return
        handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
    }

    private fun togglePause() {
        // Tapping a sleeping frame wakes it rather than showing a pause icon on a black
        // screen. The override clears itself at the next scheduled boundary, so this
        // cannot leave quiet hours permanently disabled.
        if (isAsleep) {
            Log.i(TAG, "tap while asleep — waking until the next scheduled boundary")
            prefs.manualSleepOverride = false
            evaluateSleepState()
            return
        }

        isPaused = !isPaused
        binding.ivPauseOverlay.visibility = if (isPaused) View.VISIBLE else View.GONE
        if (isPaused && binding.vvVideo.visibility == View.VISIBLE) {
            runCatching { binding.vvVideo.pause() }
            return
        }
        if (!isPaused && binding.vvVideo.visibility == View.VISIBLE) {
            runCatching { binding.vvVideo.start() }
            return
        }
        if (isPaused) {
            // Previously missing: the already-posted callback fired anyway, so tapping
            // pause advanced one more photo while showing the pause icon.
            handler.removeCallbacks(advanceRunnable)
            front.animate().cancel()
        } else if (photos.isNotEmpty()) {
            scheduleNext()
        }
    }

    // --- window -------------------------------------------------------------

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** The system clears these flags on focus change; without re-applying, months of
     *  uptime end with a permanently visible navigation bar. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        AppForeground.onActivityPaused()
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(watchdogRunnable)
        // onPause is reachable without sleeping — settings, the Portal launcher, the
        // Assistant — and the plan named only enterSleep. Missing it here would leave the
        // driver decoding tile bitmaps into an Activity nobody can see.
        stopCollage()
        // The sleep tick deliberately keeps running. Cancelling it here was the bug that
        // stopped the frame waking: the panel powering off pauses the Activity, which
        // removed the only callback still evaluating the schedule. The alarm is the real
        // safety net now, but there is no reason to stop evaluating while paused.
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        AppForeground.onActivityResumed()
        // Remove before posting: the tick is no longer cancelled in onPause, so a bare
        // post() here would stack another loop on every resume.
        handler.removeCallbacks(sleepTickRunnable)
        handler.post(sleepTickRunnable)
        // Presence may have been toggled in settings while we were away.
        startPresenceIfEnabled()
        if (!isAsleep && !isPaused) {
            // Collage may have been toggled in settings while we were away, and prefs are
            // read fresh by collageMode(), so this is also where a mode switch lands.
            if (collageMode()) {
                startCollage()
                restartWatchdog()
            } else if (photos.isNotEmpty()) {
                scheduleNext()
                restartWatchdog()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isInForeground = false
        handler.removeCallbacksAndMessages(null)
        // removeCallbacksAndMessages already dropped the driver and the hero timers; this
        // is for the view state — cancelling animations still running on the tiles, which
        // hold Glide targets.
        stopCollage()
        stopVideo()
        // Deliberately NOT presence.stop(). The detector belongs to PresenceService and
        // has to keep running after this Activity dies — stopping it here is what left
        // the frame unable to notice anyone once Portal's launcher took the foreground.
    }
}
