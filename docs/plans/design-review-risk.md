# Design Review — Risk, Product Fit, Prior Art

VERDICT: NEEDS_REVISION

**Reviewer note on method:** web search/fetch are blocked by policy hook in this
environment (`META_CLAUDE_ENABLE_WEB_TOOLS` unset), so every prior-art and ToS
statement below is labelled with explicit confidence and marked as needing
verification. The fixture analysis is first-hand and reproducible — every number
in §"Verified Claims" was re-derived from `/Users/bnepani/PortalGallery/album-fixture.html`.

## Summary Assessment

Section 4 "Empirical validation" is wrong in a way that invalidates the option
matrix: the fixture is a **closed April trip album** ("Arizona 2026 · Apr 11 – 17"),
every one of its 300 photos was taken in a 27-hour window four months ago, and the
"today" timestamp cited as proof of auto-refresh is a page-render value — exactly the
caveat the author raised and then dismissed. C2 (auto-refresh) is the constraint that
eliminated every supported option, and there is currently **zero evidence** the
selected option satisfies it either.

## Critical Issues (must fix)

### C-1. The evidence for C2 does not exist. The core bet is unvalidated.

§4 claims `Photo date range 2026-04-11 → 2026-08-17 (today)` and concludes *"The
window is newest-first. C2 is satisfied: new photos will appear."*

Re-parsing the `ds:1` media entries (300 of them, each `[mediaKey, [url,W,H,…],
creationTs, tok, tzOffset, uploadTs, …]`):

| | min | max |
|---|---|---|
| creation timestamp | 2026-04-11 13:37:30 UTC | **2026-04-12 16:56:58 UTC** |
| upload timestamp | 2026-04-16 04:41 UTC | **2026-04-18 07:47 UTC** |

All 300. Not one photo, and not one *upload*, is newer than 18 April 2026.

Of the 313 distinct 13-digit epoch values in the page: 310 fall in April 2026, one is
2026-05-28, and exactly two are on 2026-08-17 — `1786991255420`, which sits inside the
**contributor block** next to `["Contributor A",1,null,"Bipin"]` (a viewer-activity
timestamp), and `1786999883753`, a page-render value in the trailing footer. Neither is
a photo. §4's own caveat — *"the 'today' maximum may be a render timestamp"* — is the
correct reading, and it was overruled with *"The April–August spread is the load-bearing
evidence."* There is no April–August spread.

Worse, the album's own title is `og:title: "Arizona 2026 · Apr 11 – 17"`. This is a
**trip album that closed in April**, not "the household's Google Photos library" (§1).
It cannot demonstrate auto-refresh because nothing has been added to it in four months.

Consequences that must be worked through, not patched:

- "Newest-first" is unsupported. If the page instead returns the album's **first** N in
  ascending order (consistent with 300 consecutive photos from the trip's opening
  27 hours, ordered from the album's start), then new photos land on page 5 and **never
  appear on the frame**. C2 fails outright and the selected option joins the four
  rejected ones.
- §4's product reframing — *"the last ~300 family photos, always current"* — is wrong on
  both halves. Not the last; not current.
- §7 R5 ("Confirmed newest-first; reframed as product decision") rests on the same bad
  data and must be reopened.

**The distinguishing experiment costs five minutes and is not in the design:** add one
photo to the shared album now, wait, re-`curl` the share URL, and check whether its
media key appears in `ds:1`. Run it against an album that is actually receiving photos.
Until that returns positive, this design has no validated path to C2 and nothing should
be built on top of it.

### C-2. The 19% reframing is rationalising, and the numbers behind it are wrong.

Two separate problems.

*The number is wrong.* 302 unique `lh3…/pw/` URLs is not 302 photos. 300 are media
entries; the other two are the album cover (`<meta property="og:image" …=w600-h315-p-k`)
and a non-media asset. §6's golden test — *"Assert extraction yields 302"* — would lock
in a count that includes the og:image. Assert on **300 media entries parsed from `ds:1`**,
not on a regex hit count.

*The reframing is not honest.* "302 of 1600, reframed as a product decision" is defensible
only if the 302 are the *newest* 302 (per C-1, they are not) and if the limit is genuinely
immovable. On the second point the design didn't look: the tail of `ds:1` carries what
appears to be a continuation token (`"AF1QipPSE2BZUg0uS4PGVnxtMzVkjLDcFQhDuYH8xLgV",
"MlmQ7CRa1Vi82NuvQTTfugq5slI"` immediately before the contributor block closes). The
initial HTML contains **no** `batchexecute` string, no `rpcids`, and no `/_/…/data/…`
endpoint, so paginating would mean reverse-engineering the RPC out of Google's external
`wiz` bundles — materially harder and far more fragile than the regex harvest, and a
much stronger ToS problem than fetching a page.

That is a legitimate reason to accept one page. **Say that.** "One page is all the static
HTML gives us; going further means reverse-engineering an internal RPC, which we won't
do" is an honest engineering boundary. "19% is arguably preferable" is not — it's a
limitation wearing a product hat, and it reads as such.

### C-3. §6 commits the family's live album and PII into the repo.

`album-fixture.html` is to be committed to `src/test/resources/`. It contains:

- the live share link in plaintext: `https://photos.app.goo.gl/EXAMPLElink01`
- 35 `?key=<token>` share-access parameters
- five real full names — Contributor A, Contributor B, Contributor C, Contributor D, Contributor E
- six 21-digit Google account (Gaia) IDs, e.g. `<redacted-account-id>`
- profile-avatar URLs for each contributor
- 300 live photo URLs

Anyone who can read the repo can open the album. Golden-file testing is the right call
(§6 is correct that it's the highest-value test here) — but commit a **scrubbed** fixture:
rewrite media keys and URL slugs, delete the contributor block and `?key=` values, replace
the share URL. Add a scrubber script so refreshing the fixture after a Google markup change
can't silently re-leak. Also confirm nothing has already been pushed.

### C-4. "Never blank" is not achievable as designed. Four concrete paths.

1. **Cold start.** Fresh install: empty manifest, empty byte cache, Portal offline or
   Google slow. §5.3 renders "the on-disk manifest" — which doesn't exist yet. Blank.
   Ship 8–10 bundled JPEGs in `res/raw` as the floor, and an explicit "first sync" state.
2. **Cache smaller than the manifest.** §5.5 specifies "bounded LRU, a few hundred MB."
   The fixture's own file-size field (287 entries parsed) totals **1.89 GB, mean 6.6 MB,
   max 16.1 MB**. A 300 MB cache holds ~45 of 300 photos. §5.5's claim that on-disk bytes
   make the Portal "fully offline-capable" is false by a factor of six *at original
   resolution*. Fix: never request the bare URL — append a size suffix (the page's own
   `<img>` tags use `=w96-h72-no`, and the cover uses `=w600-h315-p-k`). At
   `=w1920-h1200-no` you get ~200–400 KB per photo, ~100 MB for all 300, and offline
   capability becomes real. This one change fixes the cache, the decode-heap concern in
   §5.7, and the bandwidth cost simultaneously — it is the highest-leverage missing detail
   in the document.
3. **Skip-loop with no floor.** §5.6's last row is "Skip photo, keep advancing" with no
   termination condition. Offline with 255 of 300 photos evicted, the runtime skips
   through the whole manifest in seconds and displays nothing — or the §5.7 watchdog
   force-advances into the same failure repeatedly, producing a strobe. Constrain the
   advance set to *cache-resident* items whenever a fetch fails, and only fall back to
   bundled assets when that set is empty.
4. **Swap-then-starve.** §5.3's "atomic manifest swap on success" can replace 300 cached
   URLs with 300 uncached ones. Prefetch bytes for the new manifest *before* swapping.

### C-5. §5.8 dismisses auto-start using the wrong argument.

C5 says the Portal reboots twice a year, and §5.8 concludes "walk over and tap the icon."
Reboot frequency is not the relevant variable. **Android process lifetime is.** A
foreground activity left running for months on a memory-constrained device will be killed
by the OS, or the system will drop back to the launcher after an update, a low-memory
event, or a Portal system-UI restart. The design has no foreground service, no
`START_STICKY` component, and no restart path — so the realistic failure is not "it
rebooted in March," it is "it's been showing the Portal home screen since Tuesday and
nobody noticed."

Dropping `BOOT_COMPLETED` / `SYSTEM_ALERT_WINDOW` / launcher takeover is a reasonable
scope call. Dropping *process durability* is a different decision and wasn't actually
made. A foreground service holding the activity is cheap and doesn't require any of the
rejected permissions.

Related: **"does the Portal screensaver override `KEEP_SCREEN_ON`?"** is filed in §9 as an
open item. For an always-on photo frame this is the single most likely cause of a dark
screen in production. It belongs in §7 as a risk with an owner, and it should be answered
before phase 3, not after.

### C-6. R2 is hand-waving, and the real question is cheap to answer.

"Personal household use; content the user owns; no redistribution → Accepted, gray" states
a conclusion without engaging the text.

Google's Terms of Service (May 2024 revision; **confidence: high** on substance,
**medium** on exact current wording — verify) prohibits, under "Don't abuse our services,"
using *"automated means to access content from any of our services in violation of the
machine-readable instructions on our web pages (for example, robots.txt files that
disallow crawling…)"*. Note what that clause does and does not say: it is **not** a blanket
prohibition on automated access — it is conditioned on `robots.txt`. So the analysis is
not "gray," it is *checkable*:

- Fetch `https://photos.google.com/robots.txt` and record what it says about `/share/`.
  One command. Put the verbatim result in R2.
- State the actual request volume: WorkManager at 6h = **4 requests/day, one device,
  one URL**. That is not crawling by any reasonable reading, and it is a much stronger
  position than "gray."
- Note explicitly that the Library API ToS and Google APIs Terms of Service do **not**
  apply, because no API and no OAuth credential are involved. The design implies this
  in §3 but never says it where it counts.
- The content is the user's own, shared to them, displayed privately, not redistributed
  and not republished. That is worth stating as fact rather than as mitigation.

Also worth a sentence: this posture is contingent on *not* paginating. If C-2 tempts
anyone toward the `batchexecute` RPC later, the ToS analysis changes materially and must
be redone.

## Suggestions

### Prior art — what already exists

The team lead is right that this is well-trodden. Everything here is from training
knowledge with web verification blocked; treat as leads to confirm, not as findings.

**Post-restriction status of the obvious candidates (confidence: medium-high):**

- **gphotos-sync** (gilesknap) — full-library downloader built on the Library API. Broke
  with the March 2025 restriction; my recollection is the maintainer archived or
  end-of-lifed it rather than migrate. It cannot help here.
- **rclone `google photos` backend** — same dependency, same outcome; limited to
  app-created data post-restriction. Not a path to an existing library.
- **Home Assistant `google_photos` integration** — migrated to the **Picker API**, which
  means interactive per-change selection. That is precisely the C2 violation §3 already
  identifies. HA landing on Picker is corroborating evidence that §3's option matrix is
  correct, and worth citing: the largest maintained project in this space looked at the
  same constraint set and could not solve C1 ∩ C2 either.
- **Immich / PhotoPrism** — self-hosted; ingest via Takeout or `immich-go`. Correctly
  rejected under C1.
- **DAKboard and commercial frames** — moved to Picker-style selection or dropped Google
  Photos. No supported auto-refresh path.

**The technique this design proposes is not novel and has named implementations
(confidence: medium — names and current health both need verifying):**

- **`google-photos-album-image-url-fetch`** (npm) does essentially §5.4 step 2: fetch the
  share page, harvest `lh3.googleusercontent.com` URLs. Widely copied.
- **MagicMirror `MMM-GooglePhotos`** (hermanho) is the closest functional analogue —
  a wall-display slideshow that has cycled through cookie-auth scraping, the Library API,
  and back toward unofficial access as Google closed doors. Its **issue tracker is the
  single most valuable artifact for this project**: it is a multi-year, public,
  timestamped log of exactly how often this class of integration breaks and what the
  breakages look like. Read it before phase 2.
- Numerous Home Assistant community custom components for "Google Photos public album."

**What this means for the design:** the *approach* is not reinvented — it's the community
consensus fallback, which is mild validation. What **is** reinvented is the parsing. §5.4
proposes writing a structured `AF_initDataCallback` walk plus a regex fallback from
scratch. Before doing that, spend an hour checking whether one of the above already
encodes the `ds:1` index positions, the `?key=` handling, and the size-suffix convention —
those are exactly the details this design is currently missing (C-4 item 2, and the
`?key=` question below), and they're the details a maintained implementation would already
have gotten right.

**Realistic breakage cadence (confidence: medium, reasoned rather than measured):**

Separate the two layers, because they have very different lifetimes.

- The `lh3.googleusercontent.com/pw/<slug>` URL form and the `=w{W}-h{H}` size grammar
  have been stable for roughly five years. The **regex layer is durable — multi-year.**
  §5.4 is right that it's the more stable of the two, and right to make it the fallback.
- The `AF_initDataCallback` `ds:N` array *index positions* are internal and unversioned.
  These shift. **Expect 6–18 months** between changes that break a positional walk.
- The tail risk that actually ends the project is not markup. It is policy: a JS-required
  render, a consent interstitial, bot detection, or rate limiting on `/share/`. Low
  probability per year, but terminal when it lands, and no amount of layered parsing
  mitigates it. This belongs in §7 as its own risk, distinct from R1 — R1's "high
  likelihood, low impact" is correct for markup drift and badly wrong for an access wall.

Practical implication: the layered design in §5.4 is well-judged, but its value is
concentrated in the regex tier. Consider making the regex the **primary** path and the
JSON walk the enrichment pass — you need the JSON only for dimensions, media keys and
timestamps, and a failure to parse those should degrade gracefully to "URL only," not
fall through to last-known-good.

### Domain constraints the design ignores

Measured from the fixture unless noted.

- **Orientation mix — the biggest unaddressed quality issue.** 170 portrait / 132
  landscape: **56% portrait** on a landscape frame. The design says nothing about it.
  Naive `centerCrop` will decapitate more than half the album; naive `fitCenter` will
  show black pillarbox bars 56% of the time on an always-on display. This is the defining
  visual problem of DIY photo frames and it needs a stated policy — blurred-and-scaled
  background fill behind a fitted portrait is the usual answer, or pair two portraits
  side by side. Pick one and write it down.
- **Burst near-duplicates.** Consecutive entries at 13:53:33.597 and 13:53:35.363 — 1.8
  seconds apart. A 300-photo trip album shot over 27 hours is dense with bursts. §5.7's
  reshuffle-per-pass helps by scattering them, but a "suppress items within N seconds of
  the previously shown photo" rule is a few lines and materially improves the frame.
- **Key the manifest on media key, not URL.** Each entry leads with a stable
  `AF1Qip…` media key. The design dedupes on URL slug. Slugs can rotate; media keys are
  the identity. This also gives you real duplicate detection and a stable cache filename.
- **`?key=` share tokens.** 35 occurrences in the page, including on the og:image. Some
  share URLs require this parameter for image access. §5.6's "Image fetch 403 → skip
  photo" treats the symptom without knowing the cause; determine whether the media URLs
  need the key propagated, or you may find the entire manifest 403s at once — which
  §5.6 would handle by skipping all 300 photos and showing nothing (see C-4 item 3).
- **HEIC is a non-issue here — say so.** No HEIC/HEIF markers in the fixture; `lh3`
  transcodes server-side. This is a genuine, unstated **advantage** of the share-page
  approach over Takeout or Drive, both of which would land raw HEIC on a device whose
  decoder support is uncertain. Worth one line in §3 as a point in the chosen option's
  favour.
- **Videos.** None in this fixture (no video MIME types, no duration fields outside CSS,
  no `mp4`). But shared albums accept them, and a video's entry yields a poster still with
  a different sub-array shape. Decide now: filter them out by structure, or show the still.
  Silently emitting a poster frame styled as a photo is fine; crashing the positional walk
  on an unexpected shape is not.
- **Motion photos** degrade to stills through `lh3`. No action needed; worth noting so
  nobody investigates it later.

### Smaller points

- §4's "Unique `lh3` URLs: 302 / Total references: 362" — 362 is the count of the
  *hostname* string; unique-slug matches total 341. Cosmetic, but the fixture table is
  presented as measurement and should be exact.
- §4 "`AF_initDataCallback` blocks: 2" is **correct** — there are five occurrences of the
  string, three of which are the shim definition in `<head>`, and two real data blocks
  (`ds:0`, `ds:1`). Good catch by the author; worth a footnote so a future reader doesn't
  "fix" it.
- §5.9's settings sheet behind a long-press is the right call, but add the resolved
  photo count and the newest photo date to the readout. Given C-1, "newest photo: 4 months
  old" is the exact signal that would have caught this design's central error, and it's
  the signal that will catch C2 silently failing in production.
- §7 R4: deleting the crashing screen is fine, but the three candidate causes named in §1
  (main-thread blocking, `errorBody().string()` in the catch, `HttpLoggingInterceptor.BODY`
  heap pressure) are all patterns that can recur in new code. Gate the logging interceptor
  to debug as planned, and add a StrictMode `detectNetwork()` penalty in debug builds —
  that closes the class of bug without needing the original stack trace.

## Verified Claims

First-hand, reproducible against `/Users/bnepani/PortalGallery/album-fixture.html`
(1,399,619 bytes).

| Design claim | Status | Measured |
|---|---|---|
| 302 unique `lh3…/pw/` URLs | **True but misleading** | 302 unique slugs = 300 media entries + og:image cover + 1 other asset |
| Page size 1,399,619 bytes | **Confirmed** | exact match |
| 2 `AF_initDataCallback` data blocks | **Confirmed** | `ds:0`, `ds:1` (5 raw string hits, 3 are the head shim) |
| 313 distinct 13-digit timestamps | **Confirmed** | 310 in Apr 2026, 1 on 2026-05-28, 2 on 2026-08-17 |
| Photo date range 2026-04-11 → 2026-08-17 | **FALSE** | creation 2026-04-11 13:37 → **2026-04-12 16:56**; upload 2026-04-16 → 2026-04-18 |
| "The window is newest-first" | **Unsupported** | no photo newer than 18 Apr 2026; both Aug-17 values are non-photo (contributor activity, page render) |
| "C2 is satisfied: new photos will appear" | **Unsupported** | see above; album is `og:title: "Arizona 2026 · Apr 11 – 17"`, a closed trip album |
| Regex harvest yields 302 on live fixture | **Confirmed** (yields 302; 300 are photos) | `lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+` |
| "1,600 photos" album | **Not verifiable from fixture** | no total-count field located; page exposes 300 |

New measurements not in the design:

| Fact | Value |
|---|---|
| Orientation mix | 170 portrait / 132 landscape (56% portrait) |
| Original file sizes (287 parsed) | **1.89 GB total**, mean 6.6 MB, max 16.1 MB |
| Sample dimensions | 4000×3000, 3000×4000, 2736×3648, 1920×1080 |
| Pagination RPC in HTML | **absent** — no `batchexecute`, no `rpcids`, no `/_/…/data/…` |
| Apparent continuation token | present at tail of `ds:1` (unconfirmed semantics) |
| Video / HEIC markers | **none** |
| PII in fixture | 5 real names, 6 Gaia IDs, 6 avatar URLs, live share link, 35 `?key=` tokens |
| Size-suffix grammar in use on page | `=w96-h72-no`, `=w54-h72-no`, `=w600-h315-p-k`, `=w1200-h1500-p-k-no` |

Claims I could not verify (web tools blocked):

- **`photoslibrary.readonly` withdrawn ~2025-03-31 — I assess this as substantially
  accurate, confidence high.** My understanding: Google announced during 2024 that
  effective **31 March 2025** the broad Library API scopes were removed for general use;
  read access narrowed to `photoslibrary.readonly.appcreateddata`, write to
  `photoslibrary.appendonly` / `…edit.appcreateddata`, and user-selected content moved to
  the separate **Picker API**. `albums.list`, `mediaItems.list` and `mediaItems.search`
  were all restricted to app-created data. That matches §1 closely. Two caveats: I recall
  reports of staggered enforcement extending past the announced date for some existing
  clients, and I cannot confirm the exact date to the day. R7's "Low — does not change the
  design" is the right call either way; §3 rejects the API paths on C1/C2 grounds that
  hold regardless of the restriction.
- All prior-art project names and their current post-restriction status — confidence
  medium to medium-high as annotated above. Verify before citing in the design.
- Google ToS wording on automated access — confidence high on substance, medium on
  current exact text. `photos.google.com/robots.txt` is unchecked and is the load-bearing
  fact for R2.

---

**Bottom line.** The architecture is the strongest part of this document: §5.3's
"slideshow never makes a blocking network call," the layered parse with a sanity gate,
last-known-good, and golden-fixture testing are all correct and well-reasoned, and
§5.8's instinct to cut scope is healthy. None of that is in question. What is in
question is the one empirical claim the whole option matrix rests on. Re-run §4 against
an album that is actually receiving photos, confirm a newly added photo reaches the
share page, and then rewrite §4, §3's verdict column, and R5 around what that shows.
If the new photo does not appear, the selected option violates C2 exactly as the four
rejected options do, and the design needs a different answer — most likely the LAN
helper from §5.2, promoted from YAGNI to load-bearing.
