# Risk & Feasibility Review — Collage Mode and the Full Archive

VERDICT: NEEDS_REVISION

**Reviewed:** `docs/plans/2026-09-09-collage-and-full-archive-design.md`
**Against:** `album-fixture.html` (raw, gitignored), `app/src/test/resources/shared_album_fixture.html`
(committed, scrubbed), `tools/scrub_fixture.py`, `tools/check_album_ordering.py`,
`app/src/main/kotlin/**`, and both prior reviews.
**Method note:** web fetch/search unavailable, so every ToS, robots.txt and rate-limit
statement below is labelled with confidence and marked as needing verification. All
fixture numbers are first-hand and reproducible.

---

## Summary Assessment

**The central claim survives.** The apparent contradiction between the two prior reviews is
not a contradiction — both are literally true, and the architecture review's reading is the
load-bearing one: the fixture *does* declare the `snAcKc` RPC and *does* carry a 226-char
continuation token at `ds:1` `data[2]`. The design is buildable. What it is not is safely
buildable as written: the failure posture has a concrete path where a silently-truncated
crawl looks like a successful one and `prune()` deletes thousands of photos (a C8
violation), the ToS section cites the milder of the two things the prior review said and
converts "must be redone" into "accepted", and the new 800 px tile tier lands exactly on an
existing abort threshold with zero margin.

---

## Resolving the contradiction (the task's question 1)

Both prior reviews are correct. They are describing different strings.

| String | `album-fixture.html` (raw) | `app/src/test/resources/shared_album_fixture.html` (committed) |
|---|---|---|
| `AF_dataServiceRequests` | **2** | **2** |
| `snAcKc` | **1** | **1** |
| `UJlKrf` | **1** | **1** |
| `/_/PhotosUi` | 3 | 2 |
| `batchexecute` | **0** | **0** |
| `rpcids` | **0** | **0** |
| `data/batchexecute` | **0** | **0** |

Verbatim from the raw fixture:

```
AF_dataServiceRequests = {'ds:0' : {id:'UJlKrf',request:["AF1QipNlo3…kHfGA","emFaR3h3…QU5R"]},
                          'ds:1' : {id:'snAcKc',ext: 7.1837398E7 ,request:["AF1QipNlo3…kHfGA",null,null,"emFaR3h3…QU5R"]}}
```

So:

- **`design-review-architecture.md:86-94` is correct and is the important finding.** The
  page names the RPC (`snAcKc`), gives the exact request tuple, and the tuple has nulls in
  the slots a token would occupy.
- **`design-review-risk.md:83-84` is also literally correct** — the strings `batchexecute`,
  `rpcids` and `/_/…/data/…` genuinely do not appear. What is wrong is the *inference* the
  risk review drew from that absence: *"paginating would mean reverse-engineering the RPC
  out of Google's external `wiz` bundles"* (`design-review-risk.md:84-85`). It would not.
  The rpc id and the argument tuple are handed to you in the markup. Only the *endpoint
  path* and the `f.req` / `rt=c` calling convention come from framework knowledge — and
  even the path prefix is in the page: `"Im6cmf":"/_/PhotosUi"` and `"eptZe":"/_/PhotosUi/"`.
  The design's `photos.google.com/_/PhotosUi/data/batchexecute` (§5.1) is that prefix plus
  the standard `wiz` suffix. The `data/batchexecute` half is convention, not evidence.

**The scrubber did not cause the absence** (the task's question 3). `tools/scrub_fixture.py`
rewrites only: `/pw/` slugs, `/a/` avatar paths, `?key=` values, `AF1Qip…` ids, 21-digit Gaia
ids, contributor names, the `photos.app.goo.gl` link, and the `og:title` meta tag. It never
touches `batchexecute` or `rpcids`, and the raw pre-scrub file has zero of both. Absence is
genuine. Correct instinct to ask, but it is not the explanation here.

**Correction to the task framing:** `album-fixture.html` is *not* the committed golden
fixture. It is the raw capture, and `.gitignore:4` excludes it (`git log -- album-fixture.html`
is empty; it still contains the live share key `emFaR3h3…`). The committed fixture is
`app/src/test/resources/shared_album_fixture.html`. C-3 from the prior review was handled
properly.

### Continuation token (the task's question 2) — CONFIRMED

Parsed from **both** files, identically:

```
ds:1 data → 6 elements: [None, list, str, list, None, int]
data[1]  = 300 entries
data[2]  = str, len 226, charset [A-Za-z0-9_-] (base64url)
           'AH_uQ40vqJE5rijuNNj0ilAW0ONI0U3F6mkJhqFuXOtNWVkgqh3f8qJKDyJFvOeQSU09xVFvJIeE…
            …z-Z5HomyPhtzsV2IU2QtQ4NeXMKse3zkct_upnA'
```

Matches `design-review-architecture.md:96` exactly (226 chars, `AH_uQ40vqJE5rijuNNj0ilAW0ONI0U3F6mkJ…`).
`SharedAlbumParser.kt:116-118` already extracts it into `Result.continuationToken`.

---

## Critical Issues (must fix)

### R-1. A silently truncated crawl is indistinguishable from a complete one, and `prune()` will eat the library. (§5.4, C8)

This is the most serious issue in the document.

§5.4 says *"`prune()` deletes only items absent from a **fully successful** crawl."* The
design never defines how the crawler decides a crawl was fully successful. The only signal
available is *the page returned no continuation token*. So "complete" and "the endpoint
stopped giving me a token" are the same observation.

Construct the failure: the RPC returns HTTP 200 with a well-formed payload, 300 entries, and
`data[2] == null` at page 40 of 67 — because of a soft rate limit, a shard hiccup, an
account-state change, or a future server-side page-count cap. The crawler concludes the
album has 12,000 photos. Now walk the existing guards:

- **Shrink alarm** (`AlbumSync.kt:114`, `SHRINK_ALARM = 0.5`): `12000 < 20000 * 0.5` is
  **false**. Does not fire. §5.4 says "the existing shrink alarm moves to the index" — moving
  it does not help, because a 0.5 ratio cannot catch a 40% truncation. At 300 items the
  threshold was reasonable; at 20,000 it leaves a 10,000-photo hole.
- **`allOk`** (`AlbumSync.kt:113`) is derived from per-album `error == null`. Every request
  returned 200. `allOk` is **true**.
- **`prune()`** (`AlbumSync.kt:204` → `PhotoStore.kt:102-106`) deletes every file whose id is
  not in the keep set. **8,000 photos deleted.**

C8 — *"a failed sync never reduces what is on disk"* — is violated, silently, with no error
surfaced anywhere. And §3.1's reassurance that *"a broken RPC degrades to no new photos,
never to a blank frame"* is only true for a *loudly* broken RPC. A quietly truncating one
degrades to mass deletion.

The design's own §5.4 bullet — *"a partial crawl is discarded, not merged"* — is the right
rule and does not help, because this crawl does not look partial.

Required:
- State the **completeness contract** explicitly. At minimum: record `pagesFetched`,
  `itemsSeen`, and last-known-good totals; treat *any* index shrink beyond a small absolute
  tolerance (not a 0.5 ratio) as suspect; require a second confirming crawl before any
  deletion of more than N items.
- Make `prune()` **budget-limited**: never delete more than a few hundred items in one sync
  without an explicit confirmation pass. A family album does not lose 8,000 photos in six
  hours; if the crawl says it did, the crawl is wrong.
- Add to §10 the question this exposes: **how does the endpoint signal end-of-album, and how
  is that distinguishable from a mid-crawl stop?** The probe (§5.2) must answer this — it is
  more important than "does page 2 work."

### R-2. §3.1 undersells the ToS risk, and cites the weaker of the two things the prior review said.

The quotation itself is accurate. `design-review-risk.md:179-181` reads:

> Also worth a sentence: this posture is contingent on *not* paginating. If C-2 tempts
> anyone toward the `batchexecute` RPC later, the ToS analysis changes materially and must
> be redone.

Three problems with how §3.1 handles it.

**(a) "Must be redone" is not "accepted."** The prior review asked for an *analysis*. §3.1
supplies a *decision*. Those are different artifacts, and substituting one for the other is
the specific move the prior review cycle was created to catch.

**(b) It does not engage `design-review-risk.md:84-86`**, which is the stronger statement and
goes unquoted:

> …paginating would mean reverse-engineering the RPC out of Google's external `wiz`
> bundles — materially harder and far more fragile than the regex harvest, and **a much
> stronger ToS problem than fetching a page**.

§3.1's counter — *"Pagination is an increment on that foundation, not a new category of
exposure"* — is the claim under dispute, asserted rather than argued. It is also weaker than
it looks. Fetching a share URL is retrieving a document a browser would retrieve for you.
POSTing a hand-constructed `f.req` to `/_/PhotosUi/data/batchexecute` is invoking an
application's internal API using a calling convention that (per the fixture: zero occurrences
of `batchexecute` or `rpcids`) is *not* published on the page. That is a different category,
whatever one concludes about the risk.

**(c) The volume argument silently reverses and is never stated.** The prior review's
strongest ToS point was quantitative (`design-review-risk.md:170-172`): *"WorkManager at 6h =
**4 requests/day, one device, one URL**. That is not crawling by any reasonable reading."*
The design's §5.3 replaces that with ~67 sequential requests up front, a full ~67-request
re-crawl **weekly**, plus page-1 polling — roughly **4,000 requests in year one**, throttled
to one per second, walking an album from end to end. That is a crawl by any reasonable
reading. §3.1 never states the number, so the reader cannot see that the prior review's
best defence has been discarded.

Also still outstanding from `design-review-risk.md:168-169`: *"Fetch
`https://photos.google.com/robots.txt` and record what it says about `/share/`. One
command."* Unchecked (I cannot check it either — web access blocked). Under the old design
it was a nice-to-have. Under a 67-page weekly crawl it is the load-bearing fact, because the
Google ToS clause the prior review quotes is *conditioned on* robots.txt
(confidence: high on substance, medium on current wording — verify).

To be fair to the design: for a single household, read-only, on content the user owns, this
is a reasonable risk to accept. **The objection is not the decision, it is the record.** Say
plainly: "this is now ~4,000 requests/year to an undocumented internal endpoint; robots.txt
is unchecked; we accept this for personal use." That is honest. "An increment on that
foundation, not a new category of exposure" is not.

### R-3. `=w800-h800` tiles collide with `MIN_PLAUSIBLE_EDGE = 800`, and the collision aborts the whole sync.

§6.1 specifies tiles *"fetched fit-inside an 800 px box via the existing `=w800-h800`
suffix."* `AlbumSync.kt:30` sets `MIN_PLAUSIBLE_EDGE = 800`, and `isPlausiblePhoto()`
(`AlbumSync.kt:281-290`) rejects anything with `max(w,h) < 800`.

Measured on the committed fixture: `=w800-h800` is fit-inside, so for every one of the 300
photos the resulting long edge is **exactly 800**. The gate passes by a margin of **zero
pixels**.

Now apply §1.2's own premise — a 2019–2026 family album *"that receives scans and back-dated
uploads."* Any source whose long edge is under 800 px (a scan, a screenshot, a
messaging-app-forwarded JPEG, an early phone photo) comes back at native size, e.g. 640×480.
Then, at `AlbumSync.kt:151-157`:

```kotlin
if (!photo.isVideo && !resolutionChecked) {
    resolutionChecked = true
    if (!isPlausiblePhoto(bytes)) {
        failures = Int.MAX_VALUE          // aborts the entire sync
```

The whole sync returns `Result.Failure("resolution gate failed — refusing to index
thumbnails")` (`AlbumSync.kt:170`). Worse, the gate samples **whichever still finishes
first** among `CONCURRENCY = 4` racing coroutines, so whether it trips is nondeterministic
across runs. The symptom is an archive that syncs fine some days and fails with a scary
"refusing to index thumbnails" on others.

The fixture cannot rule this in or out — it is a 2026 phone-only trip album, long edges
1920–8272, zero entries under 800. The frame album is the one at risk and has not been
measured.

Fix: separate the two thresholds. The gate exists to catch *bare-URL 384×512 thumbnails*
(`SharedAlbumParser.kt:21-24`); it should compare the fetched size against the **requested
box** and the **indexed dimensions**, not against a constant that now equals the request.
And a single anomalous photo should be skipped, not made fatal to a 20,000-item sync.

### R-4. No rate-limit handling, and "discard the partial crawl" amplifies under backpressure.

§5.3 gives a throttle (≈1 page/sec) and nothing else. There is no `429` path, no
`Retry-After`, no exponential backoff, no jitter, no daily request budget, no detection of a
consent/CAPTCHA interstitial, and no resume.

Combine that with §5.4's "a partial crawl is discarded, not merged" and the existing
six-hourly `refreshLoop()`, and you get an amplifier: a 429 at page 60 throws away 59 pages
of work and the next attempt re-issues **all 67** — increasing load on the endpoint at
precisely the moment it asked for less. Four times a day. That is the behaviour most likely
to convert a soft rate limit into a hard block, and a hard block on `/share/` is the tail
risk the prior review correctly called terminal (`design-review-risk.md:240-242`).

An interstitial is worse than a 429, because it arrives as HTTP 200 with a parseable-looking
body and feeds straight into R-1.

Required, and none of it is expensive:
- Explicit `429` / `5xx` handling with capped exponential backoff plus jitter, honouring
  `Retry-After`.
- A hard daily request budget; when exhausted, stop and keep the previous index.
- **Resumable crawl state** (page N + token, persisted) so backoff is cheap. This directly
  contradicts "discard, not merged" — reconcile them: resume the *fetch*, but still only swap
  the index atomically on completion.
- A positive assertion that the payload is a photo page, not an interstitial, before treating
  a token-less response as end-of-album.

The design's own §10 open question — *"is the continuation token stable across syncs, or
does it expire?"* — is a prerequisite for resumability and is currently unanswered, so the
resumable design cannot be specified until the probe runs. That is fine; say so.

### R-5. §5.3's "steady state is a single request" is unsound, and a page-1 refresh must be barred from pruning.

Two defects.

**It contradicts §1.2.** Ordering is by *capture* date. §1.2 establishes that a back-dated
photo lands at position ~19,000 and *"is not an edge case"* for this album. So a page-1-only
refresh structurally cannot see new arrivals of exactly the kind §1.2 says to expect. §5.3
half-acknowledges this with "Weekly: a full re-crawl", but then still labels the single
request "steady state." The honest framing is: **steady state is a weekly 67-page crawl**,
with page-1 polling as a low-latency optimisation for newly-*taken* photos only. That
matters because the weekly crawl, not the single request, is what sets the ToS volume (R-2)
and the rate-limit exposure (R-4).

**It rests on an unproven premise.** `2026-08-17-…-design.md:332-336` states the
newest-first ordering as a *"**Working hypothesis (not proven)**: the page reflects the
album's own sort order as configured in Google Photos"* and adds *"If so, the ordering is
**user-controllable**."* User-controllable is the problem: one family member changing the
album's sort order in the Google Photos UI silently inverts page 1 from newest to oldest,
and nothing in the design detects it. The frame would keep reporting successful syncs while
never seeing another new photo. Add a cheap invariant — after a page-1 refresh, assert the
head entry's capture time is ≥ the previous head's — and surface it.

**And the deletion hazard:** nothing in §5.4 says a page-1 refresh must never call `prune()`.
With the current code path (`AlbumSync.kt:113` `allOk` = "no HTTP error", `:204`
`prune(entries)`), a 300-item page-1 result against a 20,000-item store would trip the shrink
alarm (300 < 10,000) and be rejected — so today it fails safe by accident. Do not leave that
to accident. State it: **incremental refresh merges and never deletes; only a completed full
crawl may prune.**

### R-6. "Entry decoding is reused, not duplicated" is not true of the current code.

§5.1 asserts the RPC's inner payload is *"the same `ds:1` array shape that
`SharedAlbumParser.structured()` already handles"* and concludes *"entry decoding is reused,
not duplicated. `AlbumPager` owns only the envelope."*

The shape claim is right. The reuse claim is not, as the code stands. `structured()`
(`SharedAlbumParser.kt:91-121`) fuses three responsibilities: it locates the payload via
`extractDsRaw(html, "ds:1")`, which is HTML-specific — `html.indexOf("key: 'ds:1'")` at
`SharedAlbumParser.kt:125` — then maps entries inline, then builds a `Result` carrying
`albumTitle` scraped from an `og:title` meta tag. None of that exists in a batchexecute
response. There is no public entry-mapping function to call.

This is a small refactor (split `structured()` into `locate` / `mapEntries` / `assemble`),
but the design books the saving without booking the work, and the fused version is exactly
the kind of thing that gets copy-pasted under deadline — at which point the `VIDEO_META_KEY
= "76647426"` heuristic (`SharedAlbumParser.kt:212`) and the `getOrNullLong` epoch guard
(`:231`) exist in two places and drift. Name the refactor as a step.

### R-7. There is no phasing, so the presentation work is gated on the reach work for no reason.

The prior design had a phasing section (`2026-08-17-…-design.md` §8). This one does not, and
§5.2 makes the RPC probe *"a go/no-go gate on the rest"* (line 4-5).

But most of the value here does not depend on the RPC:

- §8.1's orientation-tagged layout slots are *"the mechanism that recovers the 56% portrait
  photos"* (§1.3). That works on the existing 300 photos, today.
- §7's `CollageSelector`, §8.2's living wall, §8.3's hero interludes, §6.3's generational
  eviction — all independent of reach.
- Only §4's `AlbumIndex`, §5's `AlbumPager` and §6's two-tier budget need the RPC.

Gating all of it behind an unverified probe means a probe failure discards the ~60% of the
design that would have shipped regardless. Given that the probe is genuinely uncertain
(the design says so; `design-review-architecture.md:98-100` says so), that is a poor bet.

Split it: **Phase 1 = collage on the current 300** (delivers the orientation recovery
immediately, and gives §7/§8 a real testbed), **Phase 2 = reach**, gated on the probe. This
also makes C9 ("day one is strictly better than today") verifiable at each step instead of
only at the end.

---

## Suggestions

**S-1. The scrubber is not fit for an RPC fixture, and §5.2 assumes it is.** §5.2 says *"scrub
the response via the existing `tools/scrub_fixture.py` and commit it as a golden fixture."*
Three problems, all verified against the current committed fixture:

- **It changes byte length.** Raw 1,399,601 → scrubbed 1,399,570 (**−31 bytes**), from the
  `og:title` and contributor-name replacements (`scrub_fixture.py:80-92`), which are the only
  rules that do not preserve length. Harmless in HTML. **Fatal in a batchexecute response**,
  which is *length-prefixed* — §5.1 step 2 and the §9 test *"Envelope parsing: `)]}'`, chunk
  framing"* would then be validating a fixture whose framing no longer matches its own
  prefixes.
- **Its leak check is vacuous by construction.** `scrub_fixture.py:104-116` only searches for
  values it collected itself. If the contributor-name regex
  (`\["([A-Z][a-z]+ [A-Z][a-z]+)",1,null`, `:80`) does not match the RPC payload's shape,
  `names` is empty, the loop finds nothing, and it prints
  `"scrub verified: no gaia ids, share links, or contributor names remain"` on a file it
  failed to scrub. A green message on an unscrubbed fixture is worse than no message.
- **It already misses things in the HTML fixture.** The committed
  `shared_album_fixture.html` still contains: the real album title **"Arizona 2026" ×6**
  (only the `og:title` meta tag is rewritten, not the copy inside `ds:1 data[3]`, the
  `data-title` attribute, or the DOM text); the **226-char continuation token** verbatim; all
  **300 per-entry 27-char tokens** (`entry[3]`) verbatim; and an unscrubbed
  `video-downloads.googleusercontent.com/ADGPM2mm5Ryauin7…` URL. None of these open the album
  on their own — the share key and album id *are* correctly scrubbed — so this is a
  low-severity leak today. But it is the same class of gap that C-3 was raised for, and the
  RPC response will be far denser in tokens than the HTML is.

Before §5.2 commits an RPC fixture: add length-preserving name/title replacement, add an
allowlist-style verifier (assert nothing matching high-entropy token patterns survives,
rather than asserting known-bad strings are gone), and make the script *fail* rather than
print success when it finds no instances of a category it expected.

**S-2. `AlbumIndex` at 20,000 entries is ~6.5 MB, not ~3 MB — and that changes §10's answer.**
Measured from a real fixture entry, JSON-encoding exactly the fields §4 lists (id 44 chars,
baseUrl 157 chars, w, h, captureMs, addedMs, isVideo, album): **326 bytes/entry → 6.52 MB**.
§4's "~3 MB" is ~2× low, and §10's "Gson parsing 20k entries is ~200–400 ms" is
correspondingly optimistic. Note also that `saveIndex()` (`PhotoStore.kt:77-85`) does
`gson.toJson(entries)` → a ~6.5 MB `String` (13 MB as UTF-16) → `.toByteArray()` → another
6.5 MB, i.e. ~20 MB transient on every sync against a 256 MB heap limit. Survivable, but
"JSON until measured otherwise" is doing more work than the number suggests. Measure before
deciding; the answer is closer to SQLite than §10 implies.

**S-3. Two deleters, one directory, no arbiter.** §5.4 keeps `prune()` (deletes anything
outside the crawl result) and §6.3 adds generational eviction (deletes anything outside the
resident set). Both operate on `photosDir` (`PhotoStore.kt:23`), and the resident set is a
*subset* of the crawl result. `prune()`'s current contract — `keepIds` == the whole index ==
everything wanted — no longer holds. Say which one owns deletion. My suggestion: **one
deleter**, taking `keepIds = residentSet ∪ pinned`, called only after a completed crawl.
Note also that `prune()` is non-recursive and uses `substringBeforeLast('.')`
(`PhotoStore.kt:104`), so a `heroes/` subdirectory would be evaluated as an id named
`"heroes"`, fail the keep test, and hit `delete()` — which returns `false` on a non-empty
directory, so it fails safe today, silently and by luck. Give heroes an explicit path scheme.

**S-4. The existing 300 files are at `=w1920-h1200`; the new tile tier is `=w800-h800`. No
migration is specified.** C9 says day one must be *"strictly better than today, never
worse."* If the switchover re-downloads the existing 300 at 800 px and overwrites, disk
quality *decreases* for the set the frame currently shows. If it leaves them, `fileFor(id)`
(`PhotoStore.kt:53`) returns one path for both tiers and the store cannot tell a 1920 file
from an 800 file. State the migration: keep the existing 300 as the initial hero pool (they
are already at hero resolution — that also gives §6.2's pool a free warm start and satisfies
C9 by construction).

**S-5. Concurrency bugs that 20,000 items will surface.** `done`, `failures` and
`resolutionChecked` (`AlbumSync.kt:122-124`) are plain `var`s mutated from `CONCURRENCY = 4`
concurrent coroutines (`:136`, `:145`, `:151-152`, `:161`) with no synchronisation. `++done`
and `failures++` are read-modify-write. At 300 downloads lost updates are cosmetic; at 20,000
they are frequent, and `failures` is both the abort sentinel (`failures == Int.MAX_VALUE`,
`:165`, `:169`) and an input to the reported `added` count (`:209`) — a concurrent `failures++`
against `Int.MAX_VALUE` overflows to `Int.MIN_VALUE` and the abort is lost. Use
`AtomicInteger`/`AtomicBoolean` before scaling this loop by 66×.

**S-6. The request tuple has *two* null slots and §5.1 says "the slot".** Verbatim:
`request:["AF1Qip…",null,null,"<key>"]`. §5.1 says *"the same call with the token in the slot
that currently holds `null`"* — singular. `design-review-architecture.md:98` guesses index 2
(`[albumId, null, <token>, key]`). It is a guess. Make the probe test both and record the
answer in the design; it costs one extra request and removes an ambiguity that would
otherwise be discovered as a mysterious empty page 2.

**S-7. Missing from §10.** The five open questions are reasonable but omit the ones that
decide feasibility:
- **How is end-of-album signalled, and how is it distinguished from a mid-crawl stop?** (R-1 —
  the single most important unasked question.)
- What is the *minimal* request? Cookies, `at` token, `Referer`, `Origin`, `rt=c`? The prior
  review's S-4 finding that the *page* is UA-independent and unauthenticated
  (`design-review-architecture.md:300-311`) does **not** transfer to a POST endpoint; verify
  it separately.
- Is pagination consistent under concurrent mutation? A 67-page crawl takes minutes; a photo
  added mid-crawl can shift offsets and cause a skip or a duplicate. Offset-based cursors
  usually do; token-based ones sometimes snapshot.
- What does the endpoint do at N requests? (R-4.)
- Thermal and power behaviour: 20,000 sequential downloads on an always-on wall device,
  measured once.
- Rollback: if the family prefers the single-photo frame, is collage a preference or a
  rewrite? §8.3 reuses `iv_photo_a`/`iv_photo_b`, so the machinery survives — say so and make
  it a toggle.

**S-8. Privacy deserves a section; the design has none.** This is a material escalation and
it is invisible in the document:

- Today the device holds ~300 photos covering a rolling three months. The design puts **the
  household's entire 2019–2026 photographic record — ~20,000 images, ~1.9 GB — unencrypted in
  `filesDir`** on an always-on device in a living room. Android app-private storage is not
  encrypted at rest beyond FBE and is readable to anyone with root, a recovery image, or the
  physical device. Portal hardware is discontinued and no longer receives security updates
  (confidence: high, worth verifying) — an unpatched, network-connected, camera-and-mic device
  is a poor host for a complete family archive.
- The `AlbumIndex` is separately sensitive. §4 stores capture timestamps for all 20,000 items,
  and the `ds:1` entries also carry a per-photo timezone offset (`entry[4]`). Seven years of
  timestamped, timezone-tagged events is a travel and routine history, independent of the
  images.
- Consequence for disposal: the device can no longer be handed on or resold without a factory
  wipe, and nothing in the design says so.

None of this blocks the work. It is one paragraph the design owes the reader, plus a
recommendation (e.g. cap the archive, or accept explicitly).

**S-9. Small things.**
- §1.1's table cites `2026-08-17-…-design.md` §9a accurately, but §9a labels the ordering
  hypothesis *"not proven"* and §1.1 presents it as measurement. Carry the caveat forward —
  it is load-bearing for §5.3 (R-5).
- §1.3 says "168 portrait / 132 landscape"; `design-review-risk.md:254` says 170/132. I
  measure **168 / 132** from `ds:1` on the committed fixture. §1.3 and §9b are right; the
  prior review's 170 was a URL-level count. Worth a footnote so nobody "fixes" it back.
- §6.1's ~90 KB/tile is unmeasured but plausible: scaling the architecture review's measured
  301 KB mean at `=w1920-h1200` by the ~0.25 area ratio gives 75–110 KB. Measure it during
  the probe; the whole 1.8 GB budget hangs off this one number.
- `tools/check_album_ordering.py:126-130` already prints "paginate via the batchexecute RPC"
  as one of three verdict branches. Once §5.2 runs, extend that tool with the probe rather
  than writing a throwaway — it already has the `ds:1` extraction and the token check.

---

## Verified Claims

First-hand and reproducible. RAW = `album-fixture.html` (gitignored), COMMITTED =
`app/src/test/resources/shared_album_fixture.html`.

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Fixture declares the `snAcKc` pagination RPC (`design-review-architecture.md:86-94`) | **CONFIRMED** | `AF_dataServiceRequests` ×2 in both files; `'ds:1':{id:'snAcKc', ext:7.1837398E7, request:["AF1Qip…",null,null,"<key>"]}` |
| 2 | Initial HTML contains no `batchexecute` / `rpcids` / `/_/…/data/…` (`design-review-risk.md:83-84`) | **CONFIRMED, literally** | 0 occurrences of each, in RAW *and* COMMITTED |
| 3 | The two prior reviews contradict each other | **REFUTED** | Both true; they describe different strings. The risk review's *inference* (must reverse-engineer `wiz` bundles) is what is wrong — see the resolution section |
| 4 | Scrubbing removed the RPC strings | **REFUTED** | `scrub_fixture.py` touches only slugs/avatars/keys/`AF1Qip` ids/Gaia/names/share-link/`og:title`. RAW has 0 `batchexecute` pre-scrub |
| 5 | Continuation token at `ds:1` `data[2]` | **CONFIRMED** | `str`, **226 chars**, base64url `[A-Za-z0-9_-]`, `AH_uQ40vqJE5rijuNNj0ilAW0ONI0U3F6mkJ…_upnA`. Byte-identical in RAW and COMMITTED |
| 6 | `ds:1` shape | **CONFIRMED** | 6 elements: `[None, list(300), str(226), list(album meta), None, 0]` |
| 7 | Endpoint path prefix present in page | **CONFIRMED (new)** | `"Im6cmf":"/_/PhotosUi"`, `"eptZe":"/_/PhotosUi/"`. The `data/batchexecute` suffix is framework convention, not fixture evidence |
| 8 | `album-fixture.html` is the committed golden fixture | **REFUTED** | Untracked; `.gitignore:4`. Still holds the live share key. Committed fixture is `app/src/test/resources/shared_album_fixture.html` (1,399,570 B) |
| 9 | §1.3 "168 portrait / 132 landscape" | **CONFIRMED** | 168/132 from `ds:1` dimensions |
| 10 | §5.3 "~67 round trips" | **CONFIRMED** | 20,000 / 300 = 66.7 |
| 11 | §4 "~3 MB of JSON" for 20,000 entries | **REFUTED (~2× low)** | 326 B/entry measured → **6.52 MB** |
| 12 | §6.1 `=w800-h800` yields long edge exactly 800 | **CONFIRMED** | fit-inside; true for all 300 fixture entries. Equals `MIN_PLAUSIBLE_EDGE` (`AlbumSync.kt:30`) exactly — see R-3 |
| 13 | Fixture has photos below the 800 px gate | **NOT PRESENT HERE** | long edges 1920–8272, min 1920, zero under 800. Does not clear the frame album (2019–2026, per §1.2) |
| 14 | Shrink alarm catches a 40% truncated crawl | **REFUTED** | `SHRINK_ALARM = 0.5` (`AlbumSync.kt:33`); 12,000 vs 20,000 does not trip it. See R-1 |
| 15 | `prune()` deletes any unlisted file | **CONFIRMED** | `PhotoStore.kt:102-106`, non-recursive, `substringBeforeLast('.')` |
| 16 | `structured()` is reusable on an RPC payload as written | **REFUTED** | `extractDsRaw` keys on `html.indexOf("key: 'ds:1'")` (`SharedAlbumParser.kt:125`); entry mapping is inline and private (`:97-114`). See R-6 |
| 17 | Scrub preserves byte length | **REFUTED** | 1,399,601 → 1,399,570 (**−31 B**). Matters for length-prefixed RPC fixtures — see S-1 |
| 18 | Committed fixture is fully scrubbed | **PARTLY** | Share key, album id, slugs, Gaia ids, names, share link, `og:title` — scrubbed. Still present: "Arizona 2026" ×6, the 226-char token, 300 `entry[3]` tokens, one `video-downloads…` URL |
| 19 | §3.1 quotes `design-review-risk.md:179` accurately | **CONFIRMED** | Quotation is faithful. The objection is selection and framing, not accuracy — see R-2 |
| 20 | Newest-first ordering is established fact | **UNPROVEN** | `2026-08-17-…-design.md:332-336` labels it a *"Working hypothesis (not proven)"* and *"user-controllable"*. §1.1/§5.3 present it as measurement |
| 21 | `robots.txt` for `photos.google.com` | **UNCHECKED** | Web access blocked here too. Still the load-bearing ToS fact, now more so |

---

## The single most likely way this project fails

Not the probe. The probe is the well-managed risk in this document — it is named, gated, and
has a stated stop condition (§5.2).

**The most likely failure is that the crawl works, and then quietly stops working.** Some
week — a soft rate limit, a shard hiccup, a server-side page cap — the RPC returns a clean
token-less page 40. Every guard in the current pipeline passes: HTTP 200, `allOk == true`,
`12000 > 20000 × 0.5`. `prune()` deletes 8,000 photos. No error is logged, no alarm fires,
and the frame keeps cycling — just from a smaller pool. The family notices months later, if
at all, and by then there is no signal left to diagnose from. That is R-1, and it is the one
issue I would fix before writing any other line of this design.

The runner-up is organisational, not technical: **the design has no phasing** (R-7), so a
probe failure takes the collage, the layout templates and the 56% orientation recovery down
with it — none of which needed the RPC. A project that could have shipped most of its value
in week one instead ships nothing.

---

**Bottom line.** The central claim checks out — clearly and with evidence. The fixture
declares `snAcKc`, the token is real and 226 characters, and `design-review-risk.md`'s
"absent" finding was about strings that were never the point. This design is not repeating
the last cycle's mistake. Its problem is the opposite one: having established that it *can*
paginate, it has not done the work of specifying what happens when pagination goes wrong —
and the existing `prune()`/shrink-alarm machinery, sized for 300 photos, fails open at
20,000. Fix R-1 and R-3, rewrite §3.1 with the request volume actually stated, add backoff
and a phase split, and this is approvable.
