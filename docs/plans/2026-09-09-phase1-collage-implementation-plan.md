# Plan: Phase 1 — Collage Mode (no RPC dependency)

**Goal**: The frame renders a "living wall" collage of up to 6 photos with periodic
full-screen hero interludes, driven by three toggleable curation modes, using only the
photos already on disk — recovering the 56% of the library the orientation filter currently
discards.

**Architecture**: Two new pure-Kotlin units (`CollageLayout`, `CollageSelector`) carry all
the testable logic. `SlideshowActivity` gains a single driver Runnable that swaps one slot
per tick against a **fixed view pool** of 12 ImageViews (6 slots × A/B). No network changes,
no `PhotoStore` schema changes, no dependency on the `snAcKc` RPC.

**Tech Stack**: Kotlin, Android API 26+ (target device API 28), JUnit 4, Glide 4.16, view
binding. Gradle.

**Design**: `docs/plans/2026-09-09-collage-and-full-archive-design-v2.md` §7, §8, §10, §0.2

**Test command (all steps)**:
```bash
./gradlew :app:testDebugUnitTest --console=plain
```

**End-to-end**: `./tools/verify.sh "<share link>"`

> **Commits.** Each step ends with a `git commit`. Run these yourself, or ask and I will —
> I do not commit unprompted.

---

## Task Dependencies

| Group | Steps | Files Touched | Can Parallelize |
|-------|-------|---------------|-----------------|
| 1 | Steps 1–3 | `SlideshowActivity.kt` (1,2), `AlbumSync.kt` (3) | Step 3 parallel with 1–2; steps 1 and 2 share a file, run in order |
| 2 | Steps 4–7 | `CollageLayout.kt` + test | No (sequential build-up of one file) |
| 3 | Steps 8–13 | `CollageSelector.kt` + test | No (sequential build-up of one file) |
| 4 | Step 14 | `AppPreferences.kt` | Yes — independent of Groups 2–3 |
| 5 | Steps 15–20 | `activity_slideshow.xml`, `SlideshowActivity.kt` | No (depends on Groups 2–4) |
| 6 | Step 21 | `SettingsActivity.kt`, `strings.xml` | No (depends on Group 4) |
| 7 | Step 22 | — | No (final e2e) |

Groups execute sequentially. Group 4 may run at any point after Group 1.

---

# Group 1 — Pre-existing bug fixes

These are bugs in shipped code, independent of collage. Fixing them first means the collage
is not built on top of them. Design §0.2.

## Step 1: Stop the watchdog double-posting on every presence wake

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt`

**Bug**: `exitSleep()` posts `watchdogRunnable` at `:519`. It then calls `startActivity` with
`FLAG_ACTIVITY_REORDER_TO_FRONT` at `:498-507`, which delivers `onResume`, which posts
`watchdogRunnable` **again** at `:870` with no preceding `removeCallbacks`. Because the
runnable re-posts itself at `:131`, every presence-driven wake permanently adds another
watchdog loop.

### 1a. Write failing test

Not unit-testable — `Handler`/`Looper` and Activity lifecycle. Verified by inspection and by
the logcat check in Step 22. Proceed to 1c.

### 1b. n/a

### 1c. Write implementation

There are three post sites for `watchdogRunnable` (`:259`, `:519`, `:870`). Give it a single
guarded entry point and use it everywhere.

Add near `scheduleNext()`:

```kotlin
/**
 * The only place watchdogRunnable is posted.
 *
 * It re-posts itself, so a bare postDelayed() adds a *permanent* second loop. That was the
 * bug: exitSleep() posted it, then its own startActivity(REORDER_TO_FRONT) delivered
 * onResume, which posted it again — one extra loop per presence wake, forever.
 */
private fun restartWatchdog() {
    handler.removeCallbacks(watchdogRunnable)
    handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)
}
```

Replace all three `handler.postDelayed(watchdogRunnable, prefs.slideshowIntervalSeconds * 1000L)`
call sites with `restartWatchdog()`.

### 1d. Verify

```bash
./gradlew :app:testDebugUnitTest --console=plain
grep -c "handler.postDelayed(watchdogRunnable" app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt
```
Expect the grep to print `1` (only inside `restartWatchdog`).

### 1e. Commit
```bash
git commit -am "Fix watchdog double-post accumulating one loop per presence wake"
```

---

## Step 2: Stop Glide re-encoding a second copy of the library to disk

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt`

**Bug**: `showStill()` loads a local `File` with Glide's default `DiskCacheStrategy.AUTOMATIC`.
For a `DataSource.LOCAL` load that re-encodes and writes a duplicate copy into Glide's 250 MB
disk LRU. At collage tick rates this is ~25,920 pointless writes/day on a device that must
run for months.

### 2a–2b. n/a — one-line change, covered by Step 22 e2e.

### 2c. Write implementation

Add the import:
```kotlin
import com.bumptech.glide.load.engine.DiskCacheStrategy
```

In `showStill()`, on the `Glide.with(this).load(photo.file)` chain, insert before `.listener(`:
```kotlin
    // The file is already on our disk. AUTOMATIC would re-encode a second copy into
    // Glide's 250MB LRU — pure churn on a device that runs for months.
    .diskCacheStrategy(DiskCacheStrategy.NONE)
```

### 2d. Verify
```bash
./gradlew :app:testDebugUnitTest --console=plain
```

### 2e. Commit
```bash
git commit -am "Disable Glide disk cache for local files"
```

---

## Step 3: Make the resolution-gate abort sentinel race-free

**File**: `app/src/main/kotlin/com/example/portalgallery/data/store/AlbumSync.kt`

**Bug**: `failures` is a plain `Int` mutated from 4 concurrent coroutines. `failures++` at
`:145` is read-modify-write; it can interleave with the `failures = Int.MAX_VALUE` abort
sentinel at `:156` and overwrite it, defeating the resolution gate that protects against
indexing thumbnails.

### 3a. Write failing test

Not deterministically unit-testable (a race). Correctness is by construction — replace the
shared mutable `Int` with atomics.

### 3c. Write implementation

Add imports:
```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
```

Replace the declarations:
```kotlin
var done = 0
var failures = 0
var resolutionChecked = false
```
with:
```kotlin
// Mutated from CONCURRENCY coroutines at once. `failures++` on a plain Int is a
// read-modify-write and could overwrite the abort flag, defeating the resolution gate.
val done = AtomicInteger(0)
val failures = AtomicInteger(0)
val resolutionChecked = AtomicBoolean(false)
val aborted = AtomicBoolean(false)
```

Then in the download body:
- `onProgress(++done, missing.size)` → `onProgress(done.incrementAndGet(), missing.size)`
- `failures++` → `failures.incrementAndGet()`
- the resolution-gate guard `if (!photo.isVideo && !resolutionChecked) { resolutionChecked = true; … }`
  → `if (!photo.isVideo && resolutionChecked.compareAndSet(false, true)) { … }`
  (`compareAndSet` also fixes a second latent bug: today two coroutines can both pass the
  `!resolutionChecked` check and both run the gate.)
- `failures = Int.MAX_VALUE` → `aborted.set(true)`
- `if (failures == Int.MAX_VALUE) return@coroutineScope` → `if (aborted.get()) return@coroutineScope`
- `if (failures == Int.MAX_VALUE) { return@withContext Result.Failure(...) }` → `if (aborted.get()) { … }`
- in the `Result.Success` construction, `added = missing.size - failures.coerceAtMost(missing.size)`
  → `added = missing.size - failures.get().coerceAtMost(missing.size)`

### 3d. Verify
```bash
./gradlew :app:testDebugUnitTest --console=plain
```
All existing `AlbumSync`-adjacent tests must still pass.

### 3e. Commit
```bash
git commit -am "Make AlbumSync download counters atomic; fix duplicate resolution-gate runs"
```

---

# Group 2 — `CollageLayout`

## Step 4: Slot and Template types with fractional geometry

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/CollageLayout.kt` (new)

### 4a. Write failing test

**File**: `app/src/test/kotlin/com/example/portalgallery/ui/slideshow/CollageLayoutTest.kt` (new)

```kotlin
package com.example.portalgallery.ui.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageLayoutTest {

    @Test
    fun `slot reports its fractional size`() {
        val s = CollageLayout.Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)
        assertEquals(0.5f, s.width, 1e-6f)
        assertEquals(1f, s.height, 1e-6f)
    }

    @Test
    fun `slot knows whether it is portrait shaped on a given panel`() {
        // A half-width, full-height slot on a 1920x1080 panel is 960x1080 — portrait.
        val s = CollageLayout.Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)
        assertTrue(s.isPortraitOn(1920, 1080))
    }
}
```

### 4b. Run to verify it fails
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageLayoutTest*'
```
Expect a compilation failure — `CollageLayout` does not exist.

### 4c. Write implementation

```kotlin
package com.example.portalgallery.ui.slideshow

/**
 * Collage templates: where the tiles go, and what shape of photo each one wants.
 *
 * Geometry is **fractional** (0..1) rather than pixels, so a template is resolution- and
 * orientation-independent and can be unit tested with no device. The renderer multiplies
 * by the panel size.
 *
 * Templates are data, deliberately. Procedural packing produces slivers and is hard to
 * assert about; a fixed set can be checked exhaustively — see CollageLayoutTest.
 *
 * [Slot.wantPortrait] is the mechanism that recovers the 56% of the library the
 * whole-screen orientation filter discards: a landscape panel has no use for a portrait
 * photo, but a 640x1080 slot has.
 */
object CollageLayout {

    /** The renderer allocates a fixed view pool of this many slots. No template may exceed it. */
    const val MAX_SLOTS = 6

    data class Slot(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /** Which orientation of photo this slot is shaped for. */
        val wantPortrait: Boolean,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** True when this slot is taller than it is wide once projected onto a real panel. */
        fun isPortraitOn(panelW: Int, panelH: Int): Boolean =
            height * panelH > width * panelW
    }

    data class Template(val name: String, val slots: List<Slot>)
}
```

### 4d. Verify
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageLayoutTest*'
```

### 4e. Commit
```bash
git commit -am "Add CollageLayout Slot/Template geometry types"
```

---

## Step 5: The landscape template set

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/CollageLayout.kt`

### 5a. Write failing test

Append to `CollageLayoutTest`:

```kotlin
    @Test
    fun `landscape set is non-empty and within the view pool`() {
        val set = CollageLayout.forPanel(portrait = false)
        assertTrue("expected several templates", set.size >= 4)
        set.forEach {
            assertTrue("${it.name} exceeds MAX_SLOTS", it.slots.size <= CollageLayout.MAX_SLOTS)
            assertTrue("${it.name} has no slots", it.slots.isNotEmpty())
        }
    }

    @Test
    fun `at least one landscape template is all portrait slots`() {
        // This is the template that recovers the 56% portrait library on a landscape panel.
        val set = CollageLayout.forPanel(portrait = false)
        assertTrue(set.any { t -> t.slots.all { it.wantPortrait } })
    }
```

### 5b. Run — fails (`forPanel` missing).

### 5c. Write implementation

Add to `CollageLayout`:

```kotlin
    /** Three full-height portrait columns. The template that pays for the 56%. */
    private val THIRDS_PORTRAIT = Template(
        "thirds-portrait",
        (0..2).map { i ->
            Slot(i / 3f, 0f, (i + 1) / 3f, 1f, wantPortrait = true)
        },
    )

    /** 3x2 uniform grid — six landscape-ish cells. */
    private val GRID_3X2 = Template(
        "grid-3x2",
        (0..1).flatMap { row ->
            (0..2).map { col ->
                Slot(col / 3f, row / 2f, (col + 1) / 3f, (row + 1) / 2f, wantPortrait = false)
            }
        },
    )

    /** One tall photo on the left, a 2x2 of small ones on the right. */
    private val HERO_LEFT = Template(
        "hero-left",
        listOf(Slot(0f, 0f, 0.5f, 1f, wantPortrait = true)) +
            (0..1).flatMap { row ->
                (0..1).map { col ->
                    Slot(
                        0.5f + col * 0.25f, row / 2f,
                        0.5f + (col + 1) * 0.25f, (row + 1) / 2f,
                        wantPortrait = false,
                    )
                }
            },
    )

    /** Two portrait columns flanking a stacked pair. */
    private val PORTRAIT_PAIR_CENTRE_STACK = Template(
        "portrait-pair-centre-stack",
        listOf(
            Slot(0f, 0f, 0.3f, 1f, wantPortrait = true),
            Slot(0.3f, 0f, 0.7f, 0.5f, wantPortrait = false),
            Slot(0.3f, 0.5f, 0.7f, 1f, wantPortrait = false),
            Slot(0.7f, 0f, 1f, 1f, wantPortrait = true),
        ),
    )

    /** Wide banner over three small cells. */
    private val BANNER_OVER_THIRDS = Template(
        "banner-over-thirds",
        listOf(Slot(0f, 0f, 1f, 0.6f, wantPortrait = false)) +
            (0..2).map { i ->
                Slot(i / 3f, 0.6f, (i + 1) / 3f, 1f, wantPortrait = false)
            },
    )

    private val LANDSCAPE = listOf(
        GRID_3X2, THIRDS_PORTRAIT, HERO_LEFT, PORTRAIT_PAIR_CENTRE_STACK, BANNER_OVER_THIRDS,
    )

    /** Portrait panel: the same ideas transposed. */
    private val PORTRAIT = listOf(
        Template("grid-2x3", (0..2).flatMap { row ->
            (0..1).map { col ->
                Slot(col / 2f, row / 3f, (col + 1) / 2f, (row + 1) / 3f, wantPortrait = true)
            }
        }),
        Template("thirds-landscape", (0..2).map { i ->
            Slot(0f, i / 3f, 1f, (i + 1) / 3f, wantPortrait = false)
        }),
        Template("hero-top", listOf(Slot(0f, 0f, 1f, 0.5f, wantPortrait = false)) +
            (0..1).flatMap { row ->
                (0..1).map { col ->
                    Slot(col / 2f, 0.5f + row * 0.25f, (col + 1) / 2f, 0.5f + (row + 1) * 0.25f,
                        wantPortrait = false)
                }
            }),
        Template("halves", listOf(
            Slot(0f, 0f, 1f, 0.5f, wantPortrait = false),
            Slot(0f, 0.5f, 1f, 1f, wantPortrait = false),
        )),
    )

    fun forPanel(portrait: Boolean): List<Template> = if (portrait) PORTRAIT else LANDSCAPE
```

### 5d. Verify
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageLayoutTest*'
```

### 5e. Commit
```bash
git commit -am "Add landscape and portrait collage template sets"
```

---

## Step 6: Exhaustive geometry validation

**File**: `app/src/test/kotlin/com/example/portalgallery/ui/slideshow/CollageLayoutTest.kt`

This is the highest-value test in Group 2 — it makes the templates data that cannot silently
go wrong.

### 6a. Write failing test

```kotlin
    private fun allTemplates() =
        CollageLayout.forPanel(portrait = false) + CollageLayout.forPanel(portrait = true)

    @Test
    fun `no slot is degenerate or out of bounds`() {
        allTemplates().forEach { t ->
            t.slots.forEachIndexed { i, s ->
                assertTrue("${t.name}[$i] width <= 0", s.width > 0f)
                assertTrue("${t.name}[$i] height <= 0", s.height > 0f)
                assertTrue("${t.name}[$i] out of bounds", s.left >= -1e-6f && s.top >= -1e-6f)
                assertTrue("${t.name}[$i] out of bounds", s.right <= 1f + 1e-6f && s.bottom <= 1f + 1e-6f)
                // A sliver is a rendering bug, not a design choice.
                assertTrue("${t.name}[$i] is a sliver", s.width >= 0.15f && s.height >= 0.15f)
            }
        }
    }

    @Test
    fun `slots tile the panel without overlapping`() {
        allTemplates().forEach { t ->
            val area = t.slots.sumOf { (it.width * it.height).toDouble() }
            assertEquals("${t.name} does not cover the panel", 1.0, area, 1e-4)

            for (i in t.slots.indices) {
                for (j in i + 1 until t.slots.size) {
                    val a = t.slots[i]
                    val b = t.slots[j]
                    val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                    val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                    assertTrue(
                        "${t.name}: slots $i and $j overlap",
                        overlapW <= 1e-6f || overlapH <= 1e-6f,
                    )
                }
            }
        }
    }

    @Test
    fun `slot orientation tags match their actual shape on the target panel`() {
        CollageLayout.forPanel(portrait = false).forEach { t ->
            t.slots.forEachIndexed { i, s ->
                assertEquals(
                    "${t.name}[$i] tag disagrees with its geometry on 1920x1080",
                    s.wantPortrait, s.isPortraitOn(1920, 1080),
                )
            }
        }
    }

    @Test
    fun `template names are unique`() {
        val names = allTemplates().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
```

### 6b. Run — expect failures. Area sums and orientation tags will disagree in places.

### 6c. Fix the templates until green

Adjust slot rects and `wantPortrait` tags in Step 5's data until all four tests pass. Do
**not** weaken the assertions. Notes:
- `BANNER_OVER_THIRDS` banner is 1.0 × 0.6 = 1920×648 → landscape. Correct.
- `HERO_LEFT` small cells are 0.25 × 0.5 = 480×540 → **portrait** on 1920×1080. Retag.
- `PORTRAIT_PAIR_CENTRE_STACK` centre cells are 0.4 × 0.5 = 768×540 → landscape. Correct.
- `GRID_3X2` cells are 640×540 → landscape. Correct.

### 6d. Verify
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageLayoutTest*'
```

### 6e. Commit
```bash
git commit -am "Validate collage template geometry exhaustively"
```

---

## Step 7: Deterministic template rotation

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/CollageLayout.kt`

Design §8.4: templates change only behind a hero interlude, so selection must be a pure
function of a counter rather than time-based.

### 7a. Write failing test

```kotlin
    @Test
    fun `template rotation is deterministic and eventually visits every template`() {
        val set = CollageLayout.forPanel(portrait = false)
        val seen = (0 until set.size * 3).map { CollageLayout.templateAt(portrait = false, index = it).name }
        assertEquals(set.map { it.name }.toSet(), seen.toSet())
        assertEquals(
            CollageLayout.templateAt(portrait = false, index = 7).name,
            CollageLayout.templateAt(portrait = false, index = 7).name,
        )
    }

    @Test
    fun `negative and large indices are safe`() {
        CollageLayout.templateAt(portrait = false, index = -3)
        CollageLayout.templateAt(portrait = false, index = Int.MAX_VALUE)
    }
```

### 7b. Run — fails.

### 7c. Write implementation

```kotlin
    /**
     * Template for the given rotation counter. Pure: the renderer swaps templates only
     * behind a hero interlude, so this must not depend on wall-clock time.
     */
    fun templateAt(portrait: Boolean, index: Int): Template {
        val set = forPanel(portrait)
        return set[Math.floorMod(index, set.size)]
    }
```

### 7d. Verify + 7e. Commit
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageLayoutTest*'
git commit -am "Add deterministic collage template rotation"
```

---

# Group 3 — `CollageSelector`

## Step 8: Config and the fill contract (C10)

**File**: `app/src/main/kotlin/com/example/portalgallery/data/schedule/CollageSelector.kt` (new)

### 8a. Write failing test

**File**: `app/src/test/kotlin/com/example/portalgallery/data/schedule/CollageSelectorTest.kt` (new)

```kotlin
package com.example.portalgallery.data.schedule

import com.example.portalgallery.ui.slideshow.CollageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

class CollageSelectorTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 9, 9)

    data class Item(val id: String, val portrait: Boolean, val captureMs: Long, val addedMs: Long)

    private fun ms(y: Int, m: Int, d: Int): Long =
        LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun item(id: String, year: Int, portrait: Boolean = false, addedYear: Int = year) =
        Item(id, portrait, ms(year, 6, 1), ms(addedYear, 6, 1))

    private fun fill(
        candidates: List<Item>,
        slots: List<CollageLayout.Slot> = CollageLayout.forPanel(false).first().slots,
        config: CollageSelector.Config = CollageSelector.Config(),
        rotation: Int = 0,
    ) = CollageSelector.fill(
        candidates = candidates,
        slots = slots,
        config = config,
        today = today,
        zone = zone,
        rotation = rotation,
        random = Random(1234),
        isPortrait = { it.portrait },
        captureMs = { it.captureMs },
        addedMs = { it.addedMs },
    )

    @Test
    fun `always returns exactly one item per slot`() {
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill((1..50).map { item("p$it", 2020 + it % 6) }, slots)
        assertEquals(slots.size, picks.size)
    }

    @Test
    fun `no duplicates within one grid when there is ample supply`() {
        val picks = fill((1..50).map { item("p$it", 2020 + it % 6) })
        assertEquals(picks.size, picks.map { it.id }.toSet().size)
    }

    @Test
    fun `empty candidate list yields an empty result rather than throwing`() {
        assertTrue(fill(emptyList()).isEmpty())
    }

    @Test
    fun `fewer candidates than slots still fills every slot`() {
        // C10: a slot must never render empty. Duplication is the last resort, and it is
        // correct — a repeated photo beats a black rectangle.
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill(listOf(item("only", 2024)), slots)
        assertEquals(slots.size, picks.size)
        assertTrue(picks.all { it.id == "only" })
    }
}
```

### 8b. Run — fails to compile.

### 8c. Write implementation

```kotlin
package com.example.portalgallery.data.schedule

import com.example.portalgallery.ui.slideshow.CollageLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Chooses which photo goes in which collage slot.
 *
 * Pure and Android-free, like [PhotoSelector], because the interesting behaviour is in the
 * degenerate cases — a year bucket with one resident photo of the wrong shape, a template
 * with more slots than candidates, an on-this-day filter that matches nothing — and those
 * are exactly what is hard to reproduce on a device.
 *
 * **The governing rule is C10: a slot never renders empty.** Every constraint relaxes
 * rather than returning short, and duplication is permitted as the final fallback, because
 * a repeated photo beats a black rectangle on a wall.
 *
 * Relax order is deliberate and is the resolution of a contradiction in the v1 design
 * (see design-v2 §7.3):
 *
 *   1. **Residency is hard.** Callers pass only photos whose bytes are on disk.
 *   2. **Era relaxes first.**
 *   3. **Orientation relaxes last** — the whole point of slot tags is to use portrait
 *      photos a landscape panel would otherwise discard. Stretching a landscape photo
 *      1.8x into a portrait slot defeats that.
 */
object CollageSelector {

    data class Config(
        val eraMix: Boolean = true,
        val onThisDay: Boolean = false,
        val recency: Boolean = true,
    )

    /** Photos added within this window are "new" for recency purposes. */
    private const val RECENT_WINDOW_MS = 21L * 24 * 60 * 60 * 1000

    fun <T> fill(
        candidates: List<T>,
        slots: List<CollageLayout.Slot>,
        config: Config,
        today: LocalDate,
        zone: ZoneId,
        rotation: Int,
        random: Random,
        isPortrait: (T) -> Boolean,
        captureMs: (T) -> Long,
        addedMs: (T) -> Long,
    ): List<T> {
        if (candidates.isEmpty() || slots.isEmpty()) return emptyList()
        // Filled in over Steps 9-13.
        return slots.map { candidates[random.nextInt(candidates.size)] }
    }
}
```

### 8d. Verify — the four Step 8 tests pass (the placeholder body satisfies them except
"no duplicates", which Step 12 fixes; mark that test `@Ignore` with a TODO referencing
Step 12, and remove the annotation there).

```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageSelectorTest*'
```

### 8e. Commit
```bash
git commit -am "Add CollageSelector skeleton and fill contract"
```

---

## Step 9: Year bucketing with √ damping

### 9a. Write failing test

```kotlin
    @Test
    fun `buckets are damped, not uniform`() {
        // 2026 has 100x the photos of 2019. Uniform stratification would give 2019 the
        // same screen time as 2026; sqrt damping keeps it visible without dominating.
        val big = (1..1000).map { item("new$it", 2026) }
        val small = (1..10).map { item("old$it", 2019) }
        val w = CollageSelector.bucketWeights(listOf(2019 to 10, 2026 to 1000))
        assertTrue("2026 must outweigh 2019", w.getValue(2026) > w.getValue(2019))
        // ...but by ~10x (sqrt of 100), not 100x.
        val ratio = w.getValue(2026) / w.getValue(2019)
        assertTrue("expected ~10x, got $ratio", ratio in 8.0..12.0)
        assertTrue(big.isNotEmpty() && small.isNotEmpty())
    }
```

### 9b. Run — fails.

### 9c. Write implementation

```kotlin
    /**
     * Slot share per year bucket, damped by sqrt of bucket size.
     *
     * **This is a product choice, stated.** A family archive is not uniform: 2026 may hold
     * 6,000 photos and 2019 four hundred. Strict one-slot-per-year would give 2019 16.7%
     * of screen time for 2% of the archive, making a 2019 photo recur ~15x as often as a
     * 2026 one. Raw proportional allocation goes the other way and buries the old years.
     * sqrt sits between: old years stay clearly visible, recent years still dominate.
     */
    fun bucketWeights(sizes: List<Pair<Int, Int>>): Map<Int, Double> {
        val raw = sizes.associate { (year, n) -> year to sqrt(n.toDouble()) }
        val total = raw.values.sum().takeIf { it > 0.0 } ?: return sizes.associate { it.first to 0.0 }
        return raw.mapValues { it.value / total }
    }

    fun <T> bucketByYear(items: List<T>, zone: ZoneId, captureMs: (T) -> Long): Map<Int, List<T>> =
        items.groupBy { t ->
            val ms = captureMs(t)
            // captureMs 0 means "unknown" — the index predates timestamps. Group these
            // together rather than mapping them all to 1970 and inventing a huge bucket.
            if (ms <= 0L) UNKNOWN_YEAR
            else Instant.ofEpochMilli(ms).atZone(zone).year
        }

    const val UNKNOWN_YEAR = -1
```

### 9d–9e. Verify and commit
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageSelectorTest*'
git commit -am "Add year bucketing with sqrt-damped weights"
```

---

## Step 10: Bucket→slot allocation with rotation (buckets > slots)

Design §7.1: eight years, six slots. Buckets rotate deterministically so no era is
permanently invisible — v1's "oldest buckets sharing" would have left two years absent from
every grid.

### 10a. Write failing test

```kotlin
    @Test
    fun `every bucket appears within a few rotations when buckets exceed slots`() {
        val years = 2019..2026 // 8 buckets
        val items = years.flatMap { y -> (1..20).map { item("p$y-$it", y) } }
        val slots = CollageLayout.forPanel(false).first().slots // 6 slots

        val seen = mutableSetOf<Int>()
        repeat(8) { r ->
            fill(items, slots, CollageSelector.Config(eraMix = true, recency = false), rotation = r)
                .forEach { seen.add(java.time.Instant.ofEpochMilli(it.captureMs).atZone(zone).year) }
        }
        assertEquals("every era must appear within 8 grids", years.toSet(), seen)
    }

    @Test
    fun `single bucket does not crash and fills every slot`() {
        val slots = CollageLayout.forPanel(false).first().slots
        val picks = fill((1..20).map { item("p$it", 2024) }, slots,
            CollageSelector.Config(eraMix = true))
        assertEquals(slots.size, picks.size)
    }
```

### 10b. Run — fails.

### 10c. Write implementation

Add an allocation helper that assigns each slot a target bucket:

```kotlin
    /**
     * Assigns a target year bucket to each slot.
     *
     * Buckets usually outnumber slots (8 years, 6 slots), so a fixed mapping would make two
     * eras permanently invisible. The starting offset advances with [rotation], so
     * successive grids cover the whole archive.
     */
    internal fun allocate(
        weights: Map<Int, Double>,
        slotCount: Int,
        rotation: Int,
    ): List<Int> {
        val years = weights.keys.sorted()
        if (years.isEmpty()) return emptyList()

        // Largest-remainder apportionment, so heavy buckets get proportionally more slots.
        val exact = years.map { it to weights.getValue(it) * slotCount }
        val base = exact.map { (y, e) -> y to e.toInt() }.toMap().toMutableMap()
        var assigned = base.values.sum()
        exact.sortedByDescending { (y, e) -> e - base.getValue(y) }
            .forEach { (y, _) -> if (assigned < slotCount) { base[y] = base.getValue(y) + 1; assigned++ } }

        val expanded = years.flatMap { y -> List(base.getValue(y)) { y } }
        // Rotate so the buckets that lost out this grid lead the next one.
        val offset = Math.floorMod(rotation, years.size.coerceAtLeast(1))
        return (expanded.drop(offset) + expanded.take(offset)).take(slotCount).ifEmpty {
            List(slotCount) { years[Math.floorMod(rotation + it, years.size)] }
        }
    }
```

> **Note for the implementer:** the rotation must also shift *which* buckets get the
> largest-remainder seats, or the same two years lose every time. If the test at 10a fails
> after this step, rotate the `sortedByDescending` tie-break by `rotation` as well.

### 10d–10e. Verify and commit
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageSelectorTest*'
git commit -am "Allocate collage slots across year buckets with rotation"
```

---

## Step 11: The reserved recency slot (requirement (a))

Design §7.2. Without this, era mix makes a newly-added photo ~50× less visible than today.

### 11a. Write failing test

```kotlin
    @Test
    fun `a freshly added photo appears in every grid when recency is on`() {
        val old = (1..500).map { item("old$it", 2020 + it % 5, addedYear = 2020 + it % 5) }
        val fresh = Item("FRESH", false, ms(2026, 9, 8), ms(2026, 9, 8))
        val slots = CollageLayout.forPanel(false).first().slots

        repeat(5) { r ->
            val picks = fill(old + fresh, slots,
                CollageSelector.Config(eraMix = true, recency = true), rotation = r)
            assertTrue("grid $r missed the new arrival", picks.any { it.id == "FRESH" })
        }
    }

    @Test
    fun `recency off means no reserved slot`() {
        val old = (1..500).map { item("old$it", 2021) }
        val fresh = Item("FRESH", false, ms(2026, 9, 8), ms(2026, 9, 8))
        val slots = CollageLayout.forPanel(false).first().slots
        val grids = (0 until 10).map {
            fill(old + fresh, slots, CollageSelector.Config(eraMix = true, recency = false), rotation = it)
        }
        assertTrue("without recency, FRESH should not be in every grid",
            grids.any { g -> g.none { it.id == "FRESH" } })
    }
```

### 11b. Run — fails.

### 11c. Write implementation

In `fill`, before bucket allocation: when `config.recency` is on and any candidate has
`addedMs` within `RECENT_WINDOW_MS` of `today`, reserve **slot 0** for that pool and allocate
buckets across the remaining `slots.size - 1`. Prefer a recent photo whose orientation
matches slot 0; relax orientation if none.

### 11d–11e. Verify and commit
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageSelectorTest*'
git commit -am "Reserve one collage slot for recent arrivals"
```

---

## Step 12: Relax ordering, orientation matching, and no-duplicates

### 12a. Write failing test

Remove the `@Ignore` from Step 8's duplicates test, then add:

```kotlin
    @Test
    fun `orientation is honoured when supply allows`() {
        val portraitTemplate = CollageLayout.forPanel(false).first { t -> t.slots.all { it.wantPortrait } }
        val mixed = (1..30).map { item("p$it", 2024, portrait = it % 2 == 0) }
        val picks = fill(mixed, portraitTemplate.slots, CollageSelector.Config(eraMix = false))
        assertTrue("every slot wants portrait and supply exists", picks.all { it.portrait })
    }

    @Test
    fun `era relaxes before orientation`() {
        // 2019 has exactly one resident photo and it is the wrong shape for a portrait slot.
        // Correct behaviour: take a portrait photo from another era, NOT a landscape from 2019.
        val portraitTemplate = CollageLayout.forPanel(false).first { t -> t.slots.all { it.wantPortrait } }
        val items = listOf(item("old-landscape", 2019, portrait = false)) +
            (1..30).map { item("new-portrait$it", 2025, portrait = true) }
        val picks = fill(items, portraitTemplate.slots, CollageSelector.Config(eraMix = true))
        assertTrue("orientation must survive; era may not", picks.all { it.portrait })
    }

    @Test
    fun `on this day boosts matching photos and relaxes when nothing matches`() {
        val slots = CollageLayout.forPanel(false).first().slots
        val anniversary = (1..10).map {
            Item("ANN$it", false, ms(2021, 9, 9), ms(2021, 9, 9))
        }
        val other = (1..200).map { item("other$it", 2023) }
        val hits = fill(anniversary + other, slots,
            CollageSelector.Config(eraMix = false, onThisDay = true))
        assertTrue("anniversary photos should dominate", hits.count { it.id.startsWith("ANN") } >= 3)

        // Nothing matches today -> must still fill every slot.
        val nonePicks = fill(other, slots, CollageSelector.Config(eraMix = false, onThisDay = true))
        assertEquals(slots.size, nonePicks.size)
    }

    @Test
    fun `most constrained slot is filled first`() {
        // One portrait photo, one portrait slot among landscape ones. Left-to-right greedy
        // assignment can consume it on slot 0; most-constrained-first must not.
        val slots = listOf(
            CollageLayout.Slot(0f, 0f, 0.5f, 0.5f, wantPortrait = false),
            CollageLayout.Slot(0.5f, 0f, 1f, 1f, wantPortrait = true),
            CollageLayout.Slot(0f, 0.5f, 0.5f, 1f, wantPortrait = false),
        )
        val items = listOf(item("theOnlyPortrait", 2024, portrait = true)) +
            (1..20).map { item("land$it", 2024, portrait = false) }
        val picks = fill(items, slots, CollageSelector.Config(eraMix = false))
        assertEquals("theOnlyPortrait", picks[1].id)
    }
```

### 12b. Run — fails.

### 12c. Write implementation

Replace `fill`'s placeholder body with:

1. Bucket the candidates (Step 9); allocate slot→bucket (Step 10); reserve slot 0 if recency
   is on (Step 11).
2. Order slots **most-constrained-first**: fewest eligible candidates satisfying
   (bucket ∧ orientation ∧ not-already-picked).
3. For each slot in that order, try in sequence, stopping at the first non-empty pool:
   a. bucket ∧ orientation ∧ unused
   b. **any era** ∧ orientation ∧ unused *(era relaxes first)*
   c. bucket ∧ any orientation ∧ unused
   d. any ∧ any ∧ unused
   e. any candidate at all *(duplication — the C10 floor)*
4. Within a pool, pick by weight: on-this-day multiplier when enabled and month/day matches;
   recency multiplier decaying linearly over `RECENT_WINDOW_MS`. Use `random` for the
   weighted draw so results are deterministic under test.
5. Return picks in **slot order**, not selection order.

### 12d–12e. Verify and commit
```bash
./gradlew :app:testDebugUnitTest --console=plain --tests '*CollageSelectorTest*'
git commit -am "Implement collage slot filling with relax cascade and weighted draws"
```

---

## Step 13: Property test across the real state space

Design §9: v1's "eight toggle combinations" did not cover toggles × template × bucket
distribution × residency.

### 13a. Write failing test

```kotlin
    @Test
    fun `C10 holds across the full state space`() {
        val distributions = listOf(
            listOf(2024),
            listOf(2019, 2026),
            (2019..2026).toList(),
        )
        val supplies = listOf(1, 3, 7, 40, 400)
        var cases = 0
        for (portrait in listOf(false, true)) {
            for (template in CollageLayout.forPanel(portrait)) {
                for (years in distributions) {
                    for (n in supplies) {
                        for (era in listOf(false, true)) {
                            for (otd in listOf(false, true)) {
                                for (rec in listOf(false, true)) {
                                    val items = (1..n).mapIndexed { i, _ ->
                                        item("p$i", years[i % years.size], portrait = i % 3 == 0)
                                    }
                                    val picks = fill(items, template.slots,
                                        CollageSelector.Config(era, otd, rec), rotation = cases)
                                    assertEquals(
                                        "${template.name} n=$n era=$era otd=$otd rec=$rec",
                                        template.slots.size, picks.size,
                                    )
                                    if (n >= template.slots.size) {
                                        assertEquals(
                                            "${template.name} n=$n duplicated with ample supply",
                                            picks.size, picks.map { it.id }.toSet().size,
                                        )
                                    }
                                    cases++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("expected broad coverage", cases > 500)
    }
```

### 13b–13d. Run, fix any starvation the sweep exposes, verify.

### 13e. Commit
```bash
git commit -am "Add CollageSelector property test across templates, supply and toggles"
```

---

# Group 4 — Preferences

## Step 14: Collage and curation preferences

**File**: `app/src/main/kotlin/com/example/portalgallery/prefs/AppPreferences.kt`

### 14c. Write implementation

Add properties following the file's existing style (each with a comment giving the reason
for its default):

```kotlin
    /** Collage mode. On by default: unlike the filters, it cannot empty the frame —
     *  CollageSelector always fills every slot — and it is what makes a large library
     *  legible on a wall. */
    var collageEnabled: Boolean
        get() = prefs.getBoolean(KEY_COLLAGE, true)
        set(value) = prefs.edit().putBoolean(KEY_COLLAGE, value).apply()

    /** Milliseconds between single-tile swaps in the living wall. */
    var collageTileSwapMs: Int
        get() = prefs.getInt(KEY_COLLAGE_SWAP_MS, 3_300)
        set(value) = prefs.edit().putInt(KEY_COLLAGE_SWAP_MS, value).apply()

    /** Minutes of grid between full-screen hero interludes. */
    var heroIntervalMinutes: Int
        get() = prefs.getInt(KEY_HERO_INTERVAL_MIN, 5)
        set(value) = prefs.edit().putInt(KEY_HERO_INTERVAL_MIN, value).apply()

    /** Each grid spans several years rather than one afternoon. Does not narrow the library. */
    var curationEraMix: Boolean
        get() = prefs.getBoolean(KEY_CURATION_ERA, true)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_ERA, value).apply()

    /** Photos taken on today's calendar date in past years. Off by default: 1-in-365, and
     *  it can legitimately match nothing — same reasoning as weekdayFilterEnabled. */
    var curationOnThisDay: Boolean
        get() = prefs.getBoolean(KEY_CURATION_OTD, false)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_OTD, value).apply()

    /** Reserves one slot for recent arrivals. This is what makes a newly added photo
     *  visible within minutes rather than once every 33 hours. */
    var curationRecency: Boolean
        get() = prefs.getBoolean(KEY_CURATION_RECENCY, true)
        set(value) = prefs.edit().putBoolean(KEY_CURATION_RECENCY, value).apply()
```

And the matching keys in the `companion object`:
```kotlin
        private const val KEY_COLLAGE = "collage_enabled"
        private const val KEY_COLLAGE_SWAP_MS = "collage_swap_ms"
        private const val KEY_HERO_INTERVAL_MIN = "hero_interval_min"
        private const val KEY_CURATION_ERA = "curation_era_mix"
        private const val KEY_CURATION_OTD = "curation_on_this_day"
        private const val KEY_CURATION_RECENCY = "curation_recency"
```

### 14d–14e. Verify and commit
```bash
./gradlew :app:testDebugUnitTest --console=plain
git commit -am "Add collage and curation preferences"
```

---

# Group 5 — Rendering

## Step 15: Fixed view pool in the layout

**File**: `app/src/main/res/layout/activity_slideshow.xml`

Design §8.4: templates reposition a fixed pool; inflating per template reintroduces the
`CustomTarget` leak the current code fixed by keying on the view.

### 15c. Write implementation

Insert a `FrameLayout` **below** `iv_photo_b` and **above** `vv_video`, containing 12
`ImageView`s (`iv_tile_0a`/`iv_tile_0b` … `iv_tile_5a`/`iv_tile_5b`), each
`layout_width="0dp" layout_height="0dp"`, `scaleType="centerCrop"`, `alpha="0"`. The renderer
sets `layoutParams` per template.

```xml
    <!--
      Fixed pool of 6 slots x A/B, positioned per template at runtime. Never inflated
      per template: Glide's ViewTarget is keyed on the view, so a stable pool is what
      keeps request cancellation working and avoids the CustomTarget leak this project
      already fixed once.

      centerCrop, not fitCenter: a tile must fill its rect. Letterboxing inside a grid
      cell reads as a rendering bug.
    -->
    <FrameLayout
        android:id="@+id/fl_collage"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone">
        <!-- iv_tile_0a … iv_tile_5b -->
    </FrameLayout>
```

### 15d–15e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Add fixed 12-view collage pool to the slideshow layout"
```

---

## Step 16: `CollageRenderer` — geometry and the single driver

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/CollageRenderer.kt` (new)

Design §8.2: one driver Runnable swapping one slot per tick. **Not** six independent timers —
the Handler already double-posted its watchdog, and seven timers staying in phase across
`enterSleep`/`onPause`/`onResume` is a bug farm.

### 16c. Write implementation

Responsibilities:
- `applyTemplate(t: Template, panelW: Int, panelH: Int)` — set `layoutParams` on all 12 views;
  hide views beyond `t.slots.size`.
- A single `tickRunnable` that advances one slot index per `collageTileSwapMs`, crossfading
  that slot's A/B pair.
- `start()` / `stop()` — exactly one `removeCallbacks` site.
- Track `oldestRenderMs` for the watchdog (§8.7): five frozen slots and one healthy one must
  **not** read as healthy.

**Tile loads stay scale-only** (§8.3) — this must be a comment in the code, because the
failure is silent:

```kotlin
// Scale-only, deliberately. Glide only grants Bitmap.Config.HARDWARE when
// isScaleOnlyOrNoTransform() holds, and hardware bitmaps are what keep ~54MB of tiles
// OFF the 256MB Java heap. Adding a rounded-corner or blur transform here moves all of
// it onto the heap silently — no error, no log, no crash until an OOM weeks later.
// centerCrop is scale-only and is fine. Decorative transforms are not.
Glide.with(context)
    .load(photo.file)
    .centerCrop()
    .diskCacheStrategy(DiskCacheStrategy.NONE)
    .dontAnimate()
    .into(target)
```

### 16d–16e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Add CollageRenderer with a single driver runnable"
```

---

## Step 17: Tile-relative Ken Burns

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/CollageRenderer.kt`

**Bug being avoided**: `SlideshowActivity.startKenBurns()` computes drift as
`displayMetrics.widthPixels * 0.02f` ≈ 38 px — screen-relative. At `KEN_BURNS_SCALE = 1.08f`
a 640 px tile has only 25.6 px of overhang, so a 38 px drift exposes a **guaranteed black
wedge every dwell**.

### 17c. Write implementation

```kotlin
/**
 * Drift must come from the slot, not the screen.
 *
 * The full-screen version uses 2% of panel width (~38px on 1920). A 640px tile at
 * KEN_BURNS_SCALE=1.08 has only (1.08-1)/2 * 640 = 25.6px of overhang per side, so a 38px
 * drift slides the bitmap clean off its own edge and exposes background. Scaling the drift
 * to the tile keeps it strictly inside the overhang.
 */
private fun kenBurnsDrift(slotWidthPx: Int): Float =
    slotWidthPx * (KEN_BURNS_SCALE - 1f) / 2f * 0.6f  // 60% of available overhang
```

Guard the whole effect behind `prefs.kenBurnsEnabled`, as the full-screen path does.

### 17d–17e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Scale collage Ken Burns drift to the tile, not the screen"
```

---

## Step 18: Hero interludes and template swap

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt`

### 18c. Write implementation

- Every `heroIntervalMinutes`, fade `fl_collage` out and run the **existing** `showStill()`
  path on `iv_photo_a`/`iv_photo_b` with full-screen Ken Burns.
- On hero completion, call `renderer.applyTemplate(CollageLayout.templateAt(portrait, ++rotation), w, h)`
  **while the grid is still hidden**, then fade back in. §8.4 — the grid never visibly reflows.
- Videos route here and only here (§8.6): a clip plays as a hero, never as a tile.

### 18d–18e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Add hero interludes and swap templates behind them"
```

---

## Step 19: Wire collage into the Activity lifecycle

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt`

### 19c. Write implementation

- In `loadFromDiskThenSync()` and `onLibraryChanged()`: when `prefs.collageEnabled`, start the
  renderer instead of `show(0)`/`scheduleNext()`.
- **Cancellation sites — all three** (§8.7): `enterSleep()`, `onPause()`, `onDestroy()`. v1
  named only `enterSleep`; `onPause` is reachable without sleeping via settings, the Portal
  launcher, or the Assistant.
- `exitSleep()` and `onResume()` restart via the renderer's single guarded entry point.
- `onConfigurationChanged()`: `applyTemplate` from the new orientation's set.
- Watchdog: stall means **oldest** slot's render time exceeds the threshold.
- `prefs.collageEnabled == false` must still take the original single-photo path unchanged.

### 19d–19e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Wire collage renderer into the slideshow lifecycle"
```

---

## Step 20: Per-slot orientation matching replaces the whole-library filter

**File**: `app/src/main/kotlin/com/example/portalgallery/ui/slideshow/SlideshowActivity.kt`

### 20c. Write implementation

In collage mode, pass the **whole** library to `CollageSelector.fill` — not
`PhotoSelector.select`'s orientation-filtered output. Slot tags do the matching now. This is
the change that recovers the 56%.

`PhotoSelector` stays untouched and still governs single-photo mode and hero selection.

### 20d. Verify

```bash
./gradlew :app:testDebugUnitTest --console=plain
```
Then confirm on device in Step 22 that the log line reports the full library in rotation, not 44%.

### 20e. Commit
```bash
git commit -am "Use per-slot orientation matching in collage mode"
```

---

# Group 6 — Settings

## Step 21: Settings toggles

**Files**: `app/src/main/kotlin/com/example/portalgallery/ui/settings/SettingsActivity.kt`,
`app/src/main/res/layout/activity_settings.xml`, `app/src/main/res/values/strings.xml`

### 21c. Write implementation

Following the existing settings rows: switches for **Collage mode**, **Era mix**,
**On this day**, **Recent arrivals**; steppers for tile-swap seconds and hero interval
minutes. Curation rows disable when collage is off.

### 21d–21e. Verify and commit
```bash
./gradlew :app:assembleDebug --console=plain
git commit -am "Add collage and curation settings"
```

---

# Group 7 — End-to-end verification

## Step 22: Verify the whole feature on device

### 22a. Full unit suite
```bash
./gradlew :app:testDebugUnitTest --console=plain
```
All tests green, including the new `CollageLayoutTest` and `CollageSelectorTest`.

### 22b. Lint and build
```bash
./gradlew :app:assembleDebug --console=plain
```
`abortOnError = true`, so lint must be clean.

### 22c. Deploy and observe
```bash
./tools/verify.sh "<your share link>"
```

Confirm, in order:

| # | Check | Why |
|---|---|---|
| 1 | A 6-up grid appears, not a single photo | Collage on by default |
| 2 | Portrait photos are on screen on the landscape panel | The 56% recovery — the headline win |
| 3 | Exactly one tile changes at a time, ~3.3 s apart | Single driver, §8.2 |
| 4 | No black wedge at tile edges during Ken Burns | Step 17 |
| 5 | A hero interlude fires after ~5 min, then the grid returns **with a different template** | §8.4 |
| 6 | Long-press → settings → toggle collage off → single-photo mode returns unchanged | No regression |
| 7 | Cover the camera / wait for quiet hours → panel sleeps; wake → grid resumes, no duplicate timers | §8.7 |

### 22d. Watchdog regression check (Step 1)
```bash
adb logcat -c
# trigger several presence wakes
adb logcat -s PortalGallery:V | grep -c "watchdog"
```
Watchdog log frequency must stay constant across wakes. Before Step 1 it doubled each time.

### 22e. Heap check
```bash
adb shell dumpsys meminfo com.example.portalgallery | grep -E "Java Heap|Graphics"
```
Expect Java Heap ~4 MB with graphics carrying the tiles. **A large Java Heap means hardware
bitmaps were silently lost** — check that no transformation beyond `centerCrop` crept into a
tile load (§8.3).

### 22f. Commit
```bash
git commit -am "Phase 1: collage mode with living wall and hero interludes"
```

---

## Deferred to Phase 2

Not in scope here, and none of it blocks the above: the `snAcKc` probe (§5.2), `AlbumPager`,
`AlbumIndex`, the §5.4 truncated-crawl safety rework (C11), the resident sample (§6), and
migration (§11).
