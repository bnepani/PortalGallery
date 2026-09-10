package com.example.portalgallery.ui.slideshow

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.portalgallery.data.store.PhotoStore
import com.example.portalgallery.prefs.AppPreferences
import kotlin.math.roundToInt

/**
 * Draws the living wall: several photos at once, one tile changing at a time.
 *
 * Owns the view pool declared in activity_slideshow.xml and nothing else. It does not
 * choose photos — [nextPhoto] and [setSelection] hand them in — and it does not decide
 * when to be on screen. That keeps every policy question in the Activity, where the
 * sleep, pause and lifecycle state already lives.
 *
 * @param context must be the Activity. Glide binds its request manager to the lifecycle
 *   of the Context it is given; an application Context would leave tile loads in flight
 *   after the Activity is destroyed.
 * @param container the `fl_collage` layer, shown and hidden through [visible].
 * @param tiles one [Tile] per slot, in slot order, of size [CollageLayout.MAX_SLOTS].
 * @param handler the Activity's main-thread Handler, so the driver is cancelled by the
 *   same `removeCallbacksAndMessages(null)` in onDestroy that clears everything else.
 */
class CollageRenderer(
    private val context: Context,
    private val container: FrameLayout,
    private val tiles: List<Tile>,
    private val prefs: AppPreferences,
    private val handler: Handler,
) {

    /**
     * One slot's views: the frame that gets positioned, and the A/B pair inside it.
     *
     * The pair mirrors the role swap the layout header documents — load into whichever
     * view is hidden, animate, then flip which one counts as live. Copying one Drawable
     * into both is what produced the rendering oddities recorded there.
     */
    data class Tile(val frame: FrameLayout, val a: ImageView, val b: ImageView)

    companion object {
        private const val TAG = "PortalGallery"

        /**
         * Floor on the swap interval.
         *
         * Nothing writes `collageTileSwapMs` today — it only ever holds its default — but
         * a settings screen eventually will, and a zero there would post the driver back
         * with no delay and spin the main thread on a device left running for months.
         */
        private const val MIN_SWAP_MS = 500L
    }

    /** Slots of the template currently applied. Empty until [applyTemplate] runs. */
    private var slots: List<CollageLayout.Slot> = emptyList()

    // Sized from the pool rather than from MAX_SLOTS, so a pool of the wrong size is a
    // template that gets truncated with a log rather than an index out of bounds.

    /** What each slot is showing, or has been asked to show. */
    private val current = arrayOfNulls<PhotoStore.StoredPhoto>(tiles.size)

    /** Which of each pair holds the live photo. Flips when a crossfade starts. */
    private val frontIsA = BooleanArray(tiles.size) { true }

    /** When each slot last completed a render. 0 means it has never shown anything. */
    private val renderedAtMs = LongArray(tiles.size)

    /**
     * Bumped per slot on every render request, so a load that finishes after a newer one
     * for the same slot has already started cannot run its crossfade. Without it,
     * [setSelection] landing on top of an in-flight tile swap fades in the view the newer
     * load has just emptied, and the tile goes black until that one arrives.
     */
    private val generation = IntArray(tiles.size)

    private var swapOrder: List<Int> = emptyList()
    private var swapCursor = 0
    private var running = false

    /**
     * A fresh photo for one slot, or null to leave that tile alone this tick.
     *
     * A callback rather than a queue because the choice depends on what the rest of the
     * wall is showing, and that is the caller's business — see [photoAt].
     */
    var nextPhoto: ((slotIndex: Int) -> PhotoStore.StoredPhoto?)? = null

    /** Whether the collage layer is on screen. */
    var visible: Boolean
        get() = container.visibility == View.VISIBLE
        set(value) {
            container.visibility = if (value) View.VISIBLE else View.GONE
        }

    /**
     * When the tile refreshed longest ago last rendered, or 0 while any active slot has
     * yet to render at all.
     *
     * A wall needs this rather than the single `lastRenderedAtMs` the full-screen path
     * uses: with six tiles, five frozen and one healthy would keep bumping a shared
     * timestamp and report the whole grid as alive. The minimum reports the worst tile.
     *
     * 0 during warm-up is deliberate and matches the existing watchdog's `> 0` guard —
     * the grid fills one tile per tick, so a freshly started wall legitimately has slots
     * that have never drawn, and that is not a stall.
     *
     * A tile whose file has been deleted under it keeps its old timestamp, so it does
     * drag this value back. That is the intended reading: a tile that can never load is
     * a frozen tile, whatever the other five are doing.
     *
     * **What the consumer does about it is not settled here, and the existing watchdog's
     * answer is the wrong one.** SlideshowActivity's watchdog responds to a stall by
     * calling `advance()`, which for a grid means disturbing all six tiles because one is
     * stuck. The right response to this signal is to redraw the offending slot with a
     * different photo — so whoever wires this up needs to identify that slot rather than
     * point the old watchdog at a new number. It also needs a bound: the precedent is
     * `consecutiveFailures` in `showStill()`, which exists so a corrupt library cannot
     * spin the frame at full speed, and a per-slot retry has exactly the same exposure.
     */
    val oldestRenderMs: Long
        get() {
            var oldest = Long.MAX_VALUE
            for (s in slots.indices) {
                if (renderedAtMs[s] == 0L) return 0L
                if (renderedAtMs[s] < oldest) oldest = renderedAtMs[s]
            }
            return if (oldest == Long.MAX_VALUE) 0L else oldest
        }

    /** What slot [slotIndex] was last asked to show, so a caller can avoid repeating it. */
    fun photoAt(slotIndex: Int): PhotoStore.StoredPhoto? = current.getOrNull(slotIndex)

    // --- geometry -----------------------------------------------------------

    /**
     * Positions the pool for [template] on a [panelW] x [panelH] panel, and parks every
     * slot the template does not use.
     *
     * The driver cannot be interleaved with this: it runs on the same main-thread
     * Handler, so a tick either finished before this call or starts after it. What can
     * straddle the call is a Glide load already in flight for a slot the new template
     * keeps — it was sized against the old rect and will draw one render slightly soft.
     * The [setSelection] that follows a template change re-loads at the new size.
     */
    fun applyTemplate(template: CollageLayout.Template, panelW: Int, panelH: Int) {
        val used = template.slots.size.coerceAtMost(tiles.size)
        if (template.slots.size > tiles.size) {
            // CollageLayout says no template may exceed MAX_SLOTS. Log and draw what fits
            // rather than throw: a frame that runs unattended for months should degrade,
            // not die, if that invariant is ever broken.
            Log.e(
                TAG,
                "template ${template.name} wants ${template.slots.size} slots but the pool " +
                    "holds ${tiles.size} — drawing the first $used",
            )
        }
        slots = template.slots.take(used)

        for (i in tiles.indices) {
            val tile = tiles[i]
            if (i >= used) {
                // Release the bitmaps. A two-slot template would otherwise leave four
                // pairs holding tile-sized bitmaps for as long as it is up, and a
                // template change is exactly when that memory should come back.
                Glide.with(context).clear(tile.a)
                Glide.with(context).clear(tile.b)
                tile.a.animate().cancel()
                tile.b.animate().cancel()
                tile.a.alpha = 0f
                tile.b.alpha = 0f
                tile.frame.visibility = View.GONE
                current[i] = null
                frontIsA[i] = true
                renderedAtMs[i] = 0L
                generation[i]++
                continue
            }

            val slot = slots[i]
            // Round each edge to a pixel and subtract, rather than rounding the width.
            // Two slots that share a fractional edge hold the identical Float there, so
            // rounding the edge gives them the identical pixel and the tiles abut
            // exactly; rounding widths independently would leave a seam or an overlap
            // wherever the fraction did not divide the panel evenly.
            val left = (slot.left * panelW).roundToInt()
            val top = (slot.top * panelH).roundToInt()
            val right = (slot.right * panelW).roundToInt()
            val bottom = (slot.bottom * panelH).roundToInt()

            tile.frame.layoutParams = FrameLayout.LayoutParams(
                right - left,
                bottom - top,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(left, top, 0, 0) }
            tile.frame.visibility = View.VISIBLE
        }

        // The template decides how many slots there are, so any round in progress is
        // about the wrong grid.
        swapOrder = emptyList()
        swapCursor = 0
    }

    // --- content ------------------------------------------------------------

    /**
     * Replaces every tile at once, from one photo per slot in slot order — the shape
     * CollageSelector.fill returns.
     *
     * Extra photos are ignored. A short list leaves the remaining tiles on what they were
     * already showing, which for a slot that has never rendered means a black rectangle;
     * CollageSelector fills every slot or repeats a photo precisely so that cannot
     * happen, so the log below is there to name the culprit if it ever does.
     */
    fun setSelection(photos: List<PhotoStore.StoredPhoto>) {
        if (slots.isEmpty()) {
            // Nothing has geometry yet, so there is no tile to load into and the whole
            // call would be a silent no-op. Worth a line, because the only way to get
            // here is a caller that has not run applyTemplate first.
            Log.w(TAG, "setSelection with no template applied — ignoring ${photos.size} photos")
            return
        }
        if (photos.size < slots.size) {
            Log.w(
                TAG,
                "selection holds ${photos.size} photos for ${slots.size} slots — " +
                    "the rest keep their previous tile",
            )
        }
        for (s in slots.indices) {
            val photo = photos.getOrNull(s) ?: continue
            current[s] = photo
            renderSlot(s, photo)
        }
    }

    private fun renderSlot(slot: Int, photo: PhotoStore.StoredPhoto) {
        val tile = tiles[slot]
        val incoming = if (frontIsA[slot]) tile.b else tile.a
        val gen = ++generation[slot]

        // Park the incoming view before Glide touches it: it is about to be faded up from
        // whatever pose its last turn as the live tile left it in.
        incoming.animate().cancel()
        incoming.alpha = 0f

        Glide.with(context)
            .load(photo.file)
            // Our own crossfade runs below; Glide's would fight it.
            .dontAnimate()
            // Same reason as the full-screen path: the file is already on our own disk,
            // and AUTOMATIC would re-encode a duplicate of it into Glide's cache.
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            // ---------------------------------------------------------------------
            // KEEP THIS LOAD SCALE-ONLY.
            //
            // Glide hands out Bitmap.Config.HARDWARE — genuinely available on this API 28
            // device — only while DecodeJob.getOptionsWithHardwareConfig() sees
            // isScaleOnlyOrNoTransform(). That flag survives the scale-only transforms,
            // and the CenterCrop that the tile's centerCrop scaleType installs is one of
            // them. Add a rounded-corner, a blur, or any other transformation to this
            // request and the flag flips: every tile decodes to ARGB_8888 on the Java
            // heap instead of off it.
            //
            // Measured on this project, twelve tile ImageViews cost about two screenfuls
            // of bitmap (~16.6MB) rather than twelve full-screen ones, and of the ~54MB
            // of live bitmap only ~4MB sits on the Java heap. Losing the hardware config
            // moves that ~54MB onto a 256MB growth limit.
            //
            // There is no error, no warning and no log when it happens — just an OOM
            // weeks later on a wall-mounted frame nobody is watching. If a tile needs
            // rounded corners, round the containing frame, not the bitmap.
            //
            // The tile's scaleType is part of this rule, not just a framing choice.
            // into(ImageView) picks the transformation from the view's scaleType —
            // centerCrop selects optionalCenterCrop(), fitCenter selects
            // optionalFitCenter(), and matrix or center select none at all. Both of the
            // first two are scale-only, so either keeps the hardware config; what they do
            // NOT agree on is size. CenterCrop decodes the bitmap cropped to the tile,
            // which is where the ~16.6MB above comes from. Under matrix Glide applies no
            // transformation and decodes to cover instead, so a 4000x3000 photo in a
            // 640x1080 slot arrives as 1440x1080 rather than 640x1080 — roughly 2.3x the
            // bytes, on every tile. Changing the scaleType in the layout therefore moves
            // the memory budget, and the measurement above stops holding.
            // ---------------------------------------------------------------------
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    // Keep the photo the tile already had. A black rectangle beside five
                    // photographs for a whole dwell is the outcome CollageSelector's relax
                    // cascade exists to avoid, and it should not be reintroduced here. The
                    // slot keeps its old renderedAt, so a tile that never loads surfaces in
                    // oldestRenderMs; the next round offers it a different photo anyway.
                    Log.w(TAG, "tile $slot: load failed for ${photo.id}: ${e?.message}")
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    // post() so the drawable is attached before the crossfade starts —
                    // Glide sets it into the view after this listener returns false.
                    incoming.post {
                        if (generation[slot] == gen) crossfade(slot, incoming)
                    }
                    return false
                }
            })
            // ViewTarget is keyed on the view, so this cancels the tile's previous
            // request on its own. See the header of activity_slideshow.xml.
            .into(incoming)
    }

    private fun crossfade(slot: Int, incoming: ImageView) {
        val tile = tiles[slot]
        val outgoing = if (frontIsA[slot]) tile.a else tile.b
        val duration = prefs.transitionMs.toLong()

        // Flipped here, at the start, rather than from the animation's end action. An end
        // action does not run when the animation is cancelled — which [stop] does — and a
        // slot left believing the wrong view is live has no way back: it would park
        // half-faded and stay there, because every later render reads frontIsA to decide
        // which view to load into.
        frontIsA[slot] = !frontIsA[slot]
        renderedAtMs[slot] = System.currentTimeMillis()

        // A fade from this slot's previous turn may still be running on the outgoing
        // view. Cancel it rather than letting two alpha animations race.
        outgoing.animate().cancel()

        incoming.animate().alpha(1f).setDuration(duration).start()
        outgoing.animate().alpha(0f).setDuration(duration).start()
    }

    // --- the driver ---------------------------------------------------------

    /**
     * One runnable, swapping one tile per tick.
     *
     * Not six staggered tile timers plus a hero timer, which is the arrangement this
     * replaced. Seven self-reposting runnables all have to stay in phase across
     * enterSleep, onPause, onResume and onDestroy, and this Activity's Handler has
     * already produced one duplicate-post bug of exactly that shape — see
     * SlideshowActivity.restartWatchdog for what it cost. One tile per tick looks the
     * same on the wall, staggers by construction, and leaves one thing to cancel.
     *
     * Held in a val, because removeCallbacks matches on identity and a lambda built at
     * each post site would be a different object every time.
     */
    private val driver = object : Runnable {
        override fun run() {
            swapOneTile()
            reschedule()
        }
    }

    /** Begins swapping tiles. Idempotent: calling it twice does not start two loops. */
    fun start() {
        running = true
        reschedule()
    }

    /**
     * Stops swapping and parks every tile at a clean pose.
     *
     * Safe before [start] and safe twice. The settle matters because the callers are the
     * paths that go dark — enterSleep, onPause — and a crossfade or a Ken Burns cancelled
     * part-way leaves alpha and the transform mid-flight, which is then exactly what the
     * frame comes back to.
     */
    fun stop() {
        running = false
        reschedule()
        for (s in tiles.indices) {
            // Disown any load still in flight as well. Its crossfade would otherwise land
            // after the settle below and start animating a tile the frame has just put to
            // sleep — the same window the full-screen path has, closed here because the
            // generation counter makes it a one-liner.
            generation[s]++
            settle(s)
        }
    }

    /**
     * The only place [driver] is posted or cancelled.
     *
     * Same rule, and the same reason, as SlideshowActivity.restartWatchdog: [driver]
     * re-posts itself, so a second post site anywhere would start a second self-sustaining
     * loop swapping tiles on its own schedule. Removing before posting is what makes
     * [start] idempotent, and checking [running] after the removal is what lets [stop] be
     * nothing more than clearing the flag and calling back in here.
     */
    private fun reschedule() {
        handler.removeCallbacks(driver)
        if (!running) return
        handler.postDelayed(driver, swapIntervalMs())
    }

    private fun swapIntervalMs(): Long =
        prefs.collageTileSwapMs.toLong().coerceAtLeast(MIN_SWAP_MS)

    private fun swapOneTile() {
        if (slots.isEmpty()) return
        val slot = nextSlot()
        // Null means the caller has nothing to offer for this slot right now. The tile
        // keeps what it has and simply forfeits its turn; the round moves on rather than
        // retrying the same slot until it answers.
        val photo = nextPhoto?.invoke(slot) ?: return
        current[slot] = photo
        renderSlot(slot, photo)
    }

    /**
     * Which tile this tick swaps: a shuffled permutation of the slots, replayed one per
     * tick and reshuffled when it runs out.
     *
     * Not `cursor++ % n`. That is the obvious choice and it walks the grid left to right
     * in lockstep, which on thirds-portrait is a marquee rather than a wall that
     * breathes. Not a uniform random slot either: that leaves a slot's staleness
     * unbounded, and a tile that goes unpicked for a long run drags [oldestRenderMs] back
     * far enough to read as a stall the watchdog then acts on.
     *
     * A permutation gives both properties. There is no fixed spatial order, and every
     * slot is refreshed exactly once per round, which bounds the gap between two
     * refreshes of one tile at 2n-1 ticks. It also cannot pin anything to slot 0 — the
     * defect era mix and recency each had to be fixed for on this branch — because every
     * slot leads a round about equally often.
     *
     * The rotation mirrors SlideshowActivity.advance(): a fresh permutation is free to
     * open on the slot the last one closed with, which would swap one tile twice a single
     * tick apart while the other five waited.
     */
    private fun nextSlot(): Int {
        if (swapCursor >= swapOrder.size) {
            val last = swapOrder.lastOrNull()
            swapOrder = slots.indices.shuffled().let {
                if (it.firstOrNull() == last && it.size > 1) it.drop(1) + it.first() else it
            }
            swapCursor = 0
        }
        return swapOrder[swapCursor++]
    }

    /** Cancels a slot's animations and leaves its pair at the pose they should rest in. */
    private fun settle(slot: Int) {
        val tile = tiles[slot]
        val live = if (frontIsA[slot]) tile.a else tile.b
        for (v in listOf(tile.a, tile.b)) {
            v.animate().cancel()
            // A slot that has never rendered has nothing to show, so neither of its views
            // is the live one — leave both hidden rather than revealing an empty view.
            v.alpha = if (v === live && renderedAtMs[slot] > 0L) 1f else 0f
        }
    }
}
