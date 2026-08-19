package com.example.portalgallery.data.presence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Two-stage presence detection: cheap motion continuously, face confirmation on demand.
 *
 * Motion alone would sleep on someone sitting still watching photos, and trip on pets
 * and curtains. Face detection alone would run a neural net on every frame forever.
 * Running the detector only when motion fires gives face-level accuracy at close to
 * motion-level cost.
 *
 * **This deliberately does NOT bind to the Activity's lifecycle.** The panel powering
 * down pauses the Activity, and a lifecycle-bound camera would stop with it — leaving
 * nothing able to notice someone entering the room, so the frame could never wake. That
 * is the same trap that broke the sleep schedule. Instead this owns a [LifecycleRegistry]
 * it drives itself, tied to [start]/[stop] rather than to any UI.
 *
 * No frame is ever written to disk or transmitted. The only thing leaving this class is
 * a timestamp.
 */
class PresenceDetector(private val context: Context) {

    companion object {
        private const val TAG = "PortalGallery"

        /** Analyse at most one frame this often. A person entering a room is not a
         *  30fps event, and continuous inference on a wall device is heat and power. */
        private const val SAMPLE_INTERVAL_MS = 1_000L

        /** Fraction of sampled pixels that must change to count as motion. */
        private const val MOTION_THRESHOLD = 0.02f

        /** Per-pixel luminance delta that counts as "changed". Above sensor noise. */
        private const val PIXEL_DELTA = 24

        /** Sample every Nth pixel of the Y plane; full-resolution diffing is wasteful. */
        private const val STRIDE = 8

        /** Faces stay "seen" this long without re-confirmation, so a head turn or a
         *  brief occlusion does not immediately read as an empty room. */
        private const val FACE_GRACE_MS = 20_000L

        /** How often to retry a camera that went away. */
        private const val RECOVERY_INTERVAL_MS = 30_000L
    }

    enum class Status { RUNNING, UNAVAILABLE }

    @Volatile
    var status: Status = Status.UNAVAILABLE
        private set

    /** Wall-clock of the last confirmed person. 0 if never. */
    @Volatile
    var lastPresenceMs: Long = 0L
        private set

    /**
     * When detection began. Used as the reference point before anyone has been seen, so
     * a freshly started frame does not immediately count as an empty room and sleep on
     * whoever just walked up to it.
     */
    @Volatile
    var startedAtMs: Long = 0L
        private set

    /** Most recent evidence of presence, or of having only just started looking. */
    val referenceMs: Long get() = maxOf(lastPresenceMs, startedAtMs)

    @Volatile
    var lastReason: String = "not started"
        private set

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private var provider: ProcessCameraProvider? = null
    private var previousLuma: ByteArray? = null
    private var lastSampleMs = 0L
    private var lastFaceMs = 0L
    private val faceInFlight = AtomicBoolean(false)

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // FAST over ACCURATE: we need "is a face there", not landmarks. Also
                // materially cheaper, which matters when this runs indefinitely.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build()
        )
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
     * Necessary on Portal specifically. It exposes two cameras, both front-facing, and
     * Portal's own `com.facebook.portal.aiservice` holds one of them open more or less
     * permanently at a high priority score. A fixed DEFAULT_FRONT_CAMERA selector is a
     * coin flip between the free camera and the occupied one; picking the occupied one
     * fails to bind and presence would report unavailable on a device that is perfectly
     * capable of it.
     */
    private fun bind(cameraProvider: ProcessCameraProvider) {
        provider = cameraProvider

        val cameras = cameraProvider.availableCameraInfos
        if (cameras.isEmpty()) {
            fail("no cameras reported by CameraX")
            return
        }

        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

        // Prefer any camera the default front selector matches, then fall back to the
        // rest — the occupied one is usually the one the system reaches for first.
        val ordered = cameras.sortedByDescending { info ->
            runCatching {
                CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(info)).isNotEmpty()
            }.getOrDefault(false)
        }

        for ((index, info) in ordered.withIndex()) {
            val analysis = ImageAnalysis.Builder()
                // Only the newest frame matters; queueing would add latency for no gain.
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
        previousLuma = null
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
     * Re-opens the camera if it was lost.
     *
     * Android 9 disconnects the camera from background processes. PresenceService should
     * prevent that, but a system-initiated disconnect (another app taking the camera at
     * higher priority, for instance) would otherwise leave detection dead until the app
     * restarted — and while it is dead the frame falls back to the schedule and may
     * never wake on presence again.
     */
    fun recoverIfStopped() {
        if (status != Status.UNAVAILABLE || running.get()) return
        val now = System.currentTimeMillis()
        // Throttled: without this a permanently unavailable camera — no permission, or
        // Portal's own service holding both — would retry on every tick, forever.
        if (now - lastRecoveryAttemptMs < RECOVERY_INTERVAL_MS) return
        lastRecoveryAttemptMs = now
        Log.i(TAG, "attempting to recover presence detection ($lastReason)")
        start()
    }

    @Volatile
    private var lastRecoveryAttemptMs = 0L

    @OptIn(ExperimentalGetImage::class)
    private fun analyse(proxy: ImageProxy) {
        // ML Kit reads the underlying image asynchronously. Closing the proxy while a
        // detection is in flight frees memory out from under it — so ownership passes to
        // the completion listener whenever we hand a frame over, and only the paths that
        // do NOT hand it over close it here.
        var handedToDetector = false
        try {
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now

            // A recently confirmed face keeps presence alive without re-running the
            // detector, so someone sitting still is not repeatedly re-verified.
            if (now - lastFaceMs < FACE_GRACE_MS) {
                lastPresenceMs = now
                return
            }

            if (!hasMotion(proxy)) return

            val image = proxy.image ?: return
            if (faceInFlight.getAndSet(true)) return

            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
            handedToDetector = true
            faceDetector.process(input)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        lastFaceMs = System.currentTimeMillis()
                        lastPresenceMs = lastFaceMs
                        lastReason = "${faces.size} face(s) in view"
                    } else {
                        lastReason = "motion but no face"
                    }
                }
                .addOnFailureListener { lastReason = "face detection failed: ${it.message}" }
                .addOnCompleteListener {
                    faceInFlight.set(false)
                    proxy.close()
                }
        } catch (e: Exception) {
            Log.w(TAG, "frame analysis failed: ${e.message}")
        } finally {
            if (!handedToDetector) proxy.close()
        }
    }

    /**
     * Luminance frame differencing on a strided sample of the Y plane. Cheap enough to
     * run on every sampled frame; its only job is to decide whether the face detector
     * is worth waking.
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

        val prev = previousLuma
        previousLuma = sampled
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
