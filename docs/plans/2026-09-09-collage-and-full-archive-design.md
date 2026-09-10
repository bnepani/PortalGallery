# PortalGallery — Collage Mode and the Full Archive

**Date:** 2026-09-09
**Status:** Design validated in session. Not implemented. Step 1 (RPC probe) is a
go/no-go gate on the rest.
**Author:** bnepani (with Claude Code)
**Supersedes:** the C-1 "pick B or C" recommendation in `design-review-architecture.md`.
This design picks **A**.

---

## 1. Problem

The frame shows ~300 photos and cycles them. New photos added to the family album do not
appear.

The stated request was "add an auto-refresh." **Auto-refresh already exists** —
`SlideshowActivity.refreshLoop()` (`SlideshowActivity.kt:277`) re-syncs every six hours in
a `while (lifecycleScope.isActive)` loop and calls `onLibraryChanged()`. The reason new
photos still do not appear is different, and it is a reach problem, not a scheduling one.

### 1.1 What the measurements actually say

The share page returns **exactly 300 items plus a continuation token**, always. What
varies between albums is the *direction* of that 300-item prefix, which follows the
album's own sort order in Google Photos (`2026-08-17-…-design.md` §9a):

| Album | Order | Capture range | Inversions |
|---|---|---|---|
| `Arizona 2026 · Apr 11–17` | ASCENDING (oldest first) | 2026-04-11 → 04-12 | 2 / 299 |
| **`PortalShare · Apr 2019 – Aug 2026`** | **DESCENDING (newest first)** | 2026-05-03 → 08-10 | 294 / 299 |

`PortalShare` is the frame album. It is newest-first, so the six-hourly refresh *should*
already be pulling in newly-taken photos — the failure mode is milder than "never
refreshes."

**The real limit: the album now holds ~20,000 photos spanning Apr 2019 – Aug 2026.** The
page serves the newest 300 by capture date, which at the time of measurement was a
**three-month window** (May 3 → Aug 10). So:

- The frame draws from **1.5%** of the archive.
- `AlbumSync.prune()` (`AlbumSync.kt:204`) deletes anything outside that window on every
  fully-successful sync, so the library is a *rolling* three months.
- **~19,700 photos — 2019 through spring 2026 — can never appear.**

### 1.2 A second, quieter failure

Ordering is by **capture** date, not added date. A photo *added* today but *taken* in 2019
sorts to position ~19,000 and will never reach the frame no matter how often we sync. For
a family album that receives scans and back-dated uploads, this is not an edge case.

### 1.3 The orientation tax, compounding

`PhotoSelector` drops every photo whose orientation does not match the panel. Measured on
the reference album: 168 portrait / 132 landscape. A landscape-mounted Portal+ therefore
renders **44%** of what it already paid to download. Against a 300-photo window that
leaves ~132 photos in rotation.

---

## 2. Constraints

Carried forward from the original design, all still binding:

| | Constraint |
|---|---|
| C6 | **The frame never blanks.** Whatever is on disk can be displayed, offline, indefinitely. |
| C7 | **No blocking network call in the Activity.** It renders what is on disk, immediately. |
| C8 | **A failed sync never reduces what is on disk.** Every non-success exit path leaves photos and index untouched. |

New for this work:

| | Constraint |
|---|---|
| C9 | **Day one is strictly better than today, never worse.** The existing 300 keep rendering throughout the initial crawl and backfill. |
| C10 | **No slot ever renders empty.** Every curation mode degrades to a broader pool rather than returning fewer tiles. |

---

## 3. Decisions taken

| Decision | Choice | Rejected alternatives |
|---|---|---|
| Reach | **Paginate the `snAcKc` batchexecute RPC** | Partitioning into ~12 year-sliced albums (caps at ~3,600 and needs ongoing manual curation by the family); accumulating forward only (never recovers 2019–2025) |
| Presentation | **Living wall + hero interludes** | Whole-grid swap (simplest, but a full visual reset every dwell) |
| Curation | **Three independent toggles: era mix, on-this-day, recency** | Event-triggered album takeover — raised, then dropped |
| Storage | **Two tiers, generational eviction** | LRU (see §6.3) |

### 3.1 Accepted risk

`design-review-risk.md:179` states the project's Terms-of-Service posture "is contingent
on *not* paginating," and that reaching for the batchexecute RPC means the ToS analysis
"changes materially and must" be redone. **This was raised explicitly and accepted** for a
personal, single-household, read-only device.

The technical risk is smaller than it looks. The app is *already* built entirely on
undocumented page structure, with two-tier parser degradation and golden fixture tests.
Pagination is an increment on that foundation, not a new category of exposure — and C8
means a broken RPC degrades to *no new photos*, never to a blank frame.

---

## 4. Architecture

The central move is **separating the index from the bytes**. Today `PhotoStore`'s index
means "these files are on disk," and `AlbumSync` conflates "what is in the album" with
"what we downloaded." At 20,000 photos those have to come apart:

- **The index is complete.** All 20,000 items — id, base URL, dimensions, capture time,
  video flag, source album. ~3 MB of JSON. Curation always sees the whole archive.
- **The bytes are progressive and capped.** Downloaded toward a disk budget, prioritised
  by what curation actually wants to show. The frame never waits for a complete download.

### 4.1 New components

| Component | Role |
|---|---|
| `data/album/AlbumPager` | Executes the `snAcKc` RPC — `)]}'` prefix, length-prefixed chunks — and yields pages plus the next token |
| `data/store/AlbumIndex` | Persistent metadata for all 20,000 items, independent of what is on disk |
| `data/schedule/CollageSelector` | Pure Kotlin. Given the index and enabled curation modes, picks tiles for slots |
| `ui/slideshow/CollageLayout` | Layout templates: slot rectangles tagged portrait/landscape |

### 4.2 Changed components

- **`SharedAlbumParser`** gains album-id and share-key extraction from
  `AF_dataServiceRequests`. Currently unparsed; required to build the RPC request.
- **`PhotoStore`** grows two tiers plus a disk cap. This retires the "deliberately not an
  LRU cache" comment, which was reasoned for a 300-photo album and does not survive
  contact with 20,000.
- **`AlbumSync`** becomes three phases: refresh index → choose download set → fetch bytes.
- **`PhotoSelector`**'s orientation filter changes role. Slots carry orientation, so
  matching happens per-slot instead of filtering the whole library. This is what recovers
  the 56% (§1.3).

---

## 5. Pagination

### 5.1 The RPC

`AF_dataServiceRequests` in the page declares `ds:1` as `snAcKc` with request tuple
`[albumId, null, null, shareKey]`. The continuation token arrives at `data[2]`. Page 2 is
the same call with the token in the slot that currently holds `null`, POSTed to
`photos.google.com/_/PhotosUi/data/batchexecute`.

The response needs three unwrapping steps the HTML path does not:

1. Strip the `)]}'` anti-JSON-hijacking prefix.
2. Walk the length-prefixed chunk framing.
3. Parse the inner payload — which is the **same `ds:1` array shape** that
   `SharedAlbumParser.structured()` already handles.

So entry decoding is reused, not duplicated. `AlbumPager` owns only the envelope.

### 5.2 Step 1 is a probe, and it is a gate

A throwaway Python probe against the live album, confirming page 2 returns entries plus a
fresh token. `design-review-architecture.md:98` had strong static evidence but was
explicit: *"I was **not able to execute this call** — the sandbox blocked the outbound
POST — so treat it as strong static evidence, not a verified capability."*

**If the probe fails, stop and re-open §3's decision** rather than discovering it three
days into implementation. On success, scrub the response via the existing
`tools/scrub_fixture.py` and commit it as a golden fixture, so `AlbumPager` gets the same
test treatment as the HTML parser.

### 5.3 Crawl strategy

- **First crawl:** ~67 round trips, throttled to roughly one page per second.
- **Steady state:** the album is newest-first, so new photos land on **page 1** — a single
  request.
- **Weekly:** a full re-crawl, to catch deletions and back-dated additions (§1.2).

### 5.4 Failure posture

Unchanged in spirit from C8:

- A partial crawl is **discarded, not merged**. The previous index survives intact.
- The existing shrink alarm moves to the index: a crawl returning far fewer items than
  last time is rejected as suspect.
- `prune()` deletes only items absent from a **fully successful** crawl. The disk cap is
  enforced by separate eviction, so a short crawl can never trigger mass deletion.

---

## 6. Storage

### 6.1 Tier 1 — tiles

Every indexed photo is eligible, fetched fit-inside an 800 px box via the existing
`=w800-h800` suffix. That covers the largest slot in a 6-up layout: a full-height portrait
slot is 640×1080, so an 800 px source upscales 1.35× — invisible on a wall at two metres.

~90 KB each. All 20,000 ≈ **1.8 GB**.

### 6.2 Tier 2 — heroes

~400 photos at today's full `=w1920-h1200`, ~301 KB each ≈ **120 MB**. Hero interludes draw
**only** from this pool, so a hero is always already on disk (C6, C7). The pool is re-drawn
each sync — newest arrivals plus a rotating sample — which is also what stops heroes
repeating.

Both tiers are keyed by the same media id, so `heroes/` is a superset-quality copy rather
than a separate identity.

Default budget **~2.5 GB** of the 12.4 GB free on `/data`
(`docs/portal-device-facts.txt`).

### 6.3 Eviction is generational, not LRU

This matters. LRU is precisely the failure `PhotoStore`'s own comment warns about — *"the
slideshow reaching for a photo that was silently thrown away, then hitting the network
mid-render."*

Instead the sync computes a **resident set** and swaps it atomically with the index,
exactly as `saveIndex()` does today. Eviction only ever deletes files **outside** the
current resident set, so nothing on screen or in the current rotation can vanish underneath
the renderer.

**Pinned and never evicted:** the newest 300, and the entire hero pool. That guarantees a
floor well above what the frame shows today (C9).

### 6.4 Download order

Curation-driven, so the archive becomes useful long before it is complete:

1. Hero pool.
2. One tile per year bucket, until every era is represented.
3. Recent arrivals.
4. General backfill.

Videos stay off by default. At 20,000 items, `=dv` originals would dwarf the entire budget.

---

## 7. Curation

`CollageSelector` is pure Kotlin with no Android dependencies — a sibling of
`PhotoSelector`, unit-tested the same way. Three independent toggles in `AppPreferences`.

### 7.1 How the three compose

**Era mix is structural, not a weight.** It partitions the six slots across capture-year
buckets, so a grid physically cannot be six photos from one afternoon. With 2019–2026 that
is roughly one slot per year, oldest buckets sharing.

**On-this-day and recency are weights**, applied *within* whichever pool era mix hands
them. On-this-day multiplies candidates whose capture month/day matches today; recency
decays a boost over a few weeks from arrival.

So: era mix decides *which era each slot draws from*, the weights decide *which photo from
that era*. With era mix off, it is one global weighted pool.

### 7.2 Defaults

Following the project's existing convention that a filter which can thin the rotation
should be an explicit choice (`AppPreferences.weekdayFilterEnabled`):

| Mode | Default | Why |
|---|---|---|
| Era mix | **on** | Does not narrow the library |
| Recency | **on** | Does not narrow the library |
| On this day | **off** | 1-in-365; can legitimately match nothing |

The existing weekday filter stays as-is. At 1-in-7 versus on-this-day's 1-in-365 they are
genuinely different features, not a duplication.

### 7.3 Two things worth calling out

**The relax cascade is mandatory, not optional (C10).** Every mode degrades to the
next-broadest pool rather than returning fewer tiles.

**Curation picks from the index, but only downloaded items can render.** Selection is
two-stage: prefer a candidate with bytes on disk; if the ideal pick is not downloaded yet,
queue it and substitute a resident one from the same bucket. This is why the download
prioritiser looks *ahead* — pre-fetching tomorrow's on-this-day set overnight, so the
anniversary photos are already there when the date turns.

---

## 8. Rendering

### 8.1 Layout templates, not procedural packing

A hand-designed set of 5–6 templates for 1920×1080, each declaring slot rectangles tagged
with a preferred orientation:

- 3×2 uniform grid (640×540 slots)
- three full-height portrait columns (640×1080)
- 960×1080 hero-left with a 2×2 beside it
- two mixed arrangements

Procedural packing produces slivers and is hard to test. Templates are data, so a unit test
can assert every slot is filled and no slot is degenerate. **Slot orientation tags are the
mechanism that recovers the 56% portrait photos** (§1.3).

### 8.2 Living wall

Each slot owns an A/B `ImageView` pair — the same role-swapping trick documented at the top
of `activity_slideshow.xml`, twelve views instead of two — so a tile crossfades without the
shared-`Drawable` problem that comment warns about.

Tiles swap on staggered independent timers, roughly one every 3.3 s to hit six new moments
per 20 s. The next tile is chosen **at random rather than round-robin**, which reads
mechanical.

### 8.3 Hero interludes

Reuse `iv_photo_a`/`iv_photo_b` above the grid, plus the existing `Transition` and
`startKenBurns()` untouched.

**Template changes hide behind the interlude.** Changing the layout moves every tile at
once, which is a jolt — so the template only ever changes *behind* a hero, concealed by the
full-screen photo. The interlude doubles as the seam for re-laying-out, and the grid never
visibly reflows.

### 8.4 Videos are hero-only

Never tiles. Six `VideoView`s in a grid is heavy, and a clip playing in one corner is
distracting rather than charming.

### 8.5 Existing machinery to extend, not replace

- `enterSleep()` must cancel all six tile timers plus the hero timer.
- The watchdog's `lastRenderedAtMs` becomes "any tile rendered recently."
- The template set is selected per orientation, so `onConfigurationChanged` swaps templates
  instead of re-filtering.

---

## 9. Testing

Following the project's existing posture — the fixture test is the highest-value test here
too, because this depends on undocumented structure and will break when Google changes it.

| Test | Guards |
|---|---|
| `AlbumPager` against a committed RPC-response fixture | Envelope parsing: `)]}'`, chunk framing, token extraction |
| Album-id / share-key extraction from `AF_dataServiceRequests` | The request tuple we send |
| `CollageSelector` — all eight toggle combinations | C10: no empty slots, no duplicate photo within one grid |
| `CollageSelector` — on-this-day matching nothing | The relax cascade |
| `CollageLayout` — every template | Every slot filled, no degenerate rect, slot areas tile the panel |
| Generational eviction | Never deletes a member of the current resident set |
| Index shrink alarm | A short crawl is rejected, previous index survives |

---

## 10. Open questions

- [ ] Does the `snAcKc` RPC actually work? **Gate on §5.2 before anything else.**
- [ ] Is the continuation token stable across syncs, or does it expire? Affects whether
      incremental refresh can resume from a stored cursor or must re-walk from page 1.
- [ ] Real heap ceiling for twelve simultaneous `ImageView`s with HARDWARE bitmaps on
      Portal+ (heap growth limit 256 m, but HARDWARE allocates off-heap).
- [ ] Initial backfill pacing — 20,000 downloads at `CONCURRENCY 4` is ~25 min flat out.
      Trickling over hours or days is politer and costs nothing on a wall frame.
- [ ] Does `AlbumIndex` stay JSON at 20,000 entries, or move to SQLite? Gson parsing 20k
      entries is ~200–400 ms on a background thread. JSON until measured otherwise.
