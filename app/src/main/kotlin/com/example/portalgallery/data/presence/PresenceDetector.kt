package com.example.portalgallery.data.presence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Two-stage presence detection: cheap motion continuously, TFLite person detection to
 * confirm.
 *
 * Motion alone would sleep on someone sitting still watching photos, and trip on pets
 * and curtains. Running a detector on every frame would burn a neural net forever on a
 * wall-mounted device. Confirming only when motion fires gives most of the accuracy at
 * close to motion-level cost.
 *
 * **Why TFLite and not ML Kit.** ML Kit's "bundled model" face-detection artifact was
 * used first, chosen specifically because bundling should avoid Google Play Services.
 * Its POM declares hard dependencies on play-services-base, play-services-basement,
 * play-services-tasks and firebase-components. Portal ships no GMS at all, so that
 * would fail at class-load — and it cost ~39MB, roughly 85% of the APK. TFLite has zero
 * GMS references and a 4.5MB model, and is what Meta's Portal guidance recommends.
 *
 * **This deliberately does NOT bind to the Activity's lifecycle.** The panel powering
 * down pauses the Activity, and a lifecycle-bound camera would stop with it — leaving
 * nothing able to notice someone entering the room, so the frame could never wake. See
 * also [PresenceService], which keeps the process foreground so Android 9 does not
 * revoke camera access outright.
 *
 * No frame is written to disk or transmitted. The only output is a timestamp.
 */
class PresenceDetector(private val context: Context) {

    companion object {
        private const val TAG = "PortalGallery"
        private const val MODEL = "person_detect.tflite"

        /** COCO label. EfficientDet-Lite0 is trained on 90 classes; we want one. */
        private const val PERSON_LABEL = "person"

        /** Analyse at most one frame this often. Someone entering a room is not a 30fps
         *  event, and continuous inference on a wall device is heat and power. */
        private const val SAMPLE_INTERVAL_MS = 1_000L

        /** Fraction of sampled pixels that must change to count as motion. */
        private const val MOTION_THRESHOLD = 0.02f

        /** Per-pixel delta that counts as "changed". Above sensor noise. */
        private const val PIXEL_DELTA = 24

        /** Sample every Nth byte; full-resolution diffing is wasteful. */
        private const val STRIDE = 16

        /** Confidence floor for a person detection. */
        private const val SCORE_THRESHOLD = 0.4f

        /** A confirmed person stays "present" this long without re-confirmation, so
         *  someone sitting still is not repeatedly re-verified. */
        private const val PERSON_GRACE_MS = 20_000L

        private const val RECOVERY_INTERVAL_MS = 30_000L
    }

    enum class Status { RUNNING, UNAVAILABLE }

    @Volatile
    var status: Status = Status.UNAVAILABLE
        private set

    @Volatile
    var lastPresenceMs: Long = 0L
        private set

    /**
     * When detection began. The reference point before anyone has been seen, so a
     * freshly started frame does not immediately count as an empty room and sleep on
     * whoever just walked up to it.
     */
    @Volatile
    var startedAtMs: Long = 0L
        private set

    @Volatile
    var lastReason: String = "not started"
        private set

    val referenceMs: Long get() = maxOf(lastPresenceMs, startedAtMs)

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private var provider: ProcessCameraProvider? = null
    private var previousFrame: ByteArray? = null
    private var lastSampleMs = 0L
    private var lastPersonMs = 0L

    @Volatile
    private var lastRecoveryAttemptMs = 0L

    /**
     * Loaded lazily and defensively. A model that fails to load must degrade to
     * motion-only, never take the app down — and the catch is `Throwable` because a
     * missing native library surfaces as `UnsatisfiedLinkError`, an Error rather than
     * an Exception.
     */
    private val detector: ObjectDetector? by lazy {
        try {
            ObjectDetector.createFromFileAndOptions(
                context,
                MODEL,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                    .setMaxResults(5)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .build(),
            ).also { Log.i(TAG, "TFLite person detector loaded") }
        } catch (t: Throwable) {
            Log.w(TAG, "person detector unavailable (${t.javaClass.simpleName}: ${t.message}) " +
                "— falling back to motion-only presence")
            null
        }
    }

    /** Self-driven so the camera survives the Activity being paused by screen-off. */
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    fun start() {
        if (running.getAndSet(true)) return
        startedAtMs = System.currentTimeMillis()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("camera permission not granted — run: adb shell pm grant " +
                "com.example.portalgallery android.permission.CAMERA")
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { bind(future.get()) }
                .onFailure { fail("camera unavailable: ${it.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Tries every camera the device reports, in turn, until one binds.
     *
     * Necessary on Portal specifically: it exposes two cameras, both front-facing, and
     * Portal's own `com.facebook.portal.aiservice` holds one open more or less
     * permanently at high priority. A fixed selector is a coin flip between the free
     * camera and the occupied one.
     */
    private fun bind(cameraProvider: ProcessCameraProvider) {
        provider = cameraProvider

        val cameras = cameraProvider.availableCameraInfos
        if (cameras.isEmpty()) {
            fail("no cameras reported by CameraX")
            return
        }

        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

        val ordered = cameras.sortedByDescending { info ->
            runCatching {
                CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(info)).isNotEmpty()
            }.getOrDefault(false)
        }

        for ((index, info) in ordered.withIndex()) {
            val analysis = ImageAnalysis.Builder()
                // RGBA rather than YUV: it makes both stages simple — motion diffs the
                // bytes directly, and toBitmap() feeds the detector with no colour
                // conversion of our own.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // Small on purpose. The detector downsamples to 320x320 anyway, and a
                // smaller stream is less memory, less heat, and faster diffing.
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, ::analyse) }

            val attempt = runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, info.cameraSelector, analysis)
            }

            if (attempt.isSuccess) {
                status = Status.RUNNING
                lastReason = "watching (camera ${index + 1} of ${ordered.size})"
                Log.i(TAG, "presence detection started on camera ${index + 1}/${ordered.size}")
                return
            }
            Log.w(TAG, "camera ${index + 1} would not bind: ${attempt.exceptionOrNull()?.message}")
        }

        fail("no camera could be bound — all ${ordered.size} are in use " +
            "(Portal's own camera service holds one open)")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching {
            provider?.unbindAll()
            lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        }
        status = Status.UNAVAILABLE
        lastReason = "stopped"
        previousFrame = null
        Log.i(TAG, "presence detection stopped")
    }

    private fun fail(reason: String) {
        status = Status.UNAVAILABLE
        lastReason = reason
        running.set(false)
        // Not an error the frame should die on — AwakePolicy falls back to the schedule.
        Log.w(TAG, "presence unavailable: $reason")
    }

    /**
     * Re-opens the camera if it was lost — a system disconnect would otherwise leave
     * detection dead until the app restarted, and while asleep a dead camera means the
     * frame can never wake on presence.
     */
    fun recoverIfStopped() {
        if (status != Status.UNAVAILABLE || running.get()) return
        val now = System.currentTimeMillis()
        // Throttled: a permanently unavailable camera would otherwise retry every tick.
        if (now - lastRecoveryAttemptMs < RECOVERY_INTERVAL_MS) return
        lastRecoveryAttemptMs = now
        Log.i(TAG, "attempting to recover presence detection ($lastReason)")
        start()
    }

    /**
     * Unlike the previous ML Kit implementation, inference here is **synchronous** on
     * the analysis thread. That removes the whole class of bug around handing an
     * ImageProxy to an async callback and closing it out from under the detector — the
     * proxy is simply closed when this returns.
     */
    private fun analyse(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now

            // A recently confirmed person keeps presence alive without re-running the
            // detector, so someone sitting still is not repeatedly re-verified.
            if (now - lastPersonMs < PERSON_GRACE_MS) {
                lastPresenceMs = now
                return
            }

            if (!hasMotion(proxy)) return

            // Motion counts as presence on its own. This is what keeps the feature
            // working if the model cannot load — without it, a failed detector would
            // mean nothing is ever detected and the frame never wakes.
            lastPresenceMs = now

            val model = detector
            if (model == null) {
                lastReason = "motion (person detection unavailable)"
                return
            }

            val bitmap = runCatching { proxy.toBitmap() }.getOrNull() ?: return
            val results = model.detect(TensorImage.fromBitmap(bitmap))
            val people = results.count { detection ->
                detection.categories.any {
                    it.label.equals(PERSON_LABEL, ignoreCase = true) &&
                        it.score >= SCORE_THRESHOLD
                }
            }

            if (people > 0) {
                lastPersonMs = System.currentTimeMillis()
                lastPresenceMs = lastPersonMs
                lastReason = "$people person(s) in view"
            } else {
                lastReason = "motion but no person"
            }
        } catch (t: Throwable) {
            // Throwable, not Exception: a missing native library arrives as
            // UnsatisfiedLinkError. A frame analysis failure must never take the app down.
            Log.w(TAG, "frame analysis failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            proxy.close()
        }
    }

    /**
     * Frame differencing on a strided sample of the RGBA plane. Cheap enough to run on
     * every sampled frame; its only job is to decide whether inference is worth doing.
     */
    private fun hasMotion(proxy: ImageProxy): Boolean {
        val plane = proxy.planes.firstOrNull() ?: return false
        val buffer = plane.buffer
        buffer.rewind()

        val size = buffer.remaining()
        val sampled = ByteArray((size + STRIDE - 1) / STRIDE)
        var i = 0
        var pos = 0
        while (pos < size) {
            sampled[i++] = buffer.get(pos)
            pos += STRIDE
        }

        val prev = previousFrame
        previousFrame = sampled
        if (prev == null || prev.size != sampled.size) return false

        var changed = 0
        for (n in sampled.indices) {
            if (abs((sampled[n].toInt() and 0xFF) - (prev[n].toInt() and 0xFF)) > PIXEL_DELTA) {
                changed++
            }
        }
        return changed.toFloat() / sampled.size > MOTION_THRESHOLD
    }
}
