# Architecture & Correctness Review — PortalGallery Photo Source Redesign

VERDICT: NEEDS_REVISION

**Reviewed:** `docs/plans/2026-08-17-portalgallery-photo-source-design.md`
**Against:** `album-fixture.html` (1,399,619 bytes), a live re-fetch of the same share URL, and `app/src/main/kotlin/`
**Date:** 2026-08-17

---

## Summary Assessment

The overall shape — delete the auth stack, read a public share page, cache bytes on disk, render from disk — is
sound and is the right call given C1 ∩ C2. But the design's decisive empirical claim is wrong: the share page
returns the **oldest 300 photos in ascending capture order**, not a newest-first rolling window, which means the
selected option does **not** satisfy C2 (auto-refresh) as designed. Separately, the "never blank" invariant has a
hole on first run, and the caching design never says what a cached photo actually is, which makes the invariant
unenforceable as written.

---

## Critical Issues (must fix)

### C-1. The newest-first claim is false. C2 is not satisfied. (§4, §5.4, R5)

This is the load-bearing claim for the entire chosen option, and the fixture refutes it.

The design says (§4):

> | Photo date range | **2026-04-11 → 2026-08-17 (today)** |
> **The window is newest-first.** … C2 is satisfied: new photos will appear.

The `ds:1` payload parses cleanly as JSON. Its photo array has 300 entries, each shaped
`[mediaId, [url, width, height, …], captureTs, …, tzOffset, addedTs, …]`. Walking it:

```
$ python3 /tmp/parse3.py
field2 (capture) inversions: 2/299   range 2026-04-11 13:37 .. 2026-04-12 16:56
field5 (added)   inversions: 16/299  range 2026-04-16 04:41 .. 2026-04-18 07:47
field2 head->tail: 2026-04-11 13:37 -> 2026-04-12 16:56
field5 head->tail: 2026-04-16 04:41 -> 2026-04-18 07:47
months present (capture): Counter({'2026-04': 300})
months present (added):   Counter({'2026-04': 300})
```

Every one of the 300 photos is from April 2026. The array is **ascending** (2 inversions in 299 adjacent pairs),
i.e. **oldest-first**, on both the capture timestamp and the added timestamp.

The album is not a rolling household album. From the page metadata:

```
<meta property="og:title" content="Arizona 2026 · Apr 11 – 17 📸
```

So the album spans Apr 11–17, and the page renders the first ~27 hours of it. This is a **deterministic prefix**,
not a window. A live re-fetch confirms the prefix is stable and identical:

```
$ # re-fetch the canonical share URL and diff against the committed fixture
fixture items: 300  live items: 300
same order & ids: True
same urls: True
continuation token identical: True
live capture range: 2026-04-11 13:37 .. 2026-04-12 16:56
live is ascending-by-capture: 2 inversions/299
```

**Where the design's "today" came from.** §4 counted 13-digit epoch-ms integers anywhere in the HTML and took the
maximum. That maximum lives in the album metadata record, not a photo:

```
$ grep -o ".\{160\}1786999883753.\{80\}" album-fixture.html
…"Arizona 2026",[1775914650000,1776470589000,null,null,1776220607093,
 [1775914650000],[1776470589000],1786999883753,1777135665359],…
```

Only 2 of 314 plausible timestamps in the whole page are from August; 310 are from April. §4's own "minor caveat"
guessed this exactly — *"the 'today' maximum may be a render timestamp"* — and then the design discarded the caveat
and kept the conclusion. That inversion is the root error.

**Consequence.** For a rolling shared album where family members add photos over time, new photos land at the
**end** of the ordering. The share page only ever yields the first 300. New photos would **never** appear. C2 —
a hard constraint — is violated, and R5's "Confirmed newest-first; reframed as product decision" is not a valid
mitigation because the premise is false.

**There is a real fix, and the fixture contains the evidence for it.** The page declares its own pagination RPC:

```
$ grep -oE 'AF_dataServiceRequests[^;]{0,600}' album-fixture.html
AF_dataServiceRequests = {
  'ds:0' : {id:'UJlKrf', request:["AF1QipNlo3…kHfGA","<redacted-share-key>"]},
  'ds:1' : {id:'snAcKc', ext: 7.1837398E7, request:["AF1QipNlo3…kHfGA",null,null,"<redacted-share-key>"]}
}
```

`ds:1`'s response carries a 226-char continuation token at `data[2]` (`AH_uQ40vqJE5rijuNNj0ilAW0ONI0U3F6mkJ…`),
and the request tuple has a `null` in exactly the slot a token would occupy. So page 2 is
`snAcKc` with `[albumId, null, <token>, key]` via `photos.google.com/_/PhotosUi/data/batchexecute`. I was **not
able to execute this call** — the sandbox blocked the outbound POST — so treat it as strong static evidence, not a
verified capability. Verify it before committing to it.

Pick one of these explicitly and record it in §4:

| Fix | Cost | Notes |
|---|---|---|
| **A. Paginate to the tail** via `snAcKc` + token | High | The only option that literally satisfies C2. But it doubles the unofficial surface area (batchexecute envelope, `)]}'` prefix, length-prefixed chunks), and needs ~6 round trips today, growing with the album. |
| **B. Keep the frame album ≤ 300 photos** | ~Zero | The prefix limitation stops mattering. Household still uses the native "add to shared album" flow, so C2's *spirit* holds. Requires occasional pruning. |
| **C. Round-robin N share links**, one per album/trip | Low | Each album's first 300 is plenty; new albums are added by pasting a link. |

**Recommendation:** ship **B or C** as the default and treat **A** as the Phase 4 "helper" work. The design
currently assumes A's benefit at B's cost, which is the worst of both.

---

### C-2. The regex fallback yields 384×512 thumbnails, and the sanity gate cannot detect it. (§5.4)

§5.4 tier 2 specifies `lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+` and calls it *"validated: yields 302 on the
live fixture."* The count is right; the URLs are not usable.

That regex stops at the `=`, so it captures the **bare** URL. 309 of the 341 occurrences in the page are already
bare — the size suffix is added by the page's JS at render time, not present in the markup:

```
$ grep -o 'lh3…/pw/[A-Za-z0-9_-]*=[A-Za-z0-9_-]*' album-fixture.html | sed 's/.*=/=/' | sort | uniq -c | sort -rn
  14 =w54-h72-no
  13 =w96-h72-no
   2 =w41-h72-no
   1 =w600-h315-p-k
   1 =w128-h72-no
   1 =w1200-h1500-p-k-no
```

Fetched bare, the URL is a thumbnail:

```
$ curl -s "$U" -o /tmp/bare.jpg && file /tmp/bare.jpg
/tmp/bare.jpg: JPEG image data, … 384x512, components 3
```

So if tier 1 ever fails and tier 2 takes over, the frame silently degrades to **384×512 postage stamps upscaled
onto a 1920×1200 panel** — and the §5.4 sanity gate ("zero, or <50% of the previous count") will **not fire**,
because the count is still 302. This is precisely the "partial parse goes unnoticed" failure the gate was written
to prevent, arriving through the door the gate doesn't watch.

The regex tier also over-collects. 302 = 300 photos + 2 non-photos:

```
total unique: 302   in ds:1 photo array: 300   extra: 2
  EXTRA suffixes: {'w600-h315-p-k', 'w1200-h1500-p-k-no'}   <- og:image social card (hard crop)
  EXTRA suffixes: {bare}                                    <- ds:0 album cover hero
```

Fixes:
- Tier 2 must append an explicit size suffix, not use the bare URL.
- The sanity gate must include a **resolution check**, not just a count: after a refresh, decode one fetched image
  and assert width ≥ ~1200. A count-only gate is nearly inert here anyway, because the page always returns exactly
  300 — the count is a constant, so the gate will never fire for the most likely markup change.
- Drop the 2 non-photo URLs (filter the `p-k` crop suffix; exclude the `og:image` and `ds:0` cover ids).

---

### C-3. §6 test 1 asserts the wrong number and locks in the wrong parser.

> Commit `album-fixture.html` to `src/test/resources/`. Assert extraction yields 302.

The correct answer for the structured walk (§5.4 tier 1) is **300**. 302 is the regex tier's answer, and it is
wrong by the two non-photo assets in C-2. Writing `assertEquals(302, …)` as the golden assertion either fails the
correct parser or, worse, pressures the implementer to make tier 1 match tier 2's over-collection.

Assert **300** for tier 1 and **300 after filtering** for tier 2, and assert the first entry's capture timestamp and
`4000×3000`-style dimensions so the test also pins the structure, not just the count.

Also note: committing the fixture puts the household's private album — the share key
(`?key=<redacted-share-key>`) plus 302 live photo URLs — into the repo. There is no git repo
here today, so this is a forward-looking hazard, but decide it deliberately rather than by default.

---

### C-4. "The slideshow never makes a blocking network call" does not hold on first run. (§5.3 vs C6)

§5.3 asserts the frame *"renders the on-disk manifest immediately at startup"*. On a cold install there is no
manifest and no cached bytes, so the frame is **blank** until the first refresh lands. §5.9's mitigation — *"Ship a
`BuildConfig` default URL so a fresh install works with no setup"* — supplies a URL, not pixels. C6 ("never go
blank") is violated on first run, and again after any app-data clear.

It is worse than "one cold start", because §5.7 specifies `WorkManager periodic, ~6h`. A `PeriodicWorkRequest` does
**not** run immediately on enqueue; its first execution is scheduled somewhere inside the first period. With a 6h
period a fresh install can sit blank for hours.

Fixes: enqueue a **one-time expedited** refresh alongside the periodic one; render an explicit "first sync…" state
rather than nothing; and consider bundling 3–5 seed JPEGs in `res/raw` so the frame is literally never empty.

---

### C-5. The caching design never says what a cached photo *is*, so the invariant is unenforceable. (§5.5)

§5.5 says *"Cache image bytes, not just URLs … Bounded LRU, a few hundred MB."* It does not say who owns the cache,
what path the manifest stores, or what the slideshow loads from. That matters, because today
`SlideshowActivity.kt:80-84` does:

```kotlin
val url = "${photos[index].baseUrl}=w1920-h1200"
Glide.with(this).load(url)
```

If the new slideshow keeps calling `.load(url)`, the core invariant is only as strong as Glide's default disk LRU
(250 MB, evicts silently) — the slideshow *will* make network calls for any evicted photo, and the design's own
failure taxonomy has no row for it. Also, if the LRU ceiling is smaller than the manifest's total bytes, the
slideshow thrashes: every full pass evicts the photos the next pass needs.

Specify: RefreshWorker downloads to an app-owned directory; the manifest stores **local file paths**; the slideshow
calls `.load(File)`; and **cache capacity must be ≥ manifest size** (or trim the manifest to fit). "Bounded LRU"
and "never blocks on network" are in direct tension unless you state that rule.

**The "few hundred MB" figure is wrong for the design as written.** Measured over 12 real photos from this album:

```
W      H            BARE      W1920 DIMS
3000   4000        34394     133590 900x1200
4000   3000        63094     381334 1600x1200
4000   3000        63271     374266 1600x1200
4000   3000        63466     384105 1600x1200
3000   4000        64449     228103 900x1200
3000   4000        65233     209010 900x1200
1920   1080        47836     245911 1920x1080
3000   4000        85401     307159 900x1200
3000   4000        65013     274866 900x1200
4000   3000        88674     608242 1600x1200
4000   3000        71135     368519 1600x1200
3000   4000        50441     184169 900x1200
avg w1920-h1200 bytes: 308272
```

| Variant | Avg/photo | 300 photos | 1,600 photos (if C-1 fix A) |
|---|---|---|---|
| bare (384×512) | ~62 KB | ~19 MB | ~99 MB |
| `=w1920-h1200` | ~301 KB | **~92 MB** | ~493 MB |
| `=d` (original) | ~2.9 MB | ~880 MB | ~4.7 GB |

At the design's own 300-photo scope and display resolution the real number is **~92 MB**, not "a few hundred MB" —
comfortably within budget. "A few hundred MB" is only right if you paginate to the full album (C-1 fix A). State
the suffix and the resulting figure; right now the sizing is 3× off because the suffix is unspecified.

---

### C-6. The atomic manifest swap is atomic on disk and undefined in memory. (§5.3, §5.7)

Renaming the manifest file atomically is necessary but not sufficient. The design never says how the **running**
slideshow observes a new manifest. Today `photos` is an in-memory `mutableListOf<MediaItem>` populated once in
`onCreate` (`SlideshowActivity.kt:27, :63`). Two failure modes, and the design picks neither:

1. **If the slideshow never re-reads:** refresh runs every 6h and the display updates only on process restart —
   which per C5 is *twice a year*. Auto-refresh would be architecturally present and functionally dead. This is a
   genuinely easy bug to ship given §5.3's framing.
2. **If it does re-read:** `currentIndex` is not re-anchored. A swap to a shorter list leaves
   `photos[index]` (`SlideshowActivity.kt:80`) out of bounds → `IndexOutOfBoundsException` on the next tick. This
   is the same class as the existing `photos.shuffled()` bug §5.7 already flags.

Specify: re-read the manifest at a **pass boundary only** (not mid-crossfade), swap the list and the shuffle order
together, and re-anchor by media id — falling back to index 0 if the current photo is gone.

Related, also unspecified:
- **Concurrent refresh.** Nothing says the periodic work is enqueued as *unique* work
  (`ExistingPeriodicWorkPolicy`). Two overlapping workers writing the manifest and the cache dir will interleave.
  Require unique work **and** write-temp-then-rename for both the manifest and each cached image.
- **Eviction vs. render.** §5.6's last row covers "Image fetch 403 → Glide failure → skip photo" but not "local
  file evicted between manifest read and decode". Add the row, or make eviction only happen from the worker while
  no pass is in flight.

---

## Suggestions

**S-1. `PhotoSource` is the wrong seam more than it is speculative.** A one-method
`suspend fun fetch(): List<Photo>` with one implementation is just a function, and §5.2 already concedes
`HelperSource` won't be built. That's cheap enough to tolerate. The real objection is that it returns **URLs**,
while the architecture's entire point (§5.5) is **bytes on disk**. The seam that carries weight is
`ManifestProvider → renderer`, where the manifest is local file paths. Put the interface there, or drop it and
keep a plain function until the second implementation actually exists.

**S-2. There is a materially simpler design, and it falls out of C-5.** Make the on-disk state a **directory of
JPEGs plus a small index file**. Then "never blank" is not an invariant you have to maintain — it's a property of
`dir.listFiles()`. Refresh becomes: download new files into a temp dir, rename the dir, delete the old one. No LRU
policy, no cache/manifest coherence problem, no eviction race (C-6), and C-4's cold start is the only remaining
special case. At 92 MB total (C-5) you do not need an LRU at all.

**S-3. Prefer the structured walk much more strongly than §5.4 implies.** §5.4 calls the regex *"markedly more
stable than the JSON scaffolding around it."* On this fixture the JSON is trivially walkable and strictly richer —
it yields dimensions and both timestamps, which the regex cannot:

```
["AF1QipOfl5Lhp…", ["https://lh3.googleusercontent.com/pw/AP1GczM1_ikQ…", 4000, 3000, …],
 1775950002016, "eRcElTsunxU…", -25200000, 1776498421701, …]
```

Those extra fields are what let you sort newest-first, skip videos, and enforce the resolution gate in C-2. The
`ds:1` payload extracts with a balanced-bracket scan and `json.loads` with no HTML parsing at all. Treat tier 2 as
a degraded-mode alarm that should page you, not as an equivalent path.

**S-4. Good news the design should bank: the fetch is UA-independent and fully unauthenticated.** Verified across
three User-Agents including OkHttp's default:

```
UA=[okhttp/4.12.0]                     http=200 bytes=1422143 pw_urls=302 ds1=1
UA=[Mozilla/5.0 (Linux; Android 10) …] http=200 bytes=1301560 pw_urls=302 ds1=1
UA=[curl/8.4.0]                        http=200 bytes=1399972 pw_urls=302 ds1=1
```

No cookies, no Referer, no UA spoofing needed — for the page *or* the images. This meaningfully de-risks §5.4 and
justifies deleting `OAuthWebViewActivity`'s UA-spoofing hack (`:32-35`). Worth stating explicitly, since the
current app's WebView UA spoofing might otherwise get cargo-culted forward.

**S-5. §4's album description doesn't match the fixture.** §4 says *"the live household album (1,600 photos)"*.
The fixture is `Arizona 2026 · Apr 11 – 17`, a trip album, and I found **no total-count field anywhere in the
page** — so 1,600 is unverifiable from this artifact. More importantly, a static trip album is the wrong specimen
for testing C2. Capture a second fixture from the actual rolling household album before finalizing; it is possible
(though I'd bet against it) that a contributor-style album orders differently. C-1's refutation stands for *this*
album unambiguously.

**S-6. R4 — one concrete observation the design missed.** §1 lists ANR, the catch block throwing, and heap
pressure. Worth adding: `PhotosApiClient.kt:23-26` builds `OkHttpClient` with **no timeouts configured**, so
OkHttp's 10s defaults apply. A 60s+ spin is therefore *not* a single call timing out — it points at either a
DNS/TLS stall or at `PhotosRepository.getPhotosFromAlbums` (`:15-28`), whose `do/while (pageToken != null)` loop is
**unbounded** across every selected album. Combined with `HttpLoggingInterceptor.Level.BODY`
(`PhotosApiClient.kt:25`) buffering all 16+ full `mediaItems` pages, that is a much better fit for "spins, then
dies" than the album list (one call, `pageSize = 20`). Note also `getAllPhotos` (`:30-39`) checks its `limit` only
*after* the loop body. None of this changes the design — the code is being deleted — but it makes R4 closeable
without a logcat, and it is worth knowing the bug was in the pagination loop rather than the error path before
declaring the class of bug "structurally eliminated" (§5.3).

**S-7. §5.7 memory math is right but pessimistic.** 1920×1200 ARGB_8888 ≈ 9.2 MB is correct. In practice the
server returns *fit-inside* dimensions — `=w1920-h1200` on a 3000×4000 portrait yields **900×1200**, and on a
4000×3000 landscape yields **1600×1200** (see C-5 table). So real decodes are 4.3–7.7 MB at ARGB_8888. The
`RGB_565` recommendation is still right. Also worth noting for §5.9: portrait photos will pillarbox heavily on a
landscape panel; that's a product decision nobody has made yet.

---

## Verified Claims

| # | Design claim | Verdict | Evidence |
|---|---|---|---|
| 1 | 302 unique `lh3 /pw/` URLs | **CONFIRMED** | `grep -o 'lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]*' album-fixture.html \| sort -u \| wc -l` → `302` |
| 2 | 362 total `lh3` references | **CONFIRMED** | 341 `/pw/` + 17 `/a/` (avatars) + `/ogw/` + others = 362 `lh3.googleusercontent.com` occurrences |
| 3 | "≈60 dupes / non-photo assets" | **PARTLY** | 39 are duplicate `/pw/` refs; 17 are `/a/` avatars, correctly excluded by the `/pw/` regex. Avatars are not a contamination risk. |
| 4 | 2 `AF_initDataCallback` blocks | **CONFIRMED** | keys `ds:0` (album meta + 15-item preview strip) and `ds:1` (the 300-photo array) |
| 5 | `AF_initDataCallback` contains a walkable structure | **CONFIRMED, and stronger than claimed** | `ds:1` payload (176,841 chars) `json.loads` cleanly. `data[1]` = 300 entries, each `[id, [url, w, h, …], captureTs, …, tzOffset, addedTs, …]`. `data[2]` = continuation token. |
| 6 | The 302 are all full-size photos | **REFUTED** | 300 are album photos; +1 `og:image` social card (`=w600-h315-p-k`, hard crop); +1 `ds:0` album cover hero. All 302 ids are distinct 120-char `AP1G…` — no crop-duplicates, no avatars. |
| 7 | Regex harvest is a robust fallback | **REFUTED** | Right count, wrong URLs. The bare URL resolves to **384×512** (`file` on the fetched bytes). Silent quality collapse the count-based gate cannot see. See C-2. |
| 8 | Appending `=w1920-h1200` works | **CONFIRMED** | `http=200 bytes=133590 type=image/jpeg`, decodes to 900×1200. Also `=w1920-h1200-no` (identical bytes) and `=d` (2,998,853 B original). It is a **fit-inside bounding box**, not a crop. |
| 9 | Images need no auth/cookies/UA | **CONFIRMED (not claimed, but load-bearing)** | `curl -A "" …` → `http=200 bytes=133590`. Headers: `cache-control: private, max-age=86400`, `etag: "vd51c"`, no expiry token in the URL. |
| 10 | Share page yields the same payload to any client | **CONFIRMED** | okhttp/curl/Chrome-Android UAs all → `http=200`, `pw_urls=302`, `ds1=1` |
| 11 | Page yields a rolling window, not the full album | **PARTLY — "not the full album" yes; "rolling" no** | It is a **deterministic prefix**. Live re-fetch vs. committed fixture: `same order & ids: True`, `same urls: True`, `continuation token identical: True`. |
| 12 | **The window is newest-first** | **REFUTED** | Ascending by capture time, 2 inversions/299. Range `2026-04-11 13:37 .. 2026-04-12 16:56`; all 300 in April. See C-1. |
| 13 | **Photo date range 2026-04-11 → 2026-08-17 (today)** | **REFUTED** | Capture `2026-04-11 → 2026-04-12`; added `2026-04-16 → 2026-04-18`. The Aug-17 max is album metadata, not a photo. 310 of 314 timestamps are April. |
| 14 | **C2 is satisfied; new photos will appear** | **REFUTED** | Follows from 12 + 13. New photos append to the tail; the page returns the head. See C-1. |
| 15 | 313 distinct 13-digit epoch-ms timestamps | **CLOSE** | 314 distinct in `1.4e12 … 1.9e12`. Immaterial. |
| 16 | Album has 1,600 photos | **UNVERIFIABLE** | No total-count field found. `og:title` = `Arizona 2026 · Apr 11 – 17 📸` — a trip album, not the household rolling album. See S-5. |
| 17 | Cache is "a few hundred MB" | **REFUTED (over by ~3×)** | 12-photo measured mean at `=w1920-h1200` = 308,272 B → 300 photos ≈ **92 MB**. See C-5 table. |
| 18 | Gradle wrapper missing (§8.1) | **CONFIRMED** | `gradle/wrapper/` contains only `gradle-wrapper.properties`; `ls gradlew` → `No such file or directory` |
| 19 | Deleting `AlbumPickerActivity` loses the interval slider (§5.9) | **CONFIRMED** | `AlbumPickerActivity.kt:62-67` is the only writer of `prefs.slideshowIntervalSeconds`; `SlideshowActivity.kt:117` is the only reader. |
| 20 | Leaked client secret ships in the APK (§5.1) | **CONFIRMED** | `AuthManager.kt:16-17`, plain constants. `app/build/outputs/apk/debug/app-debug.apk` exists on disk. Revoke it. |
| 21 | §5.7's three carried-forward slideshow bugs | **CONFIRMED, all three** | No `Glide.clear()` on the previous `CustomTarget` (`SlideshowActivity.kt:82-104`); `isFirstPhoto` never clears if the first load fails (`:30, :86-90` — there is no `onLoadFailed` override at all); `shuffled()` runs once (`:63`). |

---

## What would flip this to APPROVED

1. **C-1** resolved: pick and document a C2 strategy (pagination, ≤300 album, or multi-link), and correct §4's
   date-range and ordering findings. This is the blocker; everything else is repairable during implementation.
2. **C-2 / C-3**: regex tier appends a size suffix and filters the 2 non-photos; sanity gate adds a resolution
   check; golden test asserts 300.
3. **C-4 / C-5 / C-6**: specify the expedited first refresh, the on-disk representation (local paths, capacity ≥
   manifest), and the in-memory swap protocol (pass boundary, re-anchor by id, unique work).

The bones are good — deleting the auth stack is unambiguously right, "render from disk" is the correct core
invariant, and §5.6's failure taxonomy is the right instinct. The problem is that the one claim the whole option
rests on was read off a `grep` for large integers instead of off the parsed structure, and it happens to be wrong.
