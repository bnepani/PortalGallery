VERDICT: NEEDS_REVISION

# Architecture Review — Collage Mode and the Full Archive

**Reviewed:** `docs/plans/2026-09-09-collage-and-full-archive-design.md`
**Against:** `app/src/main/kotlin/`, `app/src/test/resources/shared_album_fixture.html`,
`docs/portal-device-facts.txt`, `docs/plans/2026-08-17-portalgallery-photo-source-design.md`,
`docs/plans/design-review-architecture.md`
**Date:** 2026-09-09

---

## Summary Assessment

The diagnosis in §1 is correct and well-evidenced — the reach problem is real, pagination
is the only option that recovers 2019–2025, and separating index from bytes is the right
instinct. But three load-bearing pieces do not survive contact with the code: the §6.1
byte arithmetic is below the floor implied by the project's own measurements (which
converts eviction from an edge case into the steady state), the §6.3 eviction invariant is
false under a concrete interleaving, and §5.4 silently breaks `AlbumSync`'s multi-album
prune guard in a way the existing 0.5 shrink alarm cannot catch. There is also no
migration section at all, which leaves C9 asserted rather than demonstrated.

---

## Critical Issues (must fix)

### C-1. §6.1's 90 KB is below the floor implied by the project's own measurements. The budget does not close.

This is the arithmetic the whole storage design rests on, and it is derived, not measured.

The prior review measured the **same 12 photos** at two sizes
(`design-review-architecture.md:216-231`), which gives two clean anchor points:

| Variant | Mean pixels | Mean bytes | KB/megapixel |
|---|---|---|---|
| bare | 384×512 = **0.197 MP** | 63,534 | **323** |
| `=w1920-h1200` | **1.513 MP** (computed from the 12 listed output dims) | 308,272 | **204** |

KB/MP *rises* as resolution falls — JPEG bytes scale sublinearly with pixel count, exactly
as the review prompt suspected. A `=w800-h800` box on this album's 4:3 and 3:4 originals
yields 800×600 / 600×800 = **0.48 MP** (0.36 MP for the one 16:9 item), so ~0.47 MP mean.

Two crude bounds bracket the answer before any curve-fitting:

- Scaling linearly **down** from `=w1920-h1200`: 308 KB × (0.47/1.513) = **96 KB** — and
  linear-down is a strict *underestimate*, because KB/MP rises.
- Scaling linearly **up** from bare: 63.5 KB × (0.47/0.197) = **151 KB** — a strict
  *overestimate*, for the same reason.

Fitting `bytes = k·MP^α` through the two anchors gives α = 0.774, k = 223,712, and at
0.47 MP → **~125 KB**.

So the design's **90 KB is outside the bracket** — it is below even the underestimate.
The real figure is ~125 KB, roughly **39% higher**.

**What this does to §6:**

| | Design says | Actual |
|---|---|---|
| 20,000 tiles | 1.8 GB | **~2.5 GB** |
| + hero pool | 120 MB | 120 MB |
| **Total** | 1.92 GB against a 2.5 GB budget | **~2.62 GB against a 2.5 GB budget** |

The design presents a full archive that fits comfortably with ~600 MB spare. It does not
fit. That is not merely a sizing error — it changes the *character* of §6.3: eviction goes
from "occasionally trims the tail" to "runs on every sync, permanently, at the margin,"
which makes C-2 below load-bearing rather than theoretical.

Fix: re-measure against the live album before committing to a budget (the committed
fixture is scrubbed — `tools/scrub_fixture.py` rewrites the photo URLs, so its URLs 400
and cannot be used for this), and either raise the budget or shrink the resident set.

**Related internal contradiction.** §6.1 justifies 800 px as covering *"the largest slot
in a 6-up layout: a full-height portrait slot is 640×1080 … upscales 1.35×."* But §8.1's
own template list includes **960×1080 hero-left**, which is larger. A 600×800 portrait
tile covering 960×1080 upscales **1.6×**; an 800×600 landscape tile covering it upscales
**1.8×**. Either drop that template or size tier 1 to the actual largest slot.

Also: §6.2 says heroes use *"today's full `=w1920-h1200`."* Today's suffix is not
`=w1920-h1200` — `SlideshowActivity.kt:289-290` passes `metrics.widthPixels,
metrics.heightPixels`, which on this Portal+ is **1920×1080**
(`docs/portal-device-facts.txt`, `app 1920 x 1080`). The 301 KB figure was measured at
h1200. Minor, but it is the number the hero tier is sized from.

---

### C-2. §6.3's generational eviction does not hold. Here is the interleaving.

§6.3 claims: *"Eviction only ever deletes files **outside** the current resident set, so
nothing on screen or in the current rotation can vanish underneath the renderer."*

The claim equivocates on "current resident set." There are two of them, and they are not
the same object:

- **R_sync** — the set the sync just computed and wrote via `saveIndex()`.
- **R_render** — the in-memory list the renderer is actually drawing from.

`R_render` is populated by `store.load()` and held in `SlideshowActivity.library` /
`photos` (`SlideshowActivity.kt:97-98`, set at `:251` and `:330`). It is only ever
refreshed inside `onLibraryChanged()`, which runs **after** `sync()` returns
(`SlideshowActivity.kt:314`). Meanwhile `AlbumSync.sync()` writes the index at
`AlbumSync.kt:202` and deletes files at `AlbumSync.kt:204`, both on `Dispatchers.IO`,
while the six tile timers are firing on the main thread.

Concrete trace, tile T resident in R_render but evicted from R_sync:

1. `t=0` — renderer holds T in `photos`; T's slot timer is armed for `t=3.3s`.
2. `t=1.0s` — sync (IO thread) computes R_sync excluding T, calls `saveIndex()`.
3. `t=1.1s` — eviction deletes `photos/<T-id>.jpg`. This is legal under §6.3's rule: T is
   outside R_sync.
4. `t=1.4s` — a *different* slot's crossfade is mid-flight loading T (the design's own
   scenario). Glide's decode already holds an open FD, so on Linux the unlink is
   survivable and this particular load completes — masking the bug intermittently.
5. `t=3.3s` — T's slot timer fires, `Glide.load(T.file)` on a deleted path →
   `onLoadFailed` (`SlideshowActivity.kt:654`). The slot goes blank or skips.
6. `onLibraryChanged()` finally runs at `t≈4s`+ and re-reads. Everything between step 3
   and here is exposed.

Today this degrades gracefully because `prune()` only removes items that left the *album*
(a rare, small set) and one failed load costs one photo out of 300. Under this design,
eviction is continuous and pressure-driven (see C-1), the exposed window contains up to
six live tiles, and `consecutiveFailures` (`SlideshowActivity.kt:663`) is compared against
`photos.size` — which is now the whole resident set, so the bounded-failure guard is
effectively disabled.

The invariant the design needs is stronger than the one it states:

> Eviction may delete only files outside **R_sync ∪ R_render**.

which requires the renderer to publish what it is holding, or the sync to defer eviction
until the renderer has acknowledged the swap. Neither is specified. §9's test row
(*"Generational eviction — never deletes a member of the current resident set"*) will pass
against the wrong definition and prove nothing.

---

### C-3. §5.4 does not compose with the existing multi-album carry-forward. A truncated crawl can prune half the library.

§5.4 says a partial crawl is *"discarded, not merged"* and that *"the existing shrink alarm
moves to the index."* Trace both against `AlbumSync.sync()`.

**(a) `allOk` is computed from `.ok` alone, and `.ok` means `error == null`.**
`AlbumSync.kt:113` is `val allOk = outcomes.all { it.ok }`, where `ok` is
`error == null` (`:56`). Note `AlbumOutcome` *already has* a `truncated` field (`:54`, set
from `!parsed.isCompleteAlbum` at `:257`) and `allOk` **deliberately ignores it** — because
today every album is truncated at 300 and pruning still has to work.

Under pagination, a crawl that dies at page 40 of 67 is the natural `ok = true, truncated =
true` case: no exception was thrown, 12,000 real photos came back. So:

- `allOk` stays `true`,
- carry-forward is skipped (`carried = emptyList()`, `:187`),
- `prune()` runs against the truncated `wanted` (`:204`),
- ~8,000 photos are deleted.

The design must invert the meaning of `truncated` and fold it into `allOk`. It never says
so, and the existing comment at `:176-185` explicitly documents `allOk` as the guard, so an
implementer following the design will not touch it.

**(b) The 0.5 shrink alarm cannot cover the gap.** `SHRINK_ALARM = 0.5`
(`AlbumSync.kt:33`) was calibrated for an all-or-nothing 300-item page fetch, where the
outcome distribution is bimodal: ~300 or ~0. Pagination makes it **continuous** — any
prefix length 1…67 pages is reachable. The alarm fires only below 50%, so:

| Crawl reaches | Items | Alarm fires? | Photos pruned |
|---|---|---|---|
| page 1–33 | ≤ 9,900 | yes | 0 (good) |
| **page 34–66** | **10,200–19,800** | **no** | **200–9,800** |
| page 67 | 20,000 | n/a | correct |

Half the failure space prunes. This is the *same class* of defect the prior review called
out at `design-review-architecture.md:155-157` ("a count-only gate is nearly inert") —
arriving through a different door.

**(c) The alarm's operands become incomparable.** `AlbumSync.kt:114` compares
`wanted.size` against `existing.size`, where `existing = store.load()` (`:81`) — and
`store.load()` returns only entries **whose bytes are present on disk**
(`PhotoStore.kt:66-73`). Once the index is 20,000 and the resident set is, say, 6,000
during backfill, the test becomes `20000 < 6000 * 0.5` — which can never fire. The alarm
does not "move to the index" for free; it must be re-based against the *previous index
size*, persisted separately, or it silently dies.

**(d) One thing that does work, and is worth banking.** If the RPC fails outright and the
crawl falls back to HTML page 1 only, `wanted.size = 300` vs a previous index of 20,000
→ `300 < 10000` → the alarm **does** fire and the sync fails safe. Good. But the design
should state the fallback explicitly, because §5.4 covers a failed crawl and says nothing
about *permanent* RPC death — after which the weekly full re-crawl fails weekly forever and
the index freezes with no alarm and no operator signal.

---

### C-4. The index/bytes separation leaks in four places in `PhotoStore`, and there is no migration plan.

§4 is right that these must come apart. The design does not account for how much of
`PhotoStore` currently *defines* them as the same thing.

1. **`load()` filters the index by file existence** (`PhotoStore.kt:66-73`). The index
   literally is "what is on disk." A complete 20,000-entry `AlbumIndex` cannot be served
   through this method, and the design never says whether `index.json` survives, is
   migrated, or is superseded. Two different reads are needed — *full index* for curation,
   *resident set* for the renderer — and neither is named.
2. **`Entry` has no `baseUrl`** (`PhotoStore.kt:27-40`). Today the URL comes fresh from the
   parse on every sync and is never persisted. §4's index requires it. That is a schema
   change to a file that ships on every existing install.
3. **`fileFor(id, isVideo)` collides across tiers** (`PhotoStore.kt:53-54`). §6.2 says both
   tiers are *"keyed by the same media id"* — so a tile and a hero both want
   `photos/<id>.jpg`. Either the hero pool needs its own directory (in which case
   `existingFile()` at `:57-59`, `hasPhoto()` at `:99`, `prune()` at `:102`, and
   `totalBytes()` at `:108` all need tier awareness) or the tiers overwrite each other.
   Unstated.
4. **`hasPhoto(id)` is a boolean** and drives the download set at `AlbumSync.kt:119`. With
   two tiers, "do we have it" is no longer a yes/no question, and "which tier is missing"
   is what the §6.4 prioritiser actually needs.

**And there is no migration section anywhere in the document.** Trace first run after
upgrade on a real install: 300 files exist at `=w1920-h1080`, keyed `<id>.jpg`, with a
300-entry `index.json`. If tier 1 lives in a new directory, `hasPhoto` is false for all
300, the frame has **zero tiles** until the 67-page crawl plus a multi-thousand-item
backfill completes — hours at best. **C9 ("day one is strictly better than today, never
worse") is violated on the only path that matters, the upgrade path.** If instead tier 1
reuses the existing directory, the 300 legacy files masquerade as tiles at 2.4× the
intended size, and `totalBytes()` accounting against the 2.5 GB cap is wrong from the
start. The design asserts C9; it never demonstrates it.

Note the project has precedent for doing this well —
`AppPreferences.albumUrls` migrates the legacy key on read, and `Entry.captureMs`/`isVideo`
default so an old index still deserialises (`PhotoStore.kt:31-39`). The design should
say which of those patterns applies.

---

### C-5. "Entry decoding is reused, not duplicated" is false as `SharedAlbumParser` is written today.

§5.1 closes with: *"the inner payload — which is the **same `ds:1` array shape** that
`SharedAlbumParser.structured()` already handles. So entry decoding is reused, not
duplicated. `AlbumPager` owns only the envelope."*

Read the function. `structured()` is `private fun structured(html: String, title: String?)`
(`SharedAlbumParser.kt:91`). It is:

- **private** — not callable from `data/album/AlbumPager`;
- **typed on `html: String`**, not on a `JsonArray`;
- **coupled to the HTML path** — its first act is `extractDsRaw(html, "ds:1")`
  (`:92`), which does `html.indexOf("key: 'ds:1'")` (`:125`), an anchor that exists only in
  the `AF_initDataCallback` markup and never in an RPC response;
- **not decomposed** — the per-entry mapping is an inline `data[1].asJsonArray.mapNotNull
  {...}` lambda at `:97-114`. There is no `decodeEntries(JsonArray): List<Photo>` seam to
  call.

The genuinely reusable logic is `:97-119` plus the private helpers `videoMeta()` (`:215`),
`normalizeScheme()` (`:222`), `getOrNullInt`/`getOrNullLong` (`:228-232`) — all private.
Making this true requires extracting an `internal fun decode(data: JsonArray): Result` and
re-pointing both `structured()` and `AlbumPager` at it. That is a small refactor, but it is
a refactor the design budgets at zero and the phasing does not mention. Worth correcting
because "reused, not duplicated" is doing real work in the risk argument for §3.1.

---

### C-6. §5.1 understates the request side, and it answers §10's open question the design leaves open.

§5.1 lists three unwrapping steps, all on the **response**. The **request** is where the
undocumented surface actually is, and the committed fixture shows it.

Confirmed present in `app/src/test/resources/shared_album_fixture.html`:

```
AF_dataServiceRequests = {'ds:0':{id:'UJlKrf',request:["AF1Qipf1y5…","_8IdqB2S…"]},
                          'ds:1':{id:'snAcKc',ext: 7.1837398E7,
                                  request:["AF1Qipf1y5…",null,null,"_8IdqB2S…"]}}
```

So §4.2's claim that album id and share key are extractable is **confirmed and testable
against the already-committed fixture** — good. But note the tuple has **two** nulls
(indices 1 and 2), and §5.1 says only *"the token in the slot that currently holds
`null`."* Pin the index (the prior review's static read was index 2) or state that §5.2's
probe resolves it.

More importantly, a `batchexecute` POST is not just `f.req`. It also needs `rpcids`,
`source-path`, `f.sid`, `bl`, `_reqid`, `rt=c`. Those live in `WIZ_global_data`, also in
the fixture:

```
cfb2h  = boq_photosuiserver_20260813.06_p0     # the `bl` build label
FdrFJe = 7077443408547388065                   # the `f.sid` session id
Im6cmf = /_/PhotosUi                           # source-path
SNlM0e = ABSENT                                # no xsrf token — consistent with unauth
```

Two consequences the design should record:

- `bl` is a **Google server build label that rotates on every deploy** (~weekly). Anything
  hardcoded breaks within days. It must be scraped per crawl.
- This **answers §10's second open question**. The crawl cannot resume from a stored cursor
  alone across process restarts, because `bl`/`f.sid` are page-load-scoped. Every crawl
  must begin with an HTML page fetch. That is cheap and it is also good news — it means
  §5.3's "steady state is a single request" is already satisfied by the HTML fetch itself.

`SNlM0e` being absent is genuinely encouraging for §5.2's probe: no xsrf token to forge.

---

### C-7. §7.1's era-mix composition does not work as claimed, and the defaults regress requirement (a).

**6 slots, 8 buckets (2019–2026).** §7.1 says *"roughly one slot per year, oldest buckets
sharing."* "Sharing" is backwards — sharing a slot means two buckets *alternate*, so any
given grid shows at most 6 of 8 eras and **two years are absent from every grid**. Which
two, and how they rotate across grids, is unspecified. Without a rotation rule the same two
eras can be permanently invisible.

**The archive is not uniform, and the design assumes it is.** 20,000 photos over Apr 2019 –
Aug 2026 in a family album that has been accumulating: recent years almost certainly
dominate. If 2019 holds 400 and 2026 holds 6,000, strict one-slot-per-year gives 2019 16.7%
of screen time for 2% of the archive — a 2019 photo recurs ~15× as often as a 2026 photo.
That is a legitimate product choice, but it should be a stated one, and §7.1 presents
stratification as neutral.

**Era mix (default on) fights requirement (a) — quantify it.** Six tile swaps per 20 s =
1,080 swaps/hour. With era mix on, ~1/6 (180/hr) go to the 2026 bucket, competing against
~6,000 candidates: a specific newly-added photo surfaces roughly **once per 33 hours**.
Today, 300 photos at an 8 s dwell = 450 renders/hour, so each photo surfaces every ~40
minutes. Under the recommended defaults, **a new photo becomes ~50× less visible than it is
today**. Recency-by-arrival helps, but the design never does this arithmetic and the
headline requirement is "newly-added photos should appear."

**6 slots, 1 bucket.** Era mix becomes a no-op and all six slots draw from one year — which
is exactly the *"six photos from one afternoon"* outcome §7.1 says the mode makes
*"physically"* impossible. Harmless in effect, but the guarantee as stated is false.

**Three orthogonal constraints, no relax ordering.** Each slot must satisfy era ×
orientation (§8.1) × bytes-resident (§7.3), plus a grid-wide no-duplicate constraint (§9's
test row). That is a constraint-satisfaction problem, and greedy per-slot assignment with
independent cascades can starve the last slot. §9 promises a test for *"all eight toggle
combinations"* — but the real state space is toggles × template × bucket distribution ×
residency, and 2³ does not cover it.

**And the relax order contradicts §1.3.** During backfill, §6.4 downloads *"one tile per
year bucket"* — one — so an old bucket has a single resident tile of arbitrary orientation.
On the "three full-height portrait columns" template, slot 1 wants (2019, portrait) and
2019's one resident is landscape. Relax era → you get a recent photo and era mix silently
stops working. Relax orientation → you get an 800×600 landscape stretched into a 640×1080
slot at 1.8× upscale with heavy crop. §1.3 and §8.1 claim slot tags *"recover the 56%"*,
which requires relaxing era **first** — directly contradicting §7.1's *"era mix is
structural, not a weight."* Pick one and write it down.

**Minor, but it breaks the math:** §7.1 hard-codes six slots throughout, while §8.1's
template list includes a 3-slot template ("three full-height portrait columns") and a
5-slot one (960×1080 + 2×2). The stratification rule is specified only for 6.

---

## Suggestions

### S-1. There is a materially simpler architecture that meets all three requirements. Consider it before building §6.

Keep the pagination — nothing else reaches 2019, and §5 is the strong half of this design.
Then **delete §6 entirely**: no tiers, no disk cap, no eviction policy, no generational
resident set.

> Crawl the full 20,000-item index (§5, unchanged). Curate a **fixed-size resident sample**
> of ~1,500–2,000 photos from it — era-stratified, so 2019 is represented — at today's
> single `=w1920-h1080` suffix. Re-roll the sample on the weekly full re-crawl, keeping the
> intersection so most bytes are reused. Everything else is deleted.

Sizing: 1,500 × 301 KB ≈ **452 MB**; 2,000 ≈ **600 MB**. Against a 2.5 GB budget that is
comfortable with the *measured* number, not a derived one — so C-1 stops mattering.

What this buys:

- **C-2 disappears.** The resident set is bounded and changes once a week at a known
  moment, not continuously under disk pressure. `PhotoStore`'s *"deliberately not an LRU
  cache"* comment (`PhotoStore.kt:11-16`) survives intact — it is still true, just at a
  different N.
- **C-4 shrinks to one problem** (add `baseUrl` to `Entry`, add a separate full-index
  file). One tier means no directory split, no `fileFor` collision, no tier-aware
  `hasPhoto`.
- **C-1 disappears** — the 1920 suffix is the one size that has actually been measured.
- **Heroes are free.** Every resident photo is already hero-quality; §6.2's separate pool
  and its ~480 MB/day of re-download churn (400 heroes × 301 KB, "re-drawn each sync",
  4 syncs/day) both go away.
- **Backfill is ~10 minutes, not hours** — which is what actually delivers C9.

What it costs: the ideal curation pick is sometimes not resident. But §7.3 **already
concedes exactly this** — *"if the ideal pick is not downloaded yet, queue it and
substitute a resident one from the same bucket."* The system is designed to tolerate misses
regardless. The 2.5 GB tier-1 fill buys a marginal reduction in substitution rate at the
cost of the entire eviction, tiering, and migration surface. That is a bad trade for a
household wall frame.

Requirements check: (a) new photos land in the weekly re-roll and can be force-pinned;
(b) the sample is drawn from all 20,000; (c) collage and curation are unaffected — they
read the same index.

### S-2. Concurrency and lifecycle are unaddressed, and the current shape will not survive the change.

Today the whole refresh is one coroutine in `SlideshowActivity.lifecycleScope`
(`SlideshowActivity.kt:277-326`) — strictly sequential, so there is nothing to race. The
new design adds a 67-page crawl, a long-running trickle downloader (§10 proposes *"hours or
days"*), and six independent render timers, and says nothing about who owns which thread.

The concrete problem: a multi-hour trickle in `lifecycleScope` **dies with the Activity**,
and on this device that is routine, not exceptional — `enterSleep()` releases
`KEEP_SCREEN_ON` (`SlideshowActivity.kt:467`), the panel powers off, and the Activity
pauses; `exitSleep()` even comments that being backgrounded is *"the normal case, not an
edge case"* (`:493-497`). A frame that sleeps nightly would never finish its backfill.
The prior review raised the same class of issue (`design-review-architecture.md:263-266`,
unique work) and the current code sidesteps it only by being in-Activity. Specify the owner
— WorkManager unique work, or a foreground service — and specify that two syncs cannot
overlap.

### S-3. §8.5 weakens the watchdog into a signal that cannot detect the failure it exists for.

*"The watchdog's `lastRenderedAtMs` becomes 'any tile rendered recently.'"* With six
independent timers, five stalled tiles and one healthy one reads as healthy. Track the
**oldest** tile render time, not the newest. `AppPreferences.lastSyncMs`'s own comment
warns about precisely this ("a fresh sync on a dead frame reports healthy, which is exactly
the lie this pair exists to prevent") — do not reintroduce it one layer down.

Related, and missed: §8.5 says `enterSleep()` must cancel the tile timers. It must also
cancel **twelve** animators, not two (`SlideshowActivity.kt:448-449`), and `onPause()`
(`:850-851`) removes only the two named runnables — it needs all six tile runnables too, or
tiles keep advancing behind a dark panel.

### S-4. The index is ~6.6 MB, not ~3 MB. §10's JSON-vs-SQLite question should be re-asked with the real number.

Measured from the committed fixture: mean media id **44 chars**, mean base URL **157
chars**. Serialising a realistic entry (`id`, `baseUrl`, `width`, `height`, `captureMs`,
`addedMs`, `isVideo`, `album`) with Gson's field names gives **329 bytes/entry** →
**6.6 MB** at 20,000. That is 2.2× the design's estimate, and Gson's reflective
deserialisation of 20k objects plus 20k 157-char strings on a 2017 Portal+ will be
meaningfully slower than the 200–400 ms guess. Still probably acceptable on a background
thread — but re-run the estimate, and state that the full index is held in memory (~10 MB
heap) rather than re-read per grid recomposition.

### S-5. §10's heap question is answerable by arithmetic; no device needed.

12 `ImageView`s: six 640×540 tiles at ARGB_8888 = 1.38 MB each (16.6 MB for the A/B pair
set), plus two full-screen 1920×1080 heroes at 8.3 MB = 16.6 MB. **~33 MB** against a
256 MB growth limit — and HARDWARE bitmaps put most of it off-heap anyway. Close the
question. The real open question is not heap, it is **six simultaneous Ken Burns
`ViewPropertyAnimator`s** on 2017 hardware. §8.3 implies Ken Burns is hero-only; say so
explicitly, and note that `startKenBurns()` keys its drift direction on `currentIndex % 2`
(`SlideshowActivity.kt:765`), which has no meaning once there are twelve views.

### S-6. §5.3's "steady state is one request" does not cover §1.2, and the design reads as if it does.

§1.2 correctly identifies back-dated additions as the quieter failure. §5.3's steady-state
path is page 1 only — and page 1 is a **capture-date-descending** three-month window, so it
misses not just 2019 scans but anything taken more than ~3 months ago. The weekly full
re-crawl is what actually solves §1.2, which means the real SLA is **"back-dated photos
appear within 7 days."* State that as the SLA rather than leaving §1.2 looking solved.

### S-7. §7.3's pre-fetch is specified for a mode that is off by default.

*"Pre-fetching tomorrow's on-this-day set overnight"* — but §7.2 defaults on-this-day
**off**. Either gate the pre-fetch on the toggle (and accept that enabling it at 9am gets a
cold start) or pre-fetch unconditionally and say so in the bandwidth budget.

### S-8. Two smaller gaps.

- **Videos.** §6.4 says videos stay off by default, which matches
  `AppPreferences.videoEnabled`. But nothing says what happens if a user turns it on with a
  20,000-item index — `=dv` originals at ~2.9 MB (`design-review-architecture.md:238`) would
  be ~58 GB. Either cap video count explicitly or force the pref off above some index size.
- **Configuration surface.** Three curation toggles, a collage on/off, and a disk budget are
  five new settings. The README documents `adb shell am start -e …` as the primary
  configuration path; the design mentions `AppPreferences` but neither `SettingsActivity`
  nor the intent extras.

### S-9. §3.1's technical-risk framing is one step weaker than stated. (Product risk already accepted — noted, not re-litigated.)

*"Pagination is an increment on that foundation, not a new category of exposure."* The
existing app performs an unauthenticated GET of a public HTML page. `batchexecute` is a
POST to an internal RPC endpoint with a hand-constructed `f.req` tuple and a scraped,
weekly-rotating `bl` build label (C-6). That is a different category of both fragility and
posture, whatever one concludes about it. The C8 argument — a broken RPC degrades to *no
new photos* — is sound and is the load-bearing part; lead with that and drop the
"increment" claim.

Concretely on fragility: the two §9 fixture tests are **shape** tests against a static
response. Nothing in the test suite can detect "Google changed the request tuple arity" or
"`bl` format changed" — those fail silently in the field. Add a `tools/` live-probe script
alongside `tools/scrub_fixture.py` so breakage is detectable off-device, and surface RPC
degradation in `lastSyncSummary` the way `PARSER DEGRADED` already is
(`AlbumSync.kt:246`).

---

## Verified Claims

| # | Design claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Auto-refresh already exists; `SlideshowActivity.refreshLoop()` at `:277` re-syncs every 6h | **CONFIRMED** | `SlideshowActivity.kt:277-326`, `REFRESH_INTERVAL_MS` at `:69`, `onLibraryChanged()` at `:314` |
| 2 | `AlbumSync.prune()` at `AlbumSync.kt:204` deletes outside the window on every fully-successful sync | **CONFIRMED** | `:204`, gated on `allOk` (`:113`); `PhotoStore.prune()` at `PhotoStore.kt:102-106` |
| 3 | Orientation tax: 168 portrait / 132 landscape, 44% renders on a landscape panel | **CONFIRMED** | Prior design §9b; `PhotoSelector.select()` `:72` |
| 4 | §1.1 ordering table (Arizona ASCENDING 2/299, PortalShare DESCENDING 294/299) | **CONFIRMED** | Verbatim from prior design §9a; consistent with `design-review-architecture.md:36-44` |
| 5 | `AF_dataServiceRequests` declares `ds:1`=`snAcKc` with `[albumId, null, null, shareKey]`; currently unparsed | **CONFIRMED** | Present in `app/src/test/resources/shared_album_fixture.html`; no reference to `AF_dataServiceRequests` anywhere in `SharedAlbumParser.kt` |
| 6 | Continuation token is at `data[2]` | **CONFIRMED** | `SharedAlbumParser.kt:116-118` reads exactly that slot |
| 7 | RPC inner payload is the same `ds:1` array shape `structured()` handles | **PLAUSIBLE, UNVERIFIED** | Correct as a *shape* claim, but see C-5: `structured()` cannot consume it as written |
| 8 | "Entry decoding is reused, not duplicated" | **REFUTED** | `structured()` is `private`, typed on `html: String`, anchored on `key: 'ds:1'` (`SharedAlbumParser.kt:91-92, 125`). No `JsonArray` seam exists. |
| 9 | ~67 round trips for the first crawl | **CONFIRMED** | 20,000 / 300 = 66.7 |
| 10 | 12.4 GB free on `/data` | **CONFIRMED** | `docs/portal-device-facts.txt`: `12979284` KB = 12.38 GB |
| 11 | Heroes ~301 KB at `=w1920-h1200` | **CONFIRMED** (with a caveat) | `design-review-architecture.md:231` measured 308,272 B mean. But today's suffix is device-derived — 1920×**1080** here (`SlideshowActivity.kt:289-290`, `portal-device-facts.txt`), not h1200. |
| 12 | **~90 KB per tile at 800 px; 20,000 ≈ 1.8 GB** | **REFUTED** | ~125 KB; outside the [96, 151] KB bracket implied by the project's own paired 12-photo measurement. 20,000 ≈ 2.5 GB. See C-1. |
| 13 | 800 px "covers the largest slot in a 6-up layout … upscales 1.35×" | **REFUTED (self-contradicted)** | §8.1's own 960×1080 template needs 1.6× (portrait) to 1.8× (landscape) |
| 14 | **Eviction "only ever deletes files outside the current resident set," so nothing on screen can vanish** | **REFUTED** | Two distinct resident sets; renderer's copy is refreshed only after `sync()` returns (`SlideshowActivity.kt:314, 330`). See C-2. |
| 15 | A partial crawl is "discarded, not merged"; the shrink alarm protects | **REFUTED as stated** | `allOk` ignores `truncated` (`AlbumSync.kt:54, 113`); the 0.5 alarm (`:33`) misses pages 34–66 of 67. See C-3. |
| 16 | Index ≈ 3 MB of JSON | **REFUTED (~2.2× low)** | Measured from fixture: 44-char ids, 157-char URLs → 329 B/entry → 6.6 MB |
| 17 | The A/B role-swap trick is documented at the top of `activity_slideshow.xml` | **CONFIRMED** | `app/src/main/res/layout/activity_slideshow.xml:2-11` — and its shared-`Drawable` warning is correctly cited |
| 18 | `PhotoStore`'s "deliberately not an LRU" comment was reasoned for ~300 photos | **CONFIRMED** | `PhotoStore.kt:11-16` says so explicitly ("~92 MB, so there is nothing to evict") |
| 19 | Era mix "physically cannot be six photos from one afternoon" | **REFUTED in the 1-bucket case** | With one year bucket the mode is a no-op. See C-7. |
| 20 | Era mix / recency defaults "do not narrow the library" | **TRUE BUT MISLEADING** | Neither narrows the *library*; era-mix-on narrows a given new photo's share of screen time ~50× vs today. See C-7. |
| 21 | Existing golden-fixture discipline; scrubber exists | **CONFIRMED** | `tools/scrub_fixture.py`; `shared_album_fixture.html` (1.3 MB) is scrubbed — its photo URLs return HTTP 400, so it cannot be used for the C-1 re-measurement |
| 22 | No xsrf token needed for the RPC | **CONFIRMED (not claimed, but load-bearing for §5.2)** | `SNlM0e` absent from `WIZ_global_data`; consistent with the prior review's unauthenticated finding |

---

## What would flip this to APPROVED

1. **C-1** — re-measure 800 px against the live album (the fixture cannot serve this), and
   reconcile the budget. Or adopt **S-1** and delete the question.
2. **C-2** — restate the eviction invariant over `R_sync ∪ R_render` and specify the
   publication/acknowledgement protocol, or adopt S-1's fixed sample.
3. **C-3** — fold crawl completeness into `allOk`, and re-base the shrink alarm against the
   previous *index* size rather than `store.load()`.
4. **C-4** — add a migration section. C9 has to be demonstrated on the upgrade path, not
   asserted.
5. **C-5 / C-6 / C-7** — budget the `SharedAlbumParser` refactor; document the request
   envelope and `bl` rotation; specify bucket→slot mapping for buckets > slots, the relax
   ordering across era/orientation/residency, and the new-photo visibility math.

The diagnosis in §1 is the best part of this document and it is correct — the reach problem
is real, pagination is genuinely the only path to 2019, and §5's probe-as-gate is exactly
the right discipline given how the last review went. §8's living-wall design is thoughtful
and the "template changes hide behind the interlude" observation is a good one. The problem
is concentrated in §6 and §7, where the numbers were derived rather than measured and the
invariants were stated rather than traced.
