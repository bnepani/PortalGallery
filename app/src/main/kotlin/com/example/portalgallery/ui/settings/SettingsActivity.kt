package com.example.portalgallery.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.portalgallery.BuildConfig
import com.example.portalgallery.R
import com.example.portalgallery.data.album.AlbumList
import com.example.portalgallery.data.schedule.PhotoSelector
import com.example.portalgallery.data.schedule.SleepSchedule
import com.example.portalgallery.data.store.PhotoStore
import com.example.portalgallery.databinding.ActivitySettingsBinding
import com.example.portalgallery.prefs.AppPreferences
import com.example.portalgallery.ui.AppForeground
import com.example.portalgallery.ui.slideshow.Transition
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.Date

/**
 * On-device settings, reached by long-pressing the frame.
 *
 * Everything here writes straight to [AppPreferences]; the slideshow reads those values
 * live on each advance, so changes take effect on the next photo without any callback
 * plumbing or restart.
 *
 * The album URL is shown but not editable — typing a share link on a wall-mounted
 * touchscreen is unpleasant and a typo silently breaks the frame. Changing it stays an
 * adb operation.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPreferences(this)

        buildTransitionOptions()
        bindSliders()
        bindKenBurns()
        bindQuietHours()
        bindVideo()
        bindWeekday()
        bindCollage()
        bindPresence()
        showAlbumStatus()

        binding.btnClose.setOnClickListener { finish() }
    }

    /** Built from the enum so adding a transition needs no layout change. */
    private fun buildTransitionOptions() {
        val current = Transition.from(prefs.transition)
        Transition.values().forEach { option ->
            val button = RadioButton(this).apply {
                id = option.ordinal + 1000
                text = "${option.label} — ${option.description}"
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt())
                minHeight = 88
                isChecked = option == current
                setPadding(24, 16, 16, 16)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            binding.rgTransition.addView(button)
        }
        binding.rgTransition.setOnCheckedChangeListener { _, checkedId ->
            Transition.values().getOrNull(checkedId - 1000)?.let {
                prefs.transition = it.name
                updateDurationLabel()
            }
        }
    }

    private fun bindSliders() {
        // Material's Slider throws unless the value lands exactly on a step, so snap
        // rather than trusting whatever is stored.
        val steppedMs = (((prefs.transitionMs.coerceIn(200, 3000) - 200) / 100) * 100) + 200
        binding.sliderDuration.value = steppedMs.toFloat()
        binding.sliderDuration.addOnChangeListener { _, value, _ ->
            prefs.transitionMs = value.toInt()
            updateDurationLabel()
        }

        binding.sliderInterval.value =
            prefs.slideshowIntervalSeconds.toFloat().coerceIn(3f, 120f)
        binding.sliderInterval.addOnChangeListener { _, value, _ ->
            prefs.slideshowIntervalSeconds = value.toInt()
            updateIntervalLabel()
        }

        updateDurationLabel()
        updateIntervalLabel()
    }

    private fun updateDurationLabel() {
        val instant = Transition.from(prefs.transition) == Transition.CUT
        binding.tvDurationLabel.text =
            if (instant) getString(R.string.settings_duration) + " — n/a for Cut"
            else getString(R.string.settings_duration) + "  ${prefs.transitionMs} ms"
    }

    private fun updateIntervalLabel() {
        binding.tvIntervalLabel.text =
            getString(R.string.settings_interval) + "  ${prefs.slideshowIntervalSeconds}s"
    }

    private fun bindKenBurns() {
        binding.swKenBurns.isChecked = prefs.kenBurnsEnabled
        binding.swKenBurns.setOnCheckedChangeListener { _, checked ->
            prefs.kenBurnsEnabled = checked
        }
    }

    private fun bindQuietHours() {
        binding.swSleep.isChecked = prefs.sleepEnabled
        binding.swSleep.setOnCheckedChangeListener { _, checked ->
            prefs.sleepEnabled = checked
        }

        refreshSleepButtons()

        binding.btnSleepStart.setOnClickListener {
            pickTime(prefs.sleepStart) { prefs.sleepStart = it; refreshSleepButtons() }
        }
        binding.btnSleepEnd.setOnClickListener {
            pickTime(prefs.sleepEnd) { prefs.sleepEnd = it; refreshSleepButtons() }
        }

        binding.btnSleepNow.setOnClickListener {
            // Mirrors the adb `command sleep` path. Clears itself at the next scheduled
            // boundary, so it cannot leave the frame dark indefinitely.
            prefs.manualSleepOverride = true
            finish()
        }
    }

    private fun refreshSleepButtons() {
        binding.btnSleepStart.text = "Sleep at  ${prefs.sleepStart}"
        binding.btnSleepEnd.text = "Wake at  ${prefs.sleepEnd}"
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val time = SleepSchedule.parseTime(current) ?: LocalTime.MIDNIGHT
        TimePickerDialog(
            this,
            { _, hour, minute ->
                onPicked(SleepSchedule.format(LocalTime.of(hour, minute)))
            },
            time.hour,
            time.minute,
            true, // 24-hour, matching the HH:mm the preference stores
        ).show()
    }

    private fun bindVideo() {
        binding.swVideo.isChecked = prefs.videoEnabled
        binding.swVideo.setOnCheckedChangeListener { _, checked ->
            prefs.videoEnabled = checked
            updateVideoExplain()
        }

        binding.swVideoAudio.isChecked = prefs.videoAudioEnabled
        binding.swVideoAudio.setOnCheckedChangeListener { _, checked ->
            prefs.videoAudioEnabled = checked
            updateVideoExplain()
        }

        // Snap to a step; Material's Slider throws on an off-step value.
        binding.sliderVolume.value = ((prefs.videoVolume.coerceIn(0, 100) / 5) * 5).toFloat()
        binding.sliderVolume.addOnChangeListener { _, value, _ ->
            prefs.videoVolume = value.toInt()
            updateVolumeLabel()
        }

        updateVolumeLabel()
        updateVideoExplain()
    }

    private fun updateVolumeLabel() {
        binding.tvVolumeLabel.text = getString(R.string.settings_volume) + "  ${prefs.videoVolume}%"
    }

    /**
     * Audio depends on presence, so saying "sound on" while presence is off would be a
     * lie — the frame would stay silent and nobody would know why.
     */
    private fun updateVideoExplain() {
        val base = getString(R.string.settings_video_explain)
        binding.tvVideoExplain.text = when {
            !prefs.videoEnabled ->
                "$base\n\nCurrently off — videos show as a still frame."
            prefs.videoAudioEnabled && !prefs.presenceEnabled ->
                "$base\n\nSound needs presence detection, which is off — clips will " +
                    "play silently until you enable it below."
            prefs.videoAudioEnabled ->
                "$base\n\nSound plays only while someone is in view."
            else ->
                "$base\n\nSound is off — clips play silently."
        }
    }

    private fun bindWeekday() {
        binding.swWeekday.isChecked = prefs.weekdayFilterEnabled
        binding.swWeekday.setOnCheckedChangeListener { _, checked ->
            prefs.weekdayFilterEnabled = checked
            updateWeekdayStatus()
        }
        updateWeekdayStatus()
    }

    /**
     * Shows what enabling this actually costs today, because the answer varies wildly:
     * a library spread over years loses roughly six sevenths of itself, and a trip album
     * shot over one weekend contributes nothing at all on a weekday.
     */
    private fun updateWeekdayStatus() {
        val today = LocalDate.now().dayOfWeek
        val dayName = today.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val library = PhotoStore(this).load()
        val matching = PhotoSelector.countForToday(
            library, today, ZoneId.systemDefault()
        ) { it.captureMs }

        val undated = library.count { it.captureMs <= 0L }

        binding.tvWeekdayStatus.text = buildString {
            append("Today is $dayName — $matching of ${library.size} items were taken on a $dayName.")
            if (undated > 0) {
                append("\n\n$undated items have no capture date yet and always show; ")
                append("the next sync fills those in.")
            }
            if (prefs.weekdayFilterEnabled && matching == 0 && library.isNotEmpty()) {
                append("\n\nNothing matches today, so the frame is showing everything ")
                append("rather than going blank.")
            }
        }
    }

    private fun bindCollage() {
        binding.swCollage.isChecked = prefs.collageEnabled
        binding.swCollage.setOnCheckedChangeListener { _, checked ->
            prefs.collageEnabled = checked
            refreshCollageEnabled()
        }

        // Stored in ms, offered in seconds — a slider in milliseconds would need 14,000
        // steps to cover the same range.
        binding.sliderCollageSwap.value =
            (prefs.collageTileSwapMs / 1000f).coerceIn(1f, 15f).let { Math.round(it * 2) / 2f }
        binding.sliderCollageSwap.addOnChangeListener { _, value, _ ->
            prefs.collageTileSwapMs = (value * 1000).toInt()
            updateCollageLabels()
        }

        binding.sliderHero.value = prefs.heroIntervalMinutes.toFloat().coerceIn(1f, 30f)
        binding.sliderHero.addOnChangeListener { _, value, _ ->
            prefs.heroIntervalMinutes = value.toInt()
            updateCollageLabels()
        }

        binding.swEraMix.isChecked = prefs.curationEraMix
        binding.swEraMix.setOnCheckedChangeListener { _, checked ->
            prefs.curationEraMix = checked
            updateCurationStatus()
        }
        binding.swOnThisDay.isChecked = prefs.curationOnThisDay
        binding.swOnThisDay.setOnCheckedChangeListener { _, checked ->
            prefs.curationOnThisDay = checked
            updateCurationStatus()
        }
        binding.swRecency.isChecked = prefs.curationRecency
        binding.swRecency.setOnCheckedChangeListener { _, checked ->
            prefs.curationRecency = checked
            updateCurationStatus()
        }

        updateCollageLabels()
        updateCollageStatus()
        updateCurationStatus()
        refreshCollageEnabled()
    }

    private fun updateCollageLabels() {
        val swapSeconds = prefs.collageTileSwapMs / 1000f
        binding.tvCollageSwapLabel.text =
            getString(R.string.settings_collage_swap) + "  ${"%.1f".format(swapSeconds)}s"
        val m = prefs.heroIntervalMinutes
        binding.tvHeroLabel.text = getString(R.string.settings_hero) +
            "  $m ${if (m == 1) "minute" else "minutes"}"
    }

    /**
     * The curation rows only mean anything while the grid is running, so grey them out
     * rather than leaving three switches that silently do nothing.
     */
    private fun refreshCollageEnabled() {
        val on = prefs.collageEnabled
        listOf(
            binding.swEraMix, binding.swOnThisDay, binding.swRecency,
            binding.sliderCollageSwap, binding.sliderHero,
        ).forEach {
            it.isEnabled = on
            it.alpha = if (on) 1f else 0.4f
        }
        updateCollageStatus()
    }

    /**
     * Says what the grid can actually draw from, because the answer is not obvious and it
     * is the whole point of the feature: the full-screen path shows only photos matching
     * the panel's orientation, and collage slots use both.
     */
    private fun updateCollageStatus() {
        val library = PhotoStore(this).load()
        val stills = library.filterNot { it.isVideo }
        val portrait = stills.count { it.isPortrait }
        val landscape = stills.size - portrait

        binding.tvCollageStatus.text = if (!prefs.collageEnabled) {
            "Off — one photo at a time, filling the screen."
        } else buildString {
            append("On — up to 6 photos at once, one changing at a time.\n\n")
            append("${stills.size} photos available: $portrait portrait, $landscape landscape. ")
            append("A single full-screen photo can only use one of those two; collage slots ")
            append("are shaped for both, so all ${stills.size} stay in rotation.")
            if (library.size > stills.size) {
                append("\n\n${library.size - stills.size} videos play as full-screen ")
                append("interludes, never in a tile.")
            }
        }
    }

    private fun updateCurationStatus() {
        binding.tvCurationStatus.text = buildString {
            if (!prefs.curationEraMix && !prefs.curationOnThisDay && !prefs.curationRecency) {
                append("All off — photos are drawn at random.")
                return@buildString
            }
            if (prefs.curationEraMix) {
                append("Each grid draws from several different years rather than one day. ")
            }
            if (prefs.curationOnThisDay) {
                append("Photos taken on today's date in past years are favoured; if none ")
                append("match, the grid fills normally rather than emptying. ")
            }
            if (prefs.curationRecency) {
                append("One slot is held for photos added in the last few weeks, so new ")
                append("arrivals show up within minutes instead of waiting their turn.")
            }
        }
    }

    private fun bindPresence() {
        binding.swPresence.isChecked = prefs.presenceEnabled
        binding.swPresence.setOnCheckedChangeListener { _, checked ->
            prefs.presenceEnabled = checked
            // The slideshow starts or stops the camera in onResume, so nothing to do
            // here beyond recording the choice.
            updatePresenceStatus()
        }

        binding.sliderAbsence.value =
            prefs.absenceTimeoutMinutes.toFloat().coerceIn(1f, 60f)
        binding.sliderAbsence.addOnChangeListener { _, value, _ ->
            prefs.absenceTimeoutMinutes = value.toInt()
            updateAbsenceLabel()
        }

        updateAbsenceLabel()
        updatePresenceStatus()
    }

    private fun updateAbsenceLabel() {
        val m = prefs.absenceTimeoutMinutes
        binding.tvAbsenceLabel.text =
            getString(R.string.settings_absence) + "  $m ${if (m == 1) "minute" else "minutes"}"
    }

    private fun updatePresenceStatus() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        binding.tvPresenceStatus.text = when {
            !prefs.presenceEnabled ->
                "Off — the quiet-hours schedule controls the frame."
            !hasPermission ->
                "Camera permission not granted. Falling back to the schedule.\n" +
                    "adb shell pm grant com.example.portalgallery android.permission.CAMERA"
            else ->
                "On — the schedule is used only if the camera cannot see " +
                    "(shutter closed, or camera unavailable)."
        }
    }

    private fun showAlbumStatus() {
        val urls = prefs.albumUrls.takeIf { it.isNotEmpty() }
            ?: AlbumList.parse(BuildConfig.DEFAULT_ALBUM_URL).urls
        val url = if (urls.isEmpty()) "(none configured)"
        else urls.mapIndexed { i, u -> "${i + 1}. $u" }.joinToString("\n")

        val onDisk = PhotoStore(this).load()
        val portrait = onDisk.count { it.isPortrait }
        val videos = onDisk.count { it.isVideo }
        val bytes = onDisk.sumOf { it.file.length() }

        val synced = prefs.lastSyncMs.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        } ?: "never"

        binding.tvAlbumStatus.text = buildString {
            appendLine(url)
            appendLine()
            appendLine("${onDisk.size} items on disk — ${onDisk.size - videos} photos, $videos videos")
            appendLine("$portrait portrait / ${onDisk.size - portrait} landscape · ${bytes / 1_048_576} MB")
            appendLine("Last sync: $synced")
            prefs.lastSyncSummary?.let { appendLine(it) }
            appendLine()
            append("To change the album, use adb — see README.")
        }
    }

    /** Re-read on return in case the slideshow changed something (e.g. wake-on-tap). */
    override fun onResume() {
        super.onResume()
        // Counts as app-visible, so PresenceService does not relaunch the slideshow
        // over the top of the settings screen while someone stands at the frame.
        AppForeground.onActivityResumed()
        binding.swSleep.isChecked = prefs.sleepEnabled
        refreshSleepButtons()
    }

    override fun onPause() {
        super.onPause()
        AppForeground.onActivityPaused()
    }
}
