# PortalGallery — Collage Mode and the Full Archive (v2)

**Date:** 2026-09-09
**Status:** Revised after three-lens review. All three reviewers returned NEEDS_REVISION
against v1; see §0.
**Author:** bnepani (with Claude Code)
**Supersedes:** `2026-09-09-collage-and-full-archive-design.md` (v1)
**Reviews:** `collage-and-full-archive-review-{architecture,android,risk}.md`

---

## 0. Changes from v1

Three reviewers, 25 critical issues. Two findings were raised independently by all three,
which is what drove the largest structural change.

| # | Change | Fixes |
|---|---|---|
| 1 | **§6 deleted.** No tiers, no disk cap, no eviction policy. Replaced by a fixed ~1,500-photo era-stratified **resident sample** at the one already-measured suffix. | arch C-1, C-2, C-4; android C1, C6; risk R-3 |
| 2 | **Crawl completeness folded into `allOk`**, shrink alarm re-based on the previous *index*, page-1 refresh barred from pruning. | risk R-1; arch C-3; risk R-5 |
| 3 | **Work split into two phases.** Phase 1 (collage) does not depend on the RPC. | risk R-7 |
| 4 | **§3.1 rewritten** with the actual request volume stated. | risk R-2; arch S-9 |
| 5 | **One driver Runnable**, not six tile timers. Pre-existing watchdog double-post fixed. | android C2 |
| 6 | **Era-mix specified properly** — bucket→slot mapping, rotation, damping, relax ordering, and a reserved recency slot. | arch C-7 |
| 7 | **Migration section added** (§11). C9 demonstrated on the upgrade path, not asserted. | arch C-4 |
| 8 | **Backoff and rate-limit handling added.** | risk R-4 |
| 9 | **`SharedAlbumParser` refactor budgeted** — v1's "reused, not duplicated" was false. | arch C-5; risk R-6 |
| 10 | Hardware-bitmap rule, Glide disk cache, tile-relative Ken Burns, fixed view pool. | android C3, C4, C5, C8, C9 |

### 0.1 What v1 got right, per review

The §1 diagnosis, the choice of pagination, and probe-as-gate all survived. Risk verified
the central claim with evidence: the fixture declares `'ds:1':{id:'snAcKc', request:[...]}`,
the 226-char token is real at `ds:1 data[2]`, and the prefix `"eptZe":"/_/PhotosUi/"` is in
the markup. The prior review's "no `batchexecute` string" finding was about strings that
were never the point — pagination needs the rpc id and argument tuple, and both are present.
**This design is not repeating the last cycle's mistake.**

### 0.2 Three pre-existing bugs found in shipped code

Not design issues. Worth fixing regardless of whether this ships.

| Bug | Location | Effect |
|---|---|---|
| Watchdog double-posts on every presence wake | `exitSleep()` `:519` posts, then async `startActivity` → `onResume` `:870` posts again with no `removeCallbacks`; self-reposts at `:131` | One extra watchdog loop accumulates per wake, forever |
| Glide re-encodes a second copy of every photo to a 250 MB disk LRU | `DiskCacheStrategy.AUTOMATIC` on a `DataSource.LOCAL` load | Pointless disk churn; ~25,920 writes/day at collage rates. One-line fix: `DiskCacheStrategy.NONE` |
| `failures++` is non-atomic across 4 coroutines | `AlbumSync.kt:145` vs `:156` | A racing increment can overwrite the `Int.MAX_VALUE` abort sentinel, defeating the resolution gate |

---

## 1. Problem

*(Unchanged from v1 — the review called this "the best part of this document." Summarised.)*

Auto-refresh already exists (`SlideshowActivity.kt:277`, six-hourly). The failure is reach,
not scheduling. The share page returns exactly 300 items plus a continuation token; the
frame album `PortalShare` is newest-first, so those 300 were a **three-month window** (May 3
→ Aug 10) over an archive of **~20,000 photos spanning Apr 2019 – Aug 2026**.

- The frame draws from **1.5%** of the archive.
- `prune()` (`AlbumSync.kt:204`) deletes anything outside that window on each successful
  sync, so the library is a *rolling* three months.
- **~19,700 photos can never appear.**
- Ordering is by **capture** date, so a photo added today but taken in 2019 sorts to
  position ~19,000 and never arrives (§1.2 in v1).
- `PhotoSelector`'s orientation filter then discards 56% of what was downloaded (168
  portrait / 132 landscape measured), leaving ~132 photos in rotation on a landscape panel.

---

## 2. Constraints

| | Constraint |
|---|---|
| C6 | **The frame never blanks.** |
| C7 | **No blocking network call in the Activity.** |
| C8 | **A failed sync never reduces what is on disk.** |
| C9 | **Day one is strictly better than today, never worse.** |
| C10 | **No slot ever renders empty.** |
| C11 | *(new)* **A silently truncated crawl must never delete photos.** Added because review found this is the single most likely way the project fails. |

---

## 3. Decisions

| Decision | Choice |
|---|---|
| Reach | Paginate the `snAcKc` RPC |
| Residency | **Fixed ~1,500-photo era-stratified sample**, re-rolled weekly |
| Presentation | Living wall + hero interludes |
| Curation | Era mix, on-this-day, recency — independently toggleable |
| Phasing | **Phase 1 collage (no RPC) → Phase 2 pagination** |

### 3.1 Terms of Service — restated with the number

v1 quoted `design-review-risk.md:179` and answered with a decision rather than the analysis
that line asks for. It also never engaged the stronger passage at `:84-86`, which calls the
RPC *"a much stronger ToS problem than fetching a page."*

**The quantitative change, stated plainly.** The prior risk review's strongest defence was
volumetric: *"4 requests/day, one device, one URL"* (`:170-172`) — roughly **1,460
requests/year**. This design replaces that with:

| | Requests |
|---|---|
| Initial crawl | ~67, once |
| Steady-state refresh (§5.3), 4×/day | ~1,460/year |
| Weekly full re-crawl | 67 × 52 ≈ **3,484/year** |
| **Total** | **~5,000/year**, versus ~1,460 today |

A ~3.4× increase in request volume, and — more relevantly — a shift from a documented
public page fetch to an **undocumented internal RPC**. That is a different kind of access,
not just more of the same.

**Accepted**, explicitly, for a personal single-household read-only device. The technical
consequence is bounded by C8/C11: a broken RPC must degrade to *no new photos*, never to
data loss. v1 asserted that; §5.4 now actually enforces it.

---

## 4. Architecture

**The index is complete; the bytes are a bounded sample.**

- **Index:** all ~20,000 items — id, base URL, dimensions, capture time, video flag, source
  album. Review measured the realistic size at **~6.6 MB**, not v1's 3 MB. Stays JSON until
  a measurement says otherwise; parsing happens once per sync on IO.
- **Resident sample:** ~1,500 photos on disk at the existing full-size suffix. Curation
  always sees all 20,000 and substitutes a resident photo when its ideal pick is absent.

### 4.1 Components

| Component | Status | Role |
|---|---|---|
| `data/album/AlbumPager` | new | `snAcKc` RPC envelope: `)]}'` prefix, chunk framing, token |
| `data/store/AlbumIndex` | new | Metadata for all ~20,000 items |
| `data/schedule/CollageSelector` | new | Pure Kotlin slot filling |
| `ui/slideshow/CollageLayout` | new | Templates: slot rects + orientation tags |
| `SharedAlbumParser` | **refactor** | Extract a `JsonArray → List<Photo>` seam (§5.5); add album-id/share-key extraction |
| `PhotoStore` | extend | Add `baseUrl` to `Entry`; resident-set manifest; generation marks |
| `AlbumSync` | rework | Index refresh → sample selection → byte fetch |
| `PhotoSelector` | extend | Per-slot orientation matching |

---

## 5. Pagination

### 5.1 The RPC

`AF_dataServiceRequests` declares `ds:1` as `snAcKc` with tuple
`[albumId, null, null, shareKey]`; the 226-char continuation token is at `data[2]`. Page 2
is the same call with the token in the slot currently holding `null`, POSTed to
`photos.google.com/_/PhotosUi/data/batchexecute`. The path prefix `/_/PhotosUi/` is in the
markup as `"eptZe"`; only the `data/batchexecute` suffix is convention.

Response unwrapping: strip `)]}'`, walk length-prefixed chunks, parse the inner payload —
which is the same `ds:1` array shape the HTML path already decodes (§5.5).

### 5.2 Step 1 is a probe, and it is a gate

A throwaway Python probe confirming page 2 returns entries plus a fresh token. The prior
review had static evidence only — its sandbox blocked the POST. **If the probe fails, Phase
2 stops; Phase 1 is unaffected** (§10).

On success, commit a scrubbed golden fixture. **Note:** `tools/scrub_fixture.py` changes
byte length, which is fatal for a length-prefixed RPC fixture, and its leak check is vacuous
by construction. It needs a length-preserving mode before it can scrub an RPC response.

### 5.3 Crawl strategy

- **First crawl:** ~67 pages, throttled to ~1/second.
- **Incremental (4×/day):** page 1 only. **This mode may never prune** — see §5.4. It can
  only *add*.
- **Weekly:** full re-crawl. The only mode permitted to prune.

v1 claimed steady state is "a single request" as though that were sufficient. It is not:
ordering is by capture date, so a back-dated photo (§1) never appears on page 1, and the
newest-first ordering is itself *"not proven"* and user-controllable per the older design's
§9a. Page-1 refresh is an optimisation for the common case; the weekly re-crawl is what
guarantees correctness.

### 5.4 Failure posture — this is the part v1 got wrong

Review's verdict on the most likely failure: the crawl works, then quietly stops working. A
soft rate limit returns a clean, token-less page 40 of 67. Every existing guard passes —
HTTP 200, `allOk == true`, `12,000 > 20,000 × 0.5` — and `prune()` deletes 8,000 photos
silently.

Four changes, all required (C11):

1. **Crawl completeness joins `allOk`.** `AlbumSync.kt:113` computes `allOk` from `.ok`
   alone and deliberately ignores the existing `truncated` field. A crawl is complete only
   when it terminated on a **missing token**, not on an error, a cap, or a timeout. Anything
   else is `truncated`, and `truncated ⇒ no prune`.
2. **Shrink alarm re-based on the previous index.** `SHRINK_ALARM` currently compares
   against `store.load()`, which returns only files on disk (`PhotoStore.kt:66-73`). With a
   20,000-item index and 1,500 resident, it can *never* fire. It must compare index-to-index.
3. **Tighten the threshold.** `0.5` was calibrated for a bimodal 300-or-0 outcome.
   Pagination makes failure continuous, so every crawl reaching pages 34–66 passes. Against
   an index, a legitimate change is a fraction of a percent — **0.98** is the right order,
   with the previous index retained on trip.
4. **Page count sanity.** Record the previous crawl's page count; a crawl ending materially
   short is truncated regardless of tokens.

### 5.5 The `SharedAlbumParser` refactor is real work

v1 claimed entry decoding is "reused, not duplicated." **False.** `structured()` is private
and anchored on the HTML string `key: 'ds:1'` via `extractDsRaw()`; there is no
`JsonArray → List<Photo>` seam. Extracting one — and keeping the golden tests green across
both callers — is a real change to the most safety-critical file in the project, and should
be budgeted as such rather than assumed free.

### 5.6 Rate limits and backoff

Absent from v1. A 67-page crawl at 1/s against an undocumented endpoint will eventually meet
a 429 or an interstitial.

- Exponential backoff with jitter on 429/5xx; abandon the crawl after N failures and mark it
  **truncated** (which by §5.4 means no prune).
- Detect a sign-in or consent interstitial the way `AlbumUrl.isSignInRedirect` already does —
  by where the request landed, not by body content.
- **Discard-and-retry amplifies under backpressure.** A discarded 40-page crawl that retries
  immediately doubles load exactly when the server is pushing back. Retry no sooner than the
  next scheduled sync.

---

## 6. Residency

*v1's two-tier storage with disk-cap eviction is deleted.* Review found the budget did not
close (90 KB/tile was below the floor implied by the project's own paired measurements; the
fitted value is ~125 KB, making tier 1 ~2.5 GB against a 2.5 GB budget), and that the
eviction scheme's core invariant was false. The simpler design removes both problems.

### 6.1 A fixed resident sample

**~1,500 photos, era-stratified, at the existing full-size suffix** — the one size that has
actually been measured (301 KB mean). 1,500 × 301 KB ≈ **452 MB** of 12.4 GB free.
Configurable; ~3,000 (≈900 MB) is still comfortable.

Consequences:

- **No tiers.** Tiles render by letting Glide downsample the full-size file to the slot —
  which is what it already does today.
- **Heroes are free.** Every resident photo is hero-quality, so v1's separate 400-photo pool
  and its ~480 MB/day of re-download churn both disappear.
- **`=w800-h800` is gone**, and with it R-3: v1's tile suffix collided with
  `MIN_PLAUSIBLE_EDGE = 800` (`AlbumSync.kt:30`) at exactly zero margin, and any sub-800px
  source — a scan, a screenshot — would have tripped `isPlausiblePhoto()` and aborted the
  entire sync.
- **Backfill is ~10 minutes**, not hours. This is what actually delivers C9.
- `PhotoStore`'s *"deliberately not an LRU cache"* comment survives intact. Still true, just
  at a different N.

### 6.2 Re-rolling the sample safely

The sample is re-rolled on the **weekly** re-crawl only, keeping the intersection so most
bytes are reused. Typical churn is a few hundred photos.

**Deletion uses a two-generation grace, not an atomic swap.** v1 claimed the resident set is
"swapped atomically"; review showed there are really two sets — the sync's, written on IO at
`AlbumSync.kt:202-204`, and the renderer's, refreshed only in `onLibraryChanged()` *after*
`sync()` returns (`SlideshowActivity.kt:314`). A photo the renderer is currently displaying
across six slots is legally deletable under v1's rule.

The fix needs no cross-thread handshake:

> Each file carries the generation at which it was last resident. A file is deleted only
> when it has been non-resident for **two consecutive generations**. Since the renderer
> adopts a new set within seconds of a sync, one full generation (a week) of grace is
> overwhelming.

This is genuinely generational, unlike v1's version, and it is trivially testable.

---

## 7. Curation

`CollageSelector`: pure Kotlin, no Android dependencies, sibling of `PhotoSelector`.

### 7.1 Era mix, specified

v1 said "roughly one slot per year, oldest buckets sharing," which review correctly called
underspecified in four ways.

**Buckets exceed slots.** Eight years, six slots. Buckets rotate on a deterministic cycle
across successive grids, so every era appears regularly and none can be permanently
invisible. v1's "sharing" would have left two unspecified years absent from every grid.

**Stratification is damped, not uniform — and this is a stated product choice.** The archive
is not uniform; a family album accumulates, so 2026 may hold 6,000 photos and 2019 400.
Strict one-slot-per-year would give 2019 16.7% of screen time for 2% of the archive, making
a 2019 photo recur ~15× as often as a 2026 one. Slot allocation is therefore weighted by
**√(bucket size)**, which keeps old years clearly visible without making them dominant.

**Single-bucket case.** With one bucket, era mix is a no-op. v1's claim that it makes "six
photos from one afternoon" *physically* impossible is false; the guarantee is
"where the archive spans eras, every grid spans eras."

**Template-relative.** §8.1's templates have 3, 5 and 6 slots. Stratification is expressed
as a proportion of available slots, not hard-coded to six.

### 7.2 Requirement (a) gets a reserved slot

Review did arithmetic v1 should have. Six swaps per 20 s = 1,080/hour; with era mix on,
~180/hour reach the 2026 bucket against ~6,000 candidates, so a specific new photo surfaces
about **once per 33 hours**. Today, 300 photos at 8 s = 450 renders/hour, so each surfaces
every ~40 minutes. **Era-mix-by-default would make a new photo ~50× less visible than it is
today** — a direct regression of the headline requirement.

Fix: when recency is on, **one slot is reserved for recent arrivals** and excluded from era
stratification. A photo added today appears within minutes and stays prominent for its first
weeks. This composes cleanly and makes requirement (a) structural rather than probabilistic.

### 7.3 Relax ordering, written down

Three orthogonal constraints per slot — era × orientation × residency — plus a grid-wide
no-duplicate rule. Greedy per-slot assignment with independent cascades can starve the last
slot, so the order is explicit:

1. **Residency** is hard. Never render a photo whose bytes are absent.
2. **Orientation** relaxes *last*. §1.3 and §8.1 rest on slot tags recovering the 56%; a
   landscape photo stretched 1.8× into a 640×1080 portrait slot defeats the entire point.
3. **Era relaxes first**, before orientation.

This resolves a contradiction in v1, which claimed era mix was "structural, not a weight"
while also claiming orientation tags recover the 56% — mutually exclusive when a bucket's
only resident photo is the wrong shape.

Slot filling is a small constraint-satisfaction problem; the implementation assigns
most-constrained-slot-first rather than left-to-right.

### 7.4 Defaults

| Mode | Default | Why |
|---|---|---|
| Era mix | on | Does not narrow the library; §7.2's reserved slot protects requirement (a) |
| Recency | on | Directly serves requirement (a) |
| On this day | off | 1-in-365; can legitimately match nothing. Matches `weekdayFilterEnabled` convention |

The on-this-day pre-fetch v1 specified is deferred — v1 specified overnight pre-fetching for
a mode that is off by default.

---

## 8. Rendering

### 8.1 Templates

5–6 hand-designed templates for 1920×1080, each declaring slot rectangles tagged
portrait/landscape: a 3×2 uniform grid (640×540), three full-height portrait columns
(640×1080), a 960×1080 hero-left with a 2×2 beside it, and two mixed arrangements. Templates
are data, so a unit test can assert every slot is filled and none is degenerate. **Slot
orientation tags are the mechanism that recovers the 56%.**

### 8.2 One driver, not six timers

v1 proposed six independent staggered tile timers plus a hero timer. Review's objection is
decisive: the Handler **already** double-posts its watchdog (§0.2), and v1's §8.5 guarded
only `enterSleep()` while `onPause` (`:850-851`) is a second cancellation site — reachable
without sleeping, via settings, the Portal launcher, or the Assistant.

**One driver Runnable swaps one slot per 3.3 s tick.** Visually identical, stagger
guaranteed by construction rather than by seven timers staying in phase, and exactly one
cancel point. Runnable identities stay stable so `removeCallbacks` works.

### 8.3 Memory — the fear was misplaced, the mechanism is fragile

Review's measurement: twelve ImageViews tiling one panel with A/B pairs cost **two
screenfuls (16.6 MB)**, not twelve full-screen bitmaps. Total live footprint ~54 MB, of
which only ~4 MB is on the Java heap. `Bitmap.Config.HARDWARE` is genuinely available on
API 28.

But it is conditional: Glide's `DecodeJob.getOptionsWithHardwareConfig()` allows hardware
bitmaps only when `isScaleOnlyOrNoTransform()`. **The first rounded-corner or blur
transformation anyone adds to a tile moves ~54 MB onto the 256 MB heap — silently, with no
error and no log.**

So this is a written rule, not an assumption: **tile loads stay scale-only.** `centerCrop`
is permitted; decorative transformations are not. Worth a comment in the loading code, since
the failure is invisible.

### 8.4 Hero interludes and template changes

Heroes reuse `iv_photo_a`/`iv_photo_b` above the grid with the existing `Transition` and
`startKenBurns()`. **Template changes happen only behind a hero**, concealed by the
full-screen photo, so the grid never visibly reflows.

**Templates reposition a fixed view pool — never inflate.** Inflating per template
reintroduces the `CustomTarget` leak the current code fixed by keying on the view.

### 8.5 Ken Burns must be tile-relative

`startKenBurns()` computes drift as `displayMetrics.widthPixels * 0.02f` ≈ 38 px — screen-
relative. At `KEN_BURNS_SCALE = 1.08f` a 640 px tile has only 25.6 px of overhang, so a 38 px
drift exposes **a guaranteed black wedge every dwell**. Drift must be computed from the slot,
not the screen.

### 8.6 Videos are hero-only

Six `VideoView`s in a grid is heavy, and a clip in one corner is distracting.

### 8.7 Lifecycle

Cancellation sites: `enterSleep()`, `onPause()`, `onDestroy()`. The watchdog's
`lastRenderedAtMs` becomes "any slot rendered recently" — but note review's point that this
weakens the signal, since five frozen slots and one healthy one reads as healthy. Track the
oldest slot's render time instead.

---

## 9. Testing

| Test | Guards |
|---|---|
| `AlbumPager` against a committed RPC fixture | `)]}'`, chunk framing, token extraction |
| Album-id / share-key extraction | The request tuple we send |
| **Truncated crawl does not prune** | **C11 — the highest-value test here** |
| Shrink alarm against index, not disk | §5.4(2) |
| `CollageSelector` — toggles × template × bucket distribution × residency | C10; v1's "eight toggle combinations" did not cover the real state space |
| On-this-day matching nothing | Relax cascade |
| Starved last slot | §7.3 ordering |
| `CollageLayout` — every template | Slots filled, no degenerate rect |
| Two-generation grace | Never deletes a file the renderer holds |
| Migration from a v1 install | §11 |

---

## 10. Phasing

v1 had none, so a probe failure would have taken down the collage, the templates and the 56%
orientation recovery — none of which need the RPC.

**Phase 1 — collage, no RPC.** Templates, living wall, hero interludes, `CollageSelector`,
per-slot orientation matching, the three curation modes over the existing ~300, and the
three pre-existing bug fixes in §0.2. Ships standalone. Recovers the 56% immediately.

**Phase 2 — reach.** The probe (§5.2), `AlbumPager`, `AlbumIndex`, the §5.4 safety rework,
the resident sample. Gated on the probe.

Phase 1 delivers most of the visible value and is unaffected if Phase 2 proves impossible.

---

## 11. Migration

Absent from v1, which asserted C9 rather than demonstrating it.

On first run after upgrade the device holds an `index.json` of ~300 `Entry` records with no
`baseUrl`, and ~300 files at the old suffix.

1. **Existing photos are adopted, not re-downloaded.** The old index deserialises — `Entry`
   gains `baseUrl` with a default, exactly as `isVideo` and `captureMs` did before it.
2. **A missing `baseUrl` means "resident but unindexed."** The photo displays normally; the
   field is backfilled at the first successful crawl.
3. **No eviction until the first successful full crawl.** Until the index is known good,
   nothing is deleted — so a failed upgrade cannot shrink the library.
4. **Phase 1 alone changes nothing on disk.** Collage renders from the existing store.

C9 therefore holds on the upgrade path: the frame renders its existing photos immediately,
in collage form, before any network call.

---

## 11a. Empirical verification, 2026-09-10

The previous design cycle left one assumption explicitly untested
(`2026-08-17-…-design.md` §9a): *"Not yet tested: adding a photo to this album and
confirming it arrives. Every photo here was added in one batch on 2026-08-17, so the
added-timestamp column cannot distinguish. The direction evidence is strong but
indirect."*

**Now tested directly.** A photo was added to `PortalShare` and the share page re-fetched
within minutes.

| | Before (2026-09-09) | After (2026-09-10) |
|---|---|---|
| Album title | `Apr 27, 2019 – Aug 10, 2026` | `Apr 27, 2019 – Sep 1, 2026` |
| Capture span of visible 300 | 2026-05-03 .. 2026-08-10 | 2026-05-16 .. 2026-09-01 |
| Added span | 2026-08-17 .. 2026-08-17 | 2026-08-17 .. **2026-09-10** |
| Newest item age | 23 days | **0 days** |
| Inversions | 294/299 | 296/299 |

Three things are now facts rather than inferences:

1. **The page serves the true newest.** A photo added today appears immediately, at the
   head. §9a's "strong but indirect" direction evidence is confirmed.
2. **Auto-add into the album works.** The 23-day gap seen on 2026-09-09 was simply a quiet
   album, not a broken sharing rule. Worth recording, because a static `added` column looks
   identical to a broken feed.
3. **The window genuinely rolls, and rolls photos out.** The oldest visible capture moved
   forward from 05-03 to 05-16 as new items entered the 300. This is direct observation of
   the §1 diagnosis: photos leave the visible set, and `prune()` deletes them from disk. The
   rolling-window data loss is measured, not theorised.

**Consequence for phasing.** The "new photos should appear" half of the requirement is
reachable with the *existing* six-hourly `refreshLoop()` — no RPC needed. What Phase 2
pagination buys is exclusively the ~19,700 photos from 2019 to spring 2026 that the
rolling window has never been able to reach, plus protection against `prune()` deleting
photos as they roll off.

### Portrait ratio, measured

Sampled twice over two different windows: **100 portrait / 200 landscape, 33.3%**, stable
across both. This corrects the 56% figure inherited from `album-fixture.html`, which is the
`Arizona 2026` trip album and not the frame's album. Portrait is the *minority* here, which
inverts which templates exhaust their supply first — see the KDoc on `CollageLayout`.

---

## 11b. Phase 1 on-device verification, 2026-09-10

Ran on the actual Portal+ (`818PGA02P080BM31`, aloha, API 28), installed over the
existing data so the upgrade path was exercised rather than a clean first run. ~15 minutes
of live operation, **zero crashes**.

This matters more than a usual smoke test: nothing from the renderer onward is reachable
from the 99 unit tests, and neither are the two `AlbumSync` fixes. Until this run, all of
it was compile-and-reason only.

| Check | Result |
|---|---|
| Orientation recovery, landscape | `194 -> 293` photos in rotation (+51%) |
| Orientation recovery, portrait | `99 -> 293` (~3x) |
| Hardware bitmaps retained | Java Heap **6.6–7.4 MB**, Graphics **56–66 MB** |
| Wrapper clipping (§8.3) | No wedges, seams or displaced tile boundaries |
| Rotation handling | Clean switch to the portrait template set and back |
| Sleep/wake cycle | Java Heap 7,384 KB before and after — no leak |
| Upgrade path (§11) | Pre-`addedMs` index loaded and rendered without a re-sync |

**The memory figures were the real unknown.** The ~16.6 MB / ~54 MB / ~4 MB numbers came
from a review's calculation and had never been measured; the agent that used them flagged
them as restated rather than reproduced. Measured: bitmaps sit in Graphics, not on the Java
heap, so `Bitmap.Config.HARDWARE` is being granted and the scale-only rule in
`CollageRenderer` is doing its job. The estimates hold.

**The 33% portrait correction predicted real behaviour.** The 99 photos the frame recovers
in landscape are exactly the measured portrait share. Had the inherited 56% figure been
believed, the expected recovery would have been nearly double the truth.

**New photos reach the frame unaided.** 12 photos added to the album were downloaded by the
existing six-hourly refresh before this build was installed, and 12 rolled-off photos were
pruned — which is why the on-disk count stayed at 293 while the index's capture span
advanced to 2026-09-01. End-to-end confirmation of the requirement in §1, on hardware.

### Not verified — carried forward

- **Hero interludes and the behind-the-hero template swap** (§8.4). Cut short; untested.
- **`collageEnabled = false` fallback.** Needs interaction with the settings screen.
- **Watchdog per-slot retry** (§8.7). Nothing stalled, so the path never ran.
- **The `AlbumSync` abort-path cleanup** (§0.2 / Fix A). Only reachable when the parser
  degrades, which cannot be triggered on demand. Still untested by anything.

---

## 11c. The §5.2 probe — PASSED, with two corrections, 2026-09-10

Executed against the live album. **Phase 2 is go.**

```
pages fetched : 67
unique items  : 19,974
truncated     : False        (the token ran out on its own)
capture span  : 2019-04-27 .. 2026-09-01
wire bytes    : 11.8 MB      elapsed 120s at 1 request/second
```

The whole archive is reachable. Every structural prediction in §5.1 held: `ds:1` declares
`snAcKc`, the tuple is `[albumId, null, null, shareKey]`, the 226-char token sits at
`ds:1 data[2]`, and the path prefix is `/_/PhotosUi/`. Response entries have the identical
shape to the HTML path — `len=10`, media sub-array `len=12`, dimensions present — so §5.5's
plan to reuse the decoder holds.

### Correction 1 — the token goes in slot 1, not slot 2

§5.1 said *"the request tuple has a `null` in exactly the slot a token would occupy."*
There are two nulls, and the design picked the wrong one.

| Args | Result |
|---|---|
| `[id, null, token, key]` | 300 entries, **0 new** — silently returns page 1 again |
| `[id, token, null, key]` | 300 entries, **300 new** — correct |

The failure mode matters more than the fix: the wrong slot returns HTTP 200, a
well-formed 300-entry payload, and **a fresh continuation token every time**. Five pages
in, a crawler would report 1,500 items fetched and hold 300 unique ones. Nothing in the
transport layer can detect this — only comparing ids across pages can. `AlbumPager` needs
a test that asserts page 2 contains ids absent from page 1, not merely that page 2 parses.

### Correction 2 — the chunk length prefix cannot be trusted

§5.1's "walk the length-prefixed chunk framing" does not survive contact. The declared
length is in **bytes** while the payload is text, and it is off by one against the JSON it
introduces:

```
chunk 1: declared=190623  actual JSON ends at 190622
```

Slicing by the declared length fails to parse. `AlbumPager` should ignore the counts and
walk with an incremental JSON decoder (`raw_decode` and its equivalents), taking each
value's true end offset. That is also what makes the parse robust if Google adjusts the
framing.

### The year distribution — and §7.1's rationale is backwards

§12 asked for the real bucket distribution. It is:

| 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|
| 3,657 | 3,843 | 3,280 | 2,916 | 1,297 | 3,189 | 1,134 | 658 |

**The opposite of what §7.1 assumed.** That section justifies sqrt damping with *"a family
album accumulates, so 2026 may hold 6,000 photos and 2019 four hundred"* — but 2019 is the
second-largest year at 18% of the archive and 2026 is the smallest at 3.3%.

The mechanism survives; only the story is wrong. Damping still moderates between
proportional and uniform, and here it works in the opposite direction from the one
described: it lifts 2026 from a 3.3% proportional share to 6.7%, which is the desirable
outcome for the newest photos. But the KDoc on `CollageSelector.bucketWeights` states the
reasoning with invented numbers and must be rewritten against these measured ones.

---

## 11d. Phase 2 build status, 2026-09-10

Everything is implemented and unit-tested. **Nothing has run on the device yet** — the
Portal went offline before the deploy, so the whole of Phase 2 is compile-and-reason plus
149 unit tests, exactly the position Phase 1 was in before its own device run.

| Piece | State |
|---|---|
| §5.2 probe | ✅ passed — 19,974 items, 67 pages, 2 design corrections |
| `AlbumPager` | ✅ 11 tests over two committed response captures |
| `AlbumIndex` | ✅ 11 tests; 20,000 entries = 4.0 MB, 60 ms read |
| `CrawlGuard` (C11) | ✅ 15 tests, including the reviewers' doomsday scenario |
| `ResidentSelector` | ✅ 13 tests |
| `AlbumSync` rework | ✅ compiles, wired, untested on hardware |
| Migration (§11) | ✅ no special path needed — see below |
| On-device verification | ✅ **passed** — see §11e |

### Migration needs no code

§11 anticipated an upgrade path. There isn't one to write, and the reason is worth
recording: on first run the index is simply absent, `CrawlGuard` returns
`Accept(mayPrune = true)` for a first complete crawl, and the resident sample keeps ~1,500
items where the old prune kept only the visible 300. **The upgrade strictly increases what
survives**, so C9 holds without special handling. `PhotoStore.Entry` is untouched, so the
existing index deserialises as it always did.

### Two deliberate departures from this design

1. **An incomplete crawl is merged, not discarded** (§5.4 said discard). The guard has
   already forbidden pruning, so add-only merging protects identically and is strictly more
   useful — an album that reliably times out at page 40 would otherwise never contribute
   another photograph.
2. **Pruning runs before indexing.** The other order writes a store index listing files the
   prune then deletes. `PhotoStore.load()` filters missing files so it self-heals, but a
   sync reporting a total it has just invalidated makes a later bug harder to read.

### What the device run established — see §11e for results

The list below is what it was asked to prove. All of it passed, after three
device-only bugs were found and fixed.

- A 67-page crawl against the live album from the app, not from a Python script.
- The first-run download of ~1,200 new photos (~360 MB) and how long it actually takes.
- That the collage draws from the full archive rather than the recent window — the point
  of the whole phase.
- Memory and stability with a 20,000-entry index loaded each sync.
- That `mayPrune` behaves on a real partial failure, which no unit test can stage.

---

## 12. Open questions

- [ ] **Does the `snAcKc` RPC work?** Gate on §5.2.
- [ ] Is the continuation token stable across syncs, or does it expire? Determines whether
      incremental refresh can resume from a cursor.
- [ ] Does the request envelope need the `bl` build-label / `at` token that batchexecute
      calls usually carry, and does it rotate? Review flagged this as under-documented in §5.1.
- [ ] Three device facts `portal-device-facts.txt` does not record and this design leans on:
      `MemTotal`, `ro.config.low_ram`, and the `/data` filesystem type.
- [x] `AlbumIndex` at ~6.6 MB: JSON or SQLite? **Measured — JSON, comfortably.** 20,000
      entries is **4.0 MB**, write 64 ms, read 60 ms (`AlbumIndexTest`). The 6.6 MB
      estimate was high, and at 60 ms once per sync on a background thread there is no
      case for a database.
- [ ] Actual bucket distribution of the 20,000 — the √ damping in §7.1 is calibrated on an
      assumption. The first successful crawl answers it.

---

## 11e. Phase 2 on-device verification, 2026-09-10

Ran on the Portal+ over the existing install. **Phase 2 works.**

```
Na5Li2hwZPuU: 19974 items across 67 page(s)
crawl accepted (prune=true): first complete crawl, 19974 items
sync: 19974 in album, 1500 resident, 1086 to download
sync ok: 1500 on disk of 19974 indexed, 498MB from 1/1 albums
```

| | Before Phase 2 | After |
|---|---|---|
| Photos on disk | 293 | **1,500** |
| Items indexed | — | **19,974** |
| Capture span on disk | 2026-05 .. 2026-09 (3 months) | **2019-04-27 .. 2026-09-01** |
| Per-year on disk | one bucket | 194 / 193 / 183 / 172 / 111 / 178 / 107 / 362 |
| Library size | 109 MB | 498 MB of 12.6 GB free |
| Portrait split | — | 472 / 1028 (31.5%, matching the 33% measurement) |

Crawl 2m18s; download of 1,086 photos ~8 min. `album_index.json` is **7.5 MB** for 19,974
entries — higher than the 4.0 MB the synthetic test predicted, because real base URLs are
longer than the test's. Still trivial, and the JSON decision stands.

The 2026 bucket holds 362 against ~110–195 for every other year, which is `PIN_NEWEST`
working as intended: the newest 300 are unconditionally resident, and era stratification
distributes the rest.

### Three device-only bugs, none of which the JVM could catch

1. **Android's regex engine rejected the patterns.** ICU treats a bare `}` as a syntax
   error where the JVM accepts it as a literal, so `AlbumPager` threw
   `PatternSyntaxException` at class-init and crashed the app on launch — with all 149
   unit tests green, because those run on the JVM.
2. **The end-of-album marker is an empty string.** This cost two runs. The Python probe
   looped on `while tok:`, which stops on `""` because Python treats it as falsy; the same
   logic written as `token != null` runs past the end of the album. Also hardened against
   a non-string marker, since `isJsonPrimitive` accepts a number and `asString` renders
   `0` as a usable-looking cursor.
3. **No no-progress guard.** Added: a page adding zero new ids means the cursor is not
   advancing, since pages are disjoint by construction.

### C11 worked before anyone knew there was a bug

The most valuable result of the run. Bug 2 sent the first crawl to the 400-page ceiling.
Every layer behaved:

```
hit the 400-page ceiling with a token still pending — treating as incomplete
19974 items across 400 page(s) (INCOMPLETE)
crawl accepted (prune=false): 1 album(s) truncated — adding only, nothing removed
```

A runaway crawl was detected, marked incomplete, and **refused permission to delete
anything** — on its first encounter with a real failure, caused by a bug nobody had found
yet. That is precisely the scenario three reviewers named as the likeliest way the project
fails, and the guard held.

### Hero interludes — verified by observation, 2026-09-10

Confirmed working on the wall by watching the frame. This had been outstanding since
Phase 1 and could not have been closed any other way: the interlude is a five-minute
cadence, and what it needed to establish was whether the grid-to-hero-to-grid transition
*reads* well, which no assertion can answer. The behind-the-hero template swap comes with
it — if the template changed in view, the grid would visibly reflow, and it does not.

### Still not verified

- `collageEnabled = false` fallback. Needs interaction with the settings screen.
- The `AlbumSync` abort-path cleanup, which needs a degraded parse to reach and cannot be
  triggered on demand.
- A *weekly* re-roll of the resident sample and the two-generation grace, which by
  definition needs two complete crawls a week apart.
