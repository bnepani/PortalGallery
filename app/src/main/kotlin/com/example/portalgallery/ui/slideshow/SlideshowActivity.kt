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
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.portalgallery.BuildConfig
import com.example.portalgallery.R
import com.example.portalgallery.data.presence.PresenceDetector
import com.example.portalgallery.data.presence.PresenceService
import com.example.portalgallery.data.schedule.AwakePolicy
import com.example.portalgallery.data.schedule.SleepSchedule
import com.example.portalgallery.data.schedule.WakeAlarm
import com.example.portalgallery.data.store.AlbumSync
import com.example.portalgallery.data.store.PhotoStore
import com.example.portalgallery.databinding.ActivitySlideshowBinding
import com.example.portalgallery.prefs.AppPreferences
import com.example.portalgallery.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime

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
        const val EXTRA_SLEEP = "sleep"
        const val EXTRA_SLEEP_START = "sleep_start"
        const val EXTRA_SLEEP_END = "sleep_end"
        const val EXTRA_COMMAND = "command"

        private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val SLEEP_TICK_MS = 60_000L

        /** Faster tick when presence drives the frame — waiting up to a minute for the
         *  photos to come back after walking into the room would feel broken. */
        private const val PRESENCE_TICK_MS = 3_000L
        private const val HINT_VISIBLE_MS = 8_000L

        /** How far Ken Burns zooms over a full dwell. Subtle on purpose. */
        private const val KEN_BURNS_SCALE = 1.08f
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

    /** Which ImageView currently holds the visible photo. Flips on every advance. */
    private var frontIsA = true

    private val handler = Handler(Looper.getMainLooper())
    private val advanceRunnable = Runnable { advance() }

    private val front: ImageView get() = if (frontIsA) binding.ivPhotoA else binding.ivPhotoB
    private val back: ImageView get() = if (frontIsA) binding.ivPhotoB else binding.ivPhotoA

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val interval = prefs.slideshowIntervalSeconds * 1000L
            val stalled = !isPaused && !isAsleep &&
                photos.isNotEmpty() &&
                lastRenderedAtMs > 0 &&
                System.currentTimeMillis() - lastRenderedAtMs > interval * 3
            if (stalled) {
                Log.w(TAG, "watchdog: no render in ${interval * 3}ms, forcing advance")
                advance()
            }
            handler.postDelayed(this, interval)
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
        presence = PresenceDetector(applicationContext)
        startPresenceIfEnabled()

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

        i.getStringExtra(EXTRA_ALBUM_URL)?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "album url set via intent")
            prefs.albumUrl = it
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
            Log.i(TAG, "loaded ${library.size} photos, ${photos.size} match orientation")

            if (photos.isNotEmpty()) {
                binding.tvStatus.visibility = View.GONE
                show(0)
                scheduleNext()
                handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
            } else {
                binding.tvStatus.setText(
                    if (albumUrl().isNullOrBlank()) R.string.status_no_album
                    else R.string.status_first_sync
                )
                binding.tvStatus.visibility = View.VISIBLE
            }

            refreshLoop()
        }
    }

    private fun albumUrl(): String? =
        prefs.albumUrl?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ALBUM_URL.takeIf { it.isNotBlank() }

    private suspend fun refreshLoop() {
        val url = albumUrl()
        if (url.isNullOrBlank()) {
            Log.w(TAG, "no album url configured — not syncing")
            return
        }
        val sync = AlbumSync(store)
        val metrics = resources.displayMetrics

        while (lifecycleScope.isActive) {
            val result = sync.sync(
                url,
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
                    prefs.lastSyncSummary = "${result.total} photos, ${result.bytes / 1_048_576}MB" +
                        if (result.degraded) " (DEGRADED PARSE)" else ""
                    Log.i(TAG, "sync ok: ${prefs.lastSyncSummary}, complete=${result.isCompleteAlbum}")
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
        if (photos.isEmpty()) return

        if (binding.tvStatus.visibility == View.VISIBLE) {
            binding.tvStatus.visibility = View.GONE
            show(currentIndex)
            scheduleNext()
            handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
        }
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
    private fun evaluateSleepState() {
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
        if (prefs.presenceEnabled) {
            // Order matters: the process must be foreground before the camera is opened,
            // or Android 9 refuses the connection outright.
            PresenceService.start(this)
            presence.start()
        } else {
            presence.stop()
            PresenceService.stop(this)
        }
    }

    private fun enterSleep() {
        isAsleep = true
        Log.i(TAG, "sleeping (${currentSchedule().describe()})")

        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(watchdogRunnable)
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        binding.ivPhotoA.visibility = View.VISIBLE
        binding.ivPhotoB.visibility = View.VISIBLE
        if (photos.isNotEmpty()) {
            show(currentIndex)
            scheduleNext()
            handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
        }
    }

    // --- orientation --------------------------------------------------------

    /**
     * Narrows the library to photos matching the frame's physical orientation.
     *
     * The one exception is an empty match: showing an ill-fitting photo is bad, showing
     * nothing violates the invariant the whole design upholds, so the filter is
     * abandoned rather than the frame going dark.
     */
    private fun applyOrientationFilter(preserveId: String? = null) {
        val wantPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val matching = library.filter { it.isPortrait == wantPortrait }

        photos = if (matching.isNotEmpty()) {
            matching.shuffled()
        } else {
            if (library.isNotEmpty()) {
                Log.w(TAG, "no ${if (wantPortrait) "portrait" else "landscape"} photos " +
                    "in a library of ${library.size} — showing all rather than blanking")
            }
            library.shuffled()
        }
        currentIndex = photos.indexOfFirst { it.id == preserveId }.takeIf { it >= 0 } ?: 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()

        val before = photos.size
        val currentId = photos.getOrNull(currentIndex)?.id
        applyOrientationFilter(preserveId = currentId)
        val now = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
            "portrait" else "landscape"
        Log.i(TAG, "rotated to $now: $before -> ${photos.size} photos in rotation")

        if (photos.isNotEmpty() && !isAsleep) {
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
        advance()
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
        if (!isPaused) {
            handler.postDelayed(advanceRunnable, prefs.slideshowIntervalSeconds * 1000L)
        }
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
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(watchdogRunnable)
        // The sleep tick deliberately keeps running. Cancelling it here was the bug that
        // stopped the frame waking: the panel powering off pauses the Activity, which
        // removed the only callback still evaluating the schedule. The alarm is the real
        // safety net now, but there is no reason to stop evaluating while paused.
    }

    override fun onResume() {
        super.onResume()
        // Remove before posting: the tick is no longer cancelled in onPause, so a bare
        // post() here would stack another loop on every resume.
        handler.removeCallbacks(sleepTickRunnable)
        handler.post(sleepTickRunnable)
        // Presence may have been toggled in settings while we were away.
        startPresenceIfEnabled()
        if (!isAsleep && !isPaused && photos.isNotEmpty()) {
            scheduleNext()
            handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopVideo()
        // Release the camera. It is bound to the detector's own lifecycle, not this
        // Activity's, so nothing else would ever let it go.
        presence.stop()
    }
}
