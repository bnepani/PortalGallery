VERDICT: NEEDS_REVISION

# Design Review — Android Platform (Collage + Full Archive)

**Reviewer lens:** Android platform behaviour, memory, and long-running-process realities
**Design under review:** `docs/plans/2026-09-09-collage-and-full-archive-design.md`
**Target:** Meta Portal+ (`aloha`), Android 9 / API 28, 1920×1080 @ 160 dpi / 60 Hz,
`heapgrowthlimit=256m`, 12.4 GB free on `/data`, no GMS (`docs/portal-device-facts.txt`)
**Date:** 2026-09-09

---

## Summary Assessment

The memory fear in open question #3 is misplaced — twelve ImageViews tiling one panel cost
exactly two screenfuls, not twelve, and `Bitmap.Config.HARDWARE` really is live on API 28 —
but the design is wrong about the two things it asserts most confidently: the resident set is
**not** swapped atomically with respect to the renderer (`AlbumSync.kt:204` prunes *before*
the Activity reloads, which is the exact failure `PhotoStore.kt:8-19` was written to prevent),
and the plan to add seven timers lands on top of a watchdog that already double-posts on every
presence-driven wake. Separately, the 66× growth in library size turns four existing
small-scale shortcuts — a per-file `fsync`, a `listFiles()`/`stat` storm, non-atomic counters
behind the resolution gate, and Glide's default `AUTOMATIC` disk cache — into real defects.

---

## Critical Issues (must fix)

### C1. "Resident set swapped atomically" is not true of the current code. Generational eviction will delete photos the renderer is holding.

§6.3 is the load-bearing claim of the whole storage section:

> the sync computes a **resident set** and swaps it atomically with the index, exactly as
> `saveIndex()` does today. Eviction only ever deletes files **outside** the current resident
> set, so nothing on screen or in the current rotation can vanish underneath the renderer.

Only the first half survives contact with the code. `PhotoStore.saveIndex()`
(`PhotoStore.kt:77-85`) is genuinely atomic — temp, `fd.sync()`, `renameTo`. **The photo bytes
are not part of that swap**, and the ordering is wrong:

| Step | Where | State |
|---|---|---|
| 1 | `AlbumSync.kt:202` `store.saveIndex(entries)` | index on disk = new set |
| 2 | `AlbumSync.kt:204` `store.prune(...)` | **files deleted** |
| 3 | `AlbumSync.kt:206` returns `Result.Success` | |
| 4 | `SlideshowActivity.kt:314` `onLibraryChanged()` | |
| 5 | `SlideshowActivity.kt:330` `store.load()` | Activity finally adopts the new set |

Between step 2 and step 5 the Activity's `photos` list (`SlideshowActivity.kt:98`) still holds
`StoredPhoto` objects whose `File` was deleted at step 2. Today that is harmless *only* because
`prune()` deletes exactly the ids that vanished from the album, and those are rare. The moment
eviction is driven by a **disk cap** rather than album membership — which is the entire point of
§6.3 — `prune()` starts deleting ids that are still in the album and are, with 6 tiles plus a
lookahead plus a hero in flight, plausibly on screen. `show()` → `showStill()` →
`Glide.load(photo.file)` on a deleted file → `onLoadFailed` (`SlideshowActivity.kt:654`) →
`consecutiveFailures++` → forced advance. Six slots × a mass eviction pass and the wall goes
into a failure-driven spin. That is precisely the outcome `PhotoStore.kt:12-16` warns about, and
the design believes it has designed around it.

**Fix:** make the handoff explicit and two-phase. Eviction must not run inside `sync()`. Split
it: `sync()` writes the index and returns the resident set; the Activity reloads and *publishes*
the id set it is now holding; only then does a separate `evict(liveIds)` pass run, and it must
subtract the Activity's live set (current tiles, both A and B of every slot, the prefetch queue,
and the hero) from its delete candidates. The `@Volatile` published-set pattern already used for
`isInForeground` (`SlideshowActivity.kt:84-85`) is the right shape.

Also fix the doc comment at `PhotoStore.kt:101` — "Only safe to call when no pass is in flight"
is currently enforced by nothing but the fact that `refreshLoop()` is a single loop.

### C2. Seven new timers on a Handler that already double-posts its watchdog.

The existing discipline works because every repeating Runnable is a *single stable instance*
(`advanceRunnable` `:115`, `watchdogRunnable` `:120`, `sleepTickRunnable` `:135`) and every
scheduling site pairs a `removeCallbacks` with its `postDelayed`. There is one place where the
pairing is already broken, and the design multiplies it by seven.

**C2a — `watchdogRunnable` double-posts on every presence wake (existing bug).**
`exitSleep()` sets `isAsleep = false` at `:484`, calls `startActivity(REORDER_TO_FRONT)` at
`:498-507`, then posts the watchdog at `:519`. `startActivity` is asynchronous, so `onResume()`
is delivered later on the same Looper — and by then `isAsleep` is `false`, `isPaused` is `false`
and `photos` is non-empty, so `:870` posts the watchdog **a second time**, with no
`removeCallbacks` guarding it. `watchdogRunnable` self-reposts at `:131`, so both copies live
forever. The comment at `:862-863` shows the author already found and fixed this exact class of
bug for `sleepTickRunnable`; the watchdog was missed.

`onPause` (`:851`) removes it, so a pause/resume cycle re-balances — but the presence wake path
(`PresenceService.kt:126-141` → `REORDER_TO_FRONT` onto an already-*paused-not-stopped*
Activity, or `exitSleep()` firing while the Activity is merely paused) does not always route
through a matching `onPause`. On a frame that presence-wakes several times a day for months,
this accumulates self-reposting watchdogs, each able to call `advance()`. Two watchdogs on a
6-slot grid is two forced tile swaps.

**C2b — the design only guards `enterSleep`, not `onPause`.** §8.5 says "`enterSleep()` must
cancel all six tile timers plus the hero timer." Correct, and insufficient. `onPause`
(`:850-851`) is a *separate* cancellation site, and there are onPause-without-enterSleep paths
that matter on this device: long-press → `SettingsActivity` (`:160-163`), Portal's Assistant or
launcher taking foreground, a system dialog. In all of those the frame is awake, so only
`onPause` protects it — and if the tile timers keep firing you get six Glide loads, six decodes
and six gralloc allocations every 3.3 s into an invisible hierarchy, indefinitely.

**C2c — Runnable identity.** `handler.removeCallbacks(r)` matches on object identity. If the
tile timers are posted as per-swap lambdas (`handler.postDelayed({ swapTile(i) }, d)`) they are
**uncancellable** — a fresh object each post. `Handler.postDelayed(Runnable, Object token, long)`
would solve it but only became public API at API 28, and `minSdk = 26`
(`app/build.gradle.kts:32`). Use stable instances, matching the existing idiom:

```kotlin
private val tileRunnables = Array(MAX_SLOTS) { i -> Runnable { swapTile(i) } }
```

**C2d — prefer one driver over six.** Six independent `postDelayed` chains on one Looper do not
stay staggered: each repost is `now + period` measured *after* the handler ran, so decode jitter
accumulates differently per chain and they drift into clumps. Two tiles crossfading in the same
frame means two offscreen layers (`ImageView` does not override `onSetAlpha()`, so an animated
alpha forces `hasOverlappingRendering` → a saveLayer per view) plus two concurrent decodes.
A single Runnable firing every 3.3 s that swaps *one* slot per tick produces the identical
visual, guarantees the stagger, gives you one cancel point instead of seven, and reads like the
existing `advanceRunnable`. Recommend this over six timers.

**C2e — the watchdog gets weaker, not stronger.** §8.5's "`lastRenderedAtMs` becomes 'any tile
rendered recently'" means five dead slots and one live one reads as healthy. It must be
per-slot: `LongArray(MAX_SLOTS)`, stalled if *any* slot exceeds 3× its own dwell, recovery
re-drives that slot rather than calling a global `advance()`.

### C3. Hardware bitmaps are real on this device, but the design has no guard keeping them on — and losing them is silent.

Verified against Glide 4.16.0 sources
(`~/.gradle/caches/.../glide-4.16.0-sources.jar`):

- `HardwareConfigState.HARDWARE_BITMAPS_SUPPORTED = SDK_INT >= P` → **true on API 28.**
- `BLOCK_HARDWARE_BITMAPS_WHEN_GL_CONTEXT_MIGHT_NOT_BE_INITIALIZED = SDK_INT < Q` → **true**, so
  hardware bitmaps are blocked until `FirstFrameWaiter` unblocks them after the first
  `onDraw`. Reached because `showStill` uses `Glide.with(this)` with an Activity
  (`SlideshowActivity.kt:650`). The first load or two is software; everything after is hardware.
- `DecodeJob.getOptionsWithHardwareConfig()` (`DecodeJob.java:507-521`) allows the hardware
  config only when `decodeHelper.isScaleOnlyOrNoTransform()`. `fitCenter`/`centerCrop` set that
  flag (`BaseRequestOptions.java:864-886`), and the layout's
  `android:scaleType="fitCenter"` (`activity_slideshow.xml:23`, `:31`) is what makes Glide apply
  `optionalFitCenter()` at all. So today: hardware. Good.

**The hazard the design creates:** any transformation that is *not* scale-only flips
`isScaleOnlyOrNoTransform` to `false` (`BaseRequestOptions.java:1037`) and every bitmap silently
moves to the Java heap. A 6-up tile grid is exactly the feature where someone adds
`RoundedCorners`, a `MultiTransformation`, a border, or a blurred letterbox fill. There is no
compile error, no log, no crash — just a different number in `dumpsys meminfo`.

Cost of that flip, computed for this panel:

| | Hardware (today) | Software (after one rounded-corner transform) |
|---|---|---|
| Grid A/B pair | 16.6 MB graphics | 16.6 MB **Java heap** |
| Hero A/B pair | 16.6 MB graphics | 16.6 MB **Java heap** |
| Glide memory cache | 16.6 MB graphics | 16.6 MB **Java heap** |
| Glide bitmap pool | ~0 (immutable bitmaps are rejected, `LruBitmapPool.java:110`) | up to 8.3 MB heap, now actually used |
| **Java heap total** | ~4 MB (array pool) | **~58 MB against a 256 MB growth limit** |

Not an instant OOM, but it moves ~54 MB onto a heap that also has to absorb the 20,000-entry
index (C6) and leaves far less headroom for GC pauses on a 60 Hz panel.

**Fix:** state in the design that tile loads are restricted to `fitCenter`/`centerCrop` and
nothing else, and add a debug assertion or a unit test on the `RequestBuilder` options. If
rounded corners are wanted visually, get them from a `ViewOutlineProvider` on the ImageView, not
a Glide transformation — that keeps the hardware config.

**Second, related:** the design also needs `centerCrop`, not the layout's current `fitCenter`.
§8.1's "a full-height portrait slot is 640×1080, so an 800 px source upscales 1.35×" only holds
for a *fill*. With `fitCenter` and a 3:4 source (600×800 after `=w800-h800`), the scale is
`min(640/600, 1080/800) = 1.067` → rendered 640×853 with **227 px of black inside the tile**
(21% of the slot). A collage with black bars inside each cell is not the design intent.
`centerCrop` is also scale-only, so it keeps the hardware config.

### C4. Glide's default cache sizing was computed for one full-screen image. Nothing in this project can change it.

Verified from `MemorySizeCalculator.java`, with `getMemoryClass() == 256` (that value *is*
`dalvik.vm.heapgrowthlimit`, so it comes straight from the facts file):

- `MEMORY_CACHE_TARGET_SCREENS = 2` → memory cache = 1920×1080×4×2 = **16.6 MB**
- `BITMAP_POOL_TARGET_SCREENS = 1` on O+ → pool = **8.3 MB**
- `ARRAY_POOL_SIZE_BYTES = 4 MB`
- `maxSize = 256 MB × 0.4 = 102.4 MB` (or ×0.33 = 84.5 MB if `isLowRamDevice()`), well above the
  24.9 MB target — so the targets apply either way. This conclusion is robust to the unknown
  `ro.config.low_ram`.

A 16.6 MB memory cache holds about **twelve 640×540 tiles — or two heroes**. Every hero
interlude flushes essentially the whole tile cache, and every returning tile is a fresh disk
read and decode. The cache does no useful work at collage scale.

You cannot fix this today: `app/build.gradle.kts:140` declares `glide:4.16.0` with **no KSP/KAPT
plugin and no `glide-compiler`/`ksp` artifact**, so `@GlideModule` on an `AppGlideModule` is
inert. This was C5 in the prior review and was never actioned; the collage is the first feature
that actually needs it.

Two paths, both viable in 4.16:
1. Add `ksp("com.github.bumptech.glide:ksp:4.16.0")` + the KSP plugin and write an
   `AppGlideModule`.
2. The legacy manifest route still works — `ManifestParser` is present and
   `Glide.java:229` still consults it when there is no generated module. Implement the
   deprecated `com.bumptech.glide.module.GlideModule` and declare
   `<meta-data android:name="…PortalGlideModule" android:value="GlideModule"/>`. **If you take
   this route, add a `-keep` rule** — `app/build.gradle.kts:97-98` has `isMinifyEnabled = true`
   and `isShrinkResources = true` for release, and a class referenced only from a manifest
   `meta-data` string will be stripped.

Size the memory cache to at least 4 screens (33 MB) so the grid plus a hero fit, and cut
`bitmapPoolScreens` to ~0 since hardware bitmaps are never pooled.

### C5. Glide is writing a second copy of the library to disk, 25,920 times a day.

`showStill` (`SlideshowActivity.kt:650-684`) never sets a `diskCacheStrategy`, so it runs on
`AUTOMATIC`. Verified from `DiskCacheStrategy.java:128-133`:

```java
return ((isFromAlternateCacheKey && dataSource == DataSource.DATA_DISK_CACHE)
        || dataSource == DataSource.LOCAL)
    && encodeStrategy == EncodeStrategy.TRANSFORMED;
```

`.load(File)` is `DataSource.LOCAL`, and `fitCenter` makes the encode strategy `TRANSFORMED`. So
Glide **re-encodes every displayed photo into its own 250 MB disk LRU** at
`cacheDir/image_manager_disk_cache` — a duplicate of a library that is already the canonical
on-disk copy.

At today's 300 photos / 8 s this is wasteful. At collage rates it is 6 tiles × (86,400 / 20 s) =
**25,920 re-encodes and disk writes per day**, thrashing a 250 MB LRU that can hold ~1% of a
2.5 GB library, on flash that has to last years. Add
`.diskCacheStrategy(DiskCacheStrategy.NONE)` to every tile and hero load. One line; it is pure
loss today.

### C6. The 20,000-file library breaks four assumptions in `PhotoStore`, all of which are on hot paths.

**C6a — `load()` does 40,000 syscalls, and it runs constantly.** `PhotoStore.kt:66-73` does
`f.exists() && f.length() > 0` per entry — two `stat()` calls each. `load()` is invoked at
startup (`SlideshowActivity.kt:251`), on every `onLibraryChanged` (`:330`), and at the top of
every `sync()` (`AlbumSync.kt:81`). Cold page cache, 40,000 stats, several seconds. It is on a
background thread so it is not an ANR, but §4.1's `AlbumIndex` needs to make residency a
*field* reconciled periodically, not a per-load stat storm.

**C6b — Gson at 20,000 entries is not 200–400 ms.** Open question #5's estimate is optimistic.
`indexFile.readText()` allocates a 3 MB String (6 MB as UTF-16) before Gson sees a character,
then reflective deserialization builds 20,000 `Entry` objects plus a 20,000-element `ArrayList`
— a transient peak in the 15 MB range and, on a 2019-era Snapdragon with a cold JIT, realistically
**1.5–3 s**, four-plus times a day. Use `JsonReader` streaming instead of `readText()`, and hold
one parsed `AlbumIndex` in memory rather than re-reading. JSON is still fine; the *pattern* is not.

**C6c — 20,000 `fd.sync()` calls during the initial crawl.** `writePhoto` (`PhotoStore.kt:88-97`)
fsyncs every file. On ext4 `data=ordered` each fsync forces a journal commit — call it 5–30 ms on
this class of storage, so **5–10 minutes of pure fsync** on top of the download, serialized
against every other write on the device, with the flash write-amplification that implies. The
temp-then-rename already gives atomicity; the fsync only adds durability across power loss, and
these are re-downloadable bytes. Drop `fd.sync()` for photos (keep it for `index.json`, which is
the thing that must never be torn). The residual risk — a rename that lands with unflushed data
after a power cut — is already handled: `load()` rejects zero-length files (`:68`) and
`onLoadFailed` (`:654`) handles a corrupt JPEG.

**C6d — flat directory + `listFiles()`.** `prune()` (`:102-106`) and `totalBytes()` (`:108`) each
call `photosDir.listFiles()`, allocating a 20,000-element `File[]` and stat-ing every entry;
`totalBytes()` runs on every successful sync (`AlbumSync.kt:211`). ext4's htree keeps *lookup*
fast, but directory entry blocks are never reclaimed, so months of eviction churn in one
directory leaves a large sparse dir that slows every `readdir`. Shard into 256 subdirectories by
id prefix and track total bytes in the index instead of re-walking the tree.

Note also `docs/portal-device-facts.txt:21` records the device and free space but **not the
filesystem type** — ext4 and f2fs have very different fsync costs. Worth one more
`adb shell mount | grep /data` before finalising C6c.

### C7. `AlbumSync`'s concurrency shortcuts do not survive 66× more items — including the resolution gate.

`AlbumSync.kt:122-124` declares `var done`, `var failures` and `var resolutionChecked` as plain
locals mutated from four concurrent coroutines on `Dispatchers.IO`.

The serious one is the resolution gate. `failures = Int.MAX_VALUE` (`:156`) is used as an *abort
sentinel*, checked at `:165` and `:169`. A concurrent `failures++` at `:145` is a
read-modify-write: it can overwrite the sentinel with a stale value, or increment it to
`Int.MIN_VALUE`. Either way the abort is lost and the sync proceeds to index thumbnails —
defeating the one gate that catches a silent URL-shape change. At 300 items with 75 chunks the
odds are negligible; at 20,000 items with 5,000 chunks they are not. Use `AtomicInteger` /
`AtomicBoolean`.

Two more at this scale:

- **`.chunked(CONCURRENCY)` (`:127`) is a barrier, not a semaphore.** Every group of four waits
  for its slowest member, so total time is `5,000 × E[max of 4 latencies]`, not
  `20,000 × E[latency] / 4`. One download hitting the 60 s `callTimeout` (`:42`) costs the whole
  chunk 60 s. §5.3's "~25 min flat out" assumes a semaphore. Replace with
  `Semaphore(4)` + `map { async { semaphore.withPermit { … } } }.awaitAll()`, or a
  channel-fed worker pool.
- **`download()` (`:273-279`) returns a whole `ByteArray`.** Fine for 90 KB tiles. If videos are
  ever enabled — and `AppPreferences.videoEnabled` is a user-facing toggle, not a compile-time
  constant — `=dv` originals are unbounded, and a single `response.body.bytes()` of a large clip
  is one contiguous Java heap allocation against a 256 MB growth limit. Stream to the temp file
  instead; it also removes the double buffering for photos.
- `onProgress(++done, missing.size)` (`:161`) becomes 20,000 `runOnUiThread` posts
  (`SlideshowActivity.kt:293-297`). Throttle to once a second.

### C8. Per-tile Ken Burns will expose black gaps, using the constants already in the code.

`startKenBurns` (`:758-769`) drifts by `resources.displayMetrics.widthPixels * 0.02f` — a
**screen-relative** constant, 38 px on this panel — and scales by `KEN_BURNS_SCALE = 1.08f`
(`:89`). Applied to a full-screen view, the overhang from a 1.08 scale is 77 px per side, so
38 px of drift is safely covered. Applied to a 640 px-wide tile, the overhang is only
`640 × 0.08 / 2 = 25.6 px` per side against the same 38 px of drift. **The tile slides out from
under its slot and leaves a black wedge on one edge, every dwell, in every slot.** If the drift
is made tile-relative the two constants must still satisfy `scale ≥ 1 + 2 × driftFraction`, and
the design should state that as a rule rather than leaving two magic numbers to be tuned apart.

Two further problems with per-tile Ken Burns:

- **Scaling a child inside a grid overlaps its neighbours** unless each slot is its own clipping
  ViewGroup — six extra ViewGroups, and clipping a scaled child is a clip-rect per slot on every
  frame.
- **`startKenBurns` never resets the view's transform.** Today `prepareIncoming` (`:693-709`)
  resets the incoming view and `settle` (`:716-725`) resets the outgoing one. With twelve views
  that discipline has to be replicated per slot; miss it and `animate().scaleX(1.08f)` from an
  already-1.08 view is a no-op, so tiles quietly freeze mid-zoom. That degrades over months
  without a crash or a log — the worst failure mode for this device.

**Recommendation: Ken Burns on the hero only, off for tiles.** The anti-image-persistence
purpose (`:752-757`) is already served — every tile swaps every 20 s and the whole template
re-lays-out behind each hero, so no pixel is static. Six independently drifting tiles buys
nothing and costs all of the above.

### C9. Template changes must reposition a fixed view pool — never inflate — or the `CustomTarget` leak returns.

Twelve stable `ViewTarget`s are safe: Glide stores the request in the view's tag, and
`into(sameView)` clears the previous request automatically — the comment at
`SlideshowActivity.kt:682-684` and `activity_slideshow.xml:2-11` document exactly this, and it is
the fix for the prior review's C4.

That property depends on **view identity being stable**. §8.3 changes templates behind a hero.
If a template change creates new ImageViews (inflate, `removeAllViews`, or a per-template
layout), each change mints twelve new ViewTargets; the old ones stay in `TargetTracker` unless
`Glide.with(this).clear(oldView)` is called for each, and each `ViewTarget.SizeDeterminer` leaves
an `OnPreDrawListener` on a dead `ViewTreeObserver`. Templates change on every hero interlude —
that is the leak from the prior review, reintroduced at twelve targets per occurrence.

**Fix, and state it in the design:** allocate the twelve ImageViews once in `onCreate` and
implement templates by rewriting `FrameLayout.LayoutParams` (or constraints), never by
inflating. This also makes "hidden behind the hero" nearly free — it is a layout pass, not an
inflate — and it keeps the gralloc allocator seeing a small fixed set of buffer sizes (see S5).

---

## Suggestions

**S1. Prefetch, or the page cache stops helping.** Today's 300 photos × ~300 KB ≈ 90 MB fits in
page cache, so a re-shown photo is a free read. A 2.5 GB working set accessed randomly does not.
Every tile swap becomes a real flash read of ~90 KB — ~2.3 GB/day of random reads, forever.
Glide decodes off the main thread so this is not an ANR, but it makes the 3.3 s cadence
irregular, because the crossfade fires from `incoming.post { … }` (`:678`) whenever the decode
lands. Neither the current code nor the design prefetches anything. Add
`Glide.with(this).load(nextFile).diskCacheStrategy(NONE).preload(w, h)` one dwell ahead per slot;
`PreloadTarget` self-clears, so it does not reintroduce C9.

**S2. Hide the grid during a hero.** HWUI does not cull views occluded by an opaque sibling, so
a full-screen hero costs twelve tile draws underneath it that nobody sees. Set the grid
container `INVISIBLE` once the hero is fully opaque and `VISIBLE` before the reverse crossfade —
one property, removes the whole subtree from the display list.

**S3. Clear the hero views between interludes.** §8.3 reuses `iv_photo_a`/`iv_photo_b`. If they
keep their drawables while the grid is showing, that is 16.6 MB of graphics memory held for
nothing between interludes. `Glide.with(this).clear(binding.ivPhotoA)` on interlude end.

**S4. The real longevity risk is LMK, not OOM.** The Java heap is fine (C3). But the process now
carries ~33 MB of live graphics buffers, plus the TFLite interpreter arena and its
memory-mapped 4.4 MB model (`assets/person_detect.tflite`, `PresenceDetector.kt:140-142`,
`numThreads=2`), plus CameraX `ImageAnalysis` at 640×480 RGBA_8888
(`PresenceDetector.kt:205-213`). All of that is native RSS. When Portal's launcher takes the
foreground, `PresenceService` keeps the process in the foreground-service band
(`PresenceService.kt:86`) — good — but the process is a materially fatter kill candidate than it
was. Worth a `dumpsys meminfo` baseline before and after, and it is the strongest argument for
keeping the tile budget at two screenfuls rather than letting it grow.

**S5. Keep tile bitmap sizes to a small fixed set.** Hardware bitmaps bypass the bitmap pool
entirely (`LruBitmapPool.java:110` rejects immutable bitmaps), so every swap is a fresh gralloc
allocation and a free. At one swap per 3.3 s for months, that is ~800,000 allocate/free cycles a
year. 2019-era gralloc implementations fragment under that if the sizes vary arbitrarily. The
template approach (§8.1) already gives you a handful of fixed slot rectangles — say so
explicitly as a requirement, and do not derive tile sizes from photo aspect ratios.

**S6. FD headroom is fine — but Glide's guard is effectively disabled on P.**
`HardwareConfigState` sets `MAXIMUM_FDS_FOR_HARDWARE_CONFIGS_P = 20000` while its own comment
notes the real per-process limit "is 1024 (at least on O)" and that each hardware bitmap costs
1–2 FDs. Your steady state is ~26 hardware bitmaps ≈ 52 FDs, so there is no problem — but Glide
will not warn you if that ever changes. One `adb shell cat /proc/<pid>/limits` to record the real
`Max open files` alongside the other device facts is cheap.

**S7. Keep the library in `filesDir`.** It is there today (`PhotoStore.kt:22`). The design never
says so. If 2.5 GB ever tempts someone to move it to `cacheDir`, the OS can delete it under
storage pressure and C6 ("the frame never blanks") dies quietly. Write it down as a constraint.

**S8. `onConfigurationChanged` cannot be hidden behind a hero.** §8.3's rule that templates only
change behind an interlude is good, but `onConfigurationChanged` (`:557-572`) currently calls
`show(currentIndex)` unconditionally — which becomes a full six-slot reload with no hero to hide
it. `uiMode` is the config change that actually fires here (the theme is DayNight; the manifest
already absorbs it via `configChanges`). Route config-driven template changes through the same
interlude machinery, or accept the jolt twice a day and say so.

Related: the panel is fixed at rotation 0, 1920×1080 (`docs/portal-device-facts.txt:8-10`), so
the portrait template set will never execute on the target device. Ship it if you like, but do
not spend design effort on it and do not let the unit tests give false confidence about a path
the hardware cannot reach.

**S9. `.tmp` files are counted and listed.** `writePhoto` writes `${name}.tmp` into `photosDir`
(`PhotoStore.kt:90`), so `totalBytes()` counts them and `listFiles()` walks them. `prune()`
happens to delete them (`substringBeforeLast('.')` on `abc.jpg.tmp` yields `abc.jpg`, never an
id). At 20,000 items a crashed crawl can leave thousands. Use a sibling `tmp/` directory.

**S10. `startPresenceIfEnabled()` runs a `startService` on every `onResume` (`:867`).** Harmless
— `onCreate` is not re-entered, so the tick does not double-post — but it is noise in the log on
a device that resumes on every presence event.

---

## Verified Claims

Checked against the repository, the Glide 4.16.0 sources in the local Gradle cache, and
`docs/portal-device-facts.txt`.

| Claim | Status | Evidence |
|---|---|---|
| "HARDWARE allocates off-heap" (open q. #3) | **Correct** | `HardwareConfigState.HARDWARE_BITMAPS_SUPPORTED = SDK_INT >= P`; API 28 is P. Backed by a graphics buffer, not the Dalvik heap. |
| Glide uses hardware bitmaps by default here | **Correct, with a delay** | `BLOCK_HARDWARE_BITMAPS_WHEN_GL_CONTEXT_MIGHT_NOT_BE_INITIALIZED` is true below Q, so `FirstFrameWaiter` unblocks after the first `onDraw`. `Glide.with(Activity)` at `:650` registers it. First load or two is software. |
| Twelve simultaneous ImageViews is a heap risk | **Overstated — this is the good news** | Twelve views *tiling one panel* with A/B pairs is exactly **two screenfuls**: 1920×1080×4×2 = 16.6 MB, independent of slot count or template. Not twelve full-screen bitmaps. |
| Total live footprint for the full design | **~54 MB, ~4 MB of it Java heap** | Grid A/B 16.6 + hero A/B 16.6 (graphics) + Glide memory cache 16.6 (graphics, holds hardware resources) + array pool 4 (heap) + bitmap pool ~0 (immutable bitmaps rejected). No realistic OOM path **while hardware bitmaps hold** — see C3 for the path that breaks it. |
| `getMemoryClass()` on this device | **256** | It returns `dalvik.vm.heapgrowthlimit`, recorded as `256m` at `portal-device-facts.txt:6`. |
| Glide default sizing is unaffected by `isLowRamDevice` | **Correct** | Even at the 0.33 multiplier, `maxSize` is 84.5 MB against a 24.9 MB target, so both cache and pool get their full target. The unrecorded `ro.config.low_ram` does not change the answer. |
| "one tile every 3.3 s" is 6× the render work | **False — it is slightly less** | Today: 10,800 loads/day × 1920×1080 = 22.4 Gpx/day. Collage: 25,920 loads/day × 640×540 = 9.0 Gpx/day plus heroes. Decode throughput goes *down*. The cost moves to I/O (S1), not CPU. |
| CONCURRENCY 4 is "appropriate" | **The number is; the mechanism is not** | 4 parallel fetches is right for a domestic link and matches Glide's own `sourceExecutor` sizing. But `.chunked(4)` is a barrier — see C7. |
| ~2.5 GB budget fits the device | **Correct** | 12,979,284 KB free (`portal-device-facts.txt:21`) → 12.4 GB. 2.5 GB leaves 9.9 GB and takes `/data` from 24% to ~38%. Well clear of any low-storage threshold. 20,000 inodes in a 17 GB ext4 is not a concern. |
| Tile sizing: 800 px source into a 640×540 slot | **Correct** | `DownsampleStrategy.FIT_CENTER` scales to `min(640/600, 540/800)`, so Glide decodes at or below view size — ~1.2–1.4 MB per tile. Slot rectangles, not source size, bound memory. |
| Videos hero-only, never tiles (§8.4) | **Correct and important** | Six `VideoView`s means six `MediaPlayer`s and six SurfaceTextures. `showVideo` (`:595-626`) already special-cases the advance timer; the collage would have to replicate that per slot. Right call. |
| `saveIndex()` is atomic (§6.3) | **Correct for the index** | `PhotoStore.kt:77-85`: temp → `flush()` → `fd.sync()` → `renameTo`. **Not** correct for the photo bytes — see C1. |
| `enterSleep()` cancels animations and video (§8.5) | **Correct today** | `:446-456` removes both timers, cancels both animators, and calls `stopVideo()`. The design correctly identifies this as needing extension — it just stops one site short (C2b). |
| `ViewTarget` fixed the target leak | **Correct** | `:682-684` and `activity_slideshow.xml:2-11`. Holds for twelve targets *provided view identity is stable* — C9. |
| `onDestroy` cleans up all callbacks | **Correct** | `:877` `removeCallbacksAndMessages(null)` is a catch-all and will absorb the new timers without change. |
| Doze / App Standby are irrelevant here | **Still correct** | Mains-powered, screen-on, foreground service. Reaffirming the prior review's S1; nothing in this design changes it. |

---

## What would change my verdict

C1 (a real two-phase handoff before eviction can delete anything), C2 (the watchdog double-post
fixed, `onPause` added to the cancellation set, stable Runnable identities, and preferably one
driver instead of six), C3 (a written rule that tile loads stay scale-only, plus `centerCrop`),
and C9 (a fixed view pool, templates by re-layout) are the four I would block on. C4–C8 are
corrections I would expect to see reflected in the document before implementation starts.

Also worth resolving before anything else, alongside §5.2's RPC probe: three device facts the
design depends on and `portal-device-facts.txt` does not record — `MemTotal`,
`ro.config.low_ram`, and the `/data` filesystem type.
