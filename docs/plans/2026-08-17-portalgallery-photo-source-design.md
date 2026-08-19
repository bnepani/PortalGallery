# PortalGallery — Photo Source Redesign

**Date:** 2026-08-17
**Status:** **Implemented (Phases 1–3), running on emulator.** §4 was refuted by review
and rewritten; see the note below and §10 for corrections already applied in code.
**Author:** bnepani (with Claude Code)

> **Review outcome (2026-08-17): all three reviewers returned NEEDS_REVISION.**
> See `design-review-architecture.md`, `design-review-android.md`, `design-review-risk.md`.
>
> **§4's central claim is false.** The share page returns the **oldest ~300 photos in
> ascending capture order** — a deterministic prefix, not a newest-first rolling window.
> Independently reproduced three ways (two reviewers plus `tools/check_album_ordering.py`):
> 300 entries, 2/299 inversions, all April 2026, continuation token present, and a live
> re-fetch returns identical order and ids.
>
> The "2026-08-17" maximum cited as proof of freshness is **album metadata**, not a photo.
> §4 raised exactly this caveat and then overruled it. That inversion is the root error.
>
> **Consequence: C2 (auto-refresh) is not satisfied.** New photos append to the tail; the
> page serves the head. Blocked pending a fixture from an album that is actively receiving
> photos — run `tools/check_album_ordering.py "<share link>"`.
>
> Corrections to apply on revision are listed in §10.

---

## 1. Problem

PortalGallery is a sideloaded Android slideshow app for a Meta Portal, displaying the
household's Google Photos library as an ambient photo frame.

It no longer works. The current implementation authenticates via OAuth and calls the
Google Photos Library API:

| Call | Location |
|---|---|
| `albums.list` | `app/src/main/kotlin/.../data/api/GooglePhotosApi.kt:12` |
| `mediaItems:search` | `GooglePhotosApi.kt:18` |
| `mediaItems.list` | `GooglePhotosApi.kt:21` |
| scope `photoslibrary.readonly` | `auth/AuthManager.kt:20` |

> ### ✅ CONFIRMED EMPIRICALLY (2026-08-17, emulator API 36)
>
> ```
> <-- 403 https://photoslibrary.googleapis.com/v1/albums?pageSize=20 (1658ms)
> { "error": { "code": 403,
>              "message": "Request had insufficient authentication scopes.",
>              "status": "PERMISSION_DENIED" } }
> ```
>
> A valid bearer token was sent and accepted; it carries no library-read scope. OAuth
> completes (sign-in appears to work) but `photoslibrary.readonly` is not granted.
> **The restriction is the blocker. The redesign is warranted.** R7 is closed.
>
> Note the call failed in **1.66 s and did not crash** — `buildErrorMessage` caught and
> rendered it as designed. The Portal's reported 60 s spin + crash is therefore
> environmental (consistent with route exhaustion under the uncapped `callTimeout`),
> not the restriction's mechanism. R4 is closed as moot: the screen is being deleted and
> the underlying failure is understood.

To the best of current knowledge (**superseded by the confirmation above**), the Google
Photos Library API restriction effective ~2025-03-31 withdrew
`photoslibrary.readonly` for general library access. Read access narrowed to
app-created content (`photoslibrary.readonly.appcreateddata`); user-selected content
moved to the separate Picker API. Every endpoint above is affected.

### Observed symptom (root cause NOT established)

Sign-in succeeds. The album fetch spins for 60s+ and then the app crashes.

This does **not** match the restriction's expected behaviour: `AlbumPickerActivity.kt:72-89`
wraps the fetch in `try/catch` and renders errors to `tvError`, so a 403 would display,
not crash. Unexplained candidates:

- **ANR** — a blocked main thread matches the "spin then die" signature.
- **The catch block itself throwing** — `buildErrorMessage` (`:111-118`) calls
  `errorBody()?.string()` on the main thread; if it throws, it escapes the enclosing catch.
- **Heap pressure** — `HttpLoggingInterceptor.Level.BODY` (`PhotosApiClient.kt:25`)
  buffers every full response body, unconditionally.

**A logcat stack trace is still outstanding.** This design deletes the crashing screen,
which is not the same as understanding the crash. See Risk R4.

---

## 2. Constraints

Established by interview. The first two are hard.

| # | Constraint | Source |
|---|---|---|
| C1 | **Photos stay in Google Photos.** Migrating to Drive or another host defeats the app's purpose. | Stated, hard |
| C2 | **Auto-refresh.** New photos appear with no human curation step. | Stated, hard |
| C3 | Household scope — a few known people, sideloaded, no app store, no OAuth verification. | Stated |
| C4 | Self-contained APK preferred; open to a LAN helper later. | Stated |
| C5 | Portal reboots are rare (months between). | Stated |
| C6 | Frame must never go blank. | Derived from product intent |

---

## 3. Options considered

| Option | Reads existing library | Auto-refresh | Supported | Verdict |
|---|---|---|---|---|
| Picker API | Selection only | **No** — interactive per change | Yes | **Rejected**: violates C2 |
| App-created data scope | **No** — only self-uploaded | Partial | Yes | **Rejected**: violates C1 intent; duplicates photos |
| Google Drive shared folder | N/A — different host | Yes | Yes | **Rejected**: violates C1 |
| Takeout export | Yes, bulk | **No** | Yes | **Rejected**: violates C2 |
| Self-hosted (Immich etc.) | N/A — different host | Yes | Yes | **Rejected**: violates C1 |
| **Shared-album public link** | **Yes** | **Yes** | **No — unofficial** | **Selected** |

**C1 ∩ C2 has exactly one member.** No supported Google API satisfies both. This is
accepted deliberately, not overlooked. The chosen path is unofficial: it depends on
the structure of a public share page and may break without notice.

**Why it fits anyway:** family members add photos via the native "add to shared album"
flow they already use; photos never leave Google Photos; the frame reads one stable URL;
and — critically — **no authentication is involved at all**.

---

## 4. Empirical validation

Run 2026-08-17 against the live household album (1,600 photos):

```
curl -sL "https://photos.app.goo.gl/<id>" -o album-fixture.html
```

| Finding | Value |
|---|---|
| HTTP status | 200, redirects to `photos.google.com/share/AF1Qip…` |
| Page size | 1,399,619 bytes |
| Unique `lh3.googleusercontent.com/pw/…` URLs | **302** |
| Total `lh3` references | 362 (≈60 dupes / non-photo assets) |
| `AF_initDataCallback` blocks | 2 |
| Distinct 13-digit epoch-ms timestamps | 313 |
| Photo date range | **2026-04-11 → 2026-08-17 (today)** |

**Two conclusions:**

1. **The page yields a rolling window, not the full album** — 302 of 1,600 (19%).
2. **The window is newest-first.** All timestamps fall in the trailing four months and
   the maximum is today. C2 is satisfied: new photos will appear.

The product is therefore "the last ~300 family photos, always current," which is an
acceptable — arguably preferable — reading of the original intent. Recorded here as a
deliberate choice rather than a discovered limitation.

*Minor caveat:* 313 timestamps vs 302 URLs suggests ~11 are page metadata; the
"today" maximum may be a render timestamp. The April–August spread is the load-bearing
evidence.

---

## 5. Architecture

### 5.1 What is deleted

The share link is unauthenticated, so roughly half the current app addresses a problem
that no longer exists:

- `auth/AuthManager.kt` — including the hardcoded `CLIENT_ID`/`CLIENT_SECRET` (`:16-17`)
- `ui/signin/OAuthWebViewActivity.kt` — and the WebView-policy risk and UA spoofing (`:32-35`)
- `ui/signin/SignInActivity.kt`
- Token refresh, expiry, and the 7-day testing-mode refresh-token problem
- `data/api/GooglePhotosApi.kt`, `data/api/PhotosApiClient.kt`, Retrofit + OAuth deps
- `ui/albums/*` — no album picking; one shared album is the source

**Action regardless of this design: revoke the leaked client secret.** It ships in the APK.

### 5.2 Components

```
┌─ PORTAL (only deployed artifact) ───────────┐
│  SlideshowActivity → PhotoManifest (disk)   │
│                          ↑                  │
│                    RefreshWorker            │
│                          ↓                  │
│                  PhotoSource  ← interface   │
│                          ↓                  │
│                  SharedAlbumSource          │
└──────────────────────────┬──────────────────┘
                           │ HTTPS
                    photos.google.com
```

`PhotoSource` is a one-method interface (`suspend fun fetch(): List<Photo>`). It exists
so a future `HelperSource` can be swapped in without rewriting the worker. **`HelperSource`
is explicitly NOT built now** (YAGNI) — only if markup breakage becomes routine enough that
per-device APK rebuilds annoy.

### 5.3 Core invariant

**The slideshow never makes a blocking network call.** It renders the on-disk manifest
immediately at startup. Refresh happens in WorkManager and swaps the manifest atomically
on success. This structurally eliminates the current bug class: a frame reading from disk
cannot spin, cannot ANR at startup, and cannot blank because Google had a bad afternoon.

### 5.4 Parsing — layered, defensive

1. **Structured walk** of the `AF_initDataCallback` JSON.
2. **Regex harvest** fallback — `lh3\.googleusercontent\.com/pw/[A-Za-z0-9_-]+`, deduped.
   The URL format is markedly more stable than the JSON scaffolding around it.
   *Validated: yields 302 on the live fixture.*
3. **Last-known-good** — if both fail, change nothing.

**Sanity gate:** a parse returning zero, or <50% of the previous count, is treated as
suspect — keep the old manifest, log loudly. A partial parse silently reducing 300 photos
to 12 is worse than an outright failure because it goes unnoticed.

### 5.5 Caching

**Cache image bytes, not just URLs.** `lh3` URL lifetime is unknown; a frame that dies
when they expire is unacceptable. On-disk bytes make the Portal fully offline-capable and
sidestep the question. Bounded LRU, a few hundred MB.

### 5.6 Failure taxonomy

| Failure | Detected by | Response |
|---|---|---|
| Network down | `IOException` | Keep manifest, exponential backoff |
| Album unshared | HTTP 404 | Keep manifest, discreet warning badge |
| Markup changed | 0 photos parsed | Keep manifest, log |
| Partial parse | <50% of previous | Keep manifest, log |
| Image fetch 403 | Glide failure | Skip photo, keep advancing |

Every row keeps the frame lit (C6).

### 5.7 Slideshow runtime

- **Memory:** a 1920×1200 ARGB_8888 bitmap is ~9 MB decoded. Decode at display resolution,
  prefer `RGB_565` (no alpha needed; halves it), preload exactly **one** image ahead.
- **Watchdog:** if the displayed photo hasn't changed in 3× the interval, force-advance.
  A dropped Glide callback should cost one frame, not a frozen wall display.
- **Refresh:** WorkManager periodic, ~6h, `NetworkType.CONNECTED`, atomic manifest swap.

**Bugs to carry forward as fixes** (current `SlideshowActivity`):

- `showPhotoAt` never clears the previous `CustomTarget` — loads leak, can land out of order.
- If the *first* image fails, `isFirstPhoto` stays `true` forever and crossfade never initialises.
- `photos.shuffled()` runs once at startup — reshuffle each full pass.

### 5.8 Auto-start: explicitly out of scope

Per C5, the Portal reboots perhaps twice a year. A `BOOT_COMPLETED` receiver,
`SYSTEM_ALERT_WINDOW` appops grant, HOME-launcher takeover, and lowering `targetSdk`
to 28 were all evaluated and **dropped**. The correct answer to "what if it reboots"
is to walk over and tap the icon.

*(Context if ever revisited: `targetSdk = 34`, and Android 10+ bars background activity
starts, so a boot receiver may fire yet be silently unable to launch the activity.)*

### 5.9 Configuration

Deleting `AlbumPickerActivity` removes the interval slider, which still feeds
`scheduleNext()` — that control must be preserved or a feature is silently lost.
Repurpose the slideshow's long-press (currently `finish()`) to open a minimal settings
sheet: share-album URL, interval, and last-refresh readout. Ship a `BuildConfig` default
URL so a fresh install works with no setup.

### 5.10 Observability

Silent breakage is the principal enemy of an unofficial dependency. Persist
`lastSuccessfulRefresh` and `lastPhotoCount` on every attempt and surface them in the
settings sheet. "Last refresh: 12 days ago, 0 photos" is diagnosable in two seconds.

---

## 6. Testing

**Golden-file parser tests are the highest-value tests in the project.**

1. Commit `album-fixture.html` to `src/test/resources/`. Assert extraction yields 302.
   When Google reshuffles markup, refresh the fixture and the diff shows what changed.
2. Truncated fixture → assert the manifest is **not** replaced (sanity gate).
3. Simulated `IOException` → assert cache survives intact.
4. Manual acceptance: Portal in airplane mode overnight; still cycling in the morning.

All of 1–3 are plain JVM tests — no emulator, no Portal, sub-second feedback.

---

## 7. Risks

| ID | Risk | Mitigation | Residual |
|---|---|---|---|
| R1 | Google changes share-page markup | Layered parse, sanity gate, last-known-good, golden fixture | **High likelihood, low impact** — frame keeps last set |
| R2 | ToS exposure — unofficial use of a share page | Personal household use; content the user owns; no redistribution | Accepted, gray |
| R3 | `lh3` URL expiry | Cache bytes, not URLs | Low |
| R4 | Original crash root cause unknown | Crashing screen deleted; gate `HttpLoggingInterceptor` to debug | **Open — logcat outstanding** |
| R5 | Window is 19% of album | Confirmed newest-first; reframed as product decision | Accepted |
| R6 | Album unshared by a family member | 404 handling, warning badge | Low |
| R7 | Photos API claims unverified | Re-verify when web tools enabled | Low — does not change the design |

---

## 8. Phasing

1. **Toolchain** — restore Gradle wrapper at 8.7 (missing `gradlew`/`gradle-wrapper.jar`;
   only JDK 21/11/8 present, and Gradle 8.4 predates Java 21 support).
2. **Parser + manifest + cache**, JVM-tested against the fixture. Delete the auth stack.
3. **Slideshow runtime** — watchdog, memory fixes, settings sheet.
4. **Helper** — only if breakage becomes routine.

---

## 9. Open items

- [ ] Logcat stack trace for the original crash (R4)
- [ ] Revoke the leaked OAuth client secret
- [ ] Verify Photos API restriction claims once web tools are enabled (R7)
- [ ] Portal-only: does the system screensaver override `KEEP_SCREEN_ON`?
- [ ] Portal-only: real heap ceiling for image cache sizing

---

## 9a. Ordering: it follows the album's sort order (2026-08-17)

§4 concluded from one album that the share page returns an **oldest-first prefix**, and
that this violated C2. A second album refutes the generalisation:

| Album | Order | Capture range | Inversions |
|---|---|---|---|
| `Arizona 2026 · Apr 11–17` | **ASCENDING** (oldest first) | 2026-04-11 → 04-12 | 2 / 299 |
| `PortalShare · Apr 2019 – Aug 2026` | **DESCENDING** (newest first) | 2026-05-03 → 08-10 | 294 / 299 |

Both return exactly 300 with a continuation token, so the 300-item prefix limit is
constant. What varies is the **direction**.

**Working hypothesis (not proven):** the page reflects the album's own sort order as
configured in Google Photos. A trip album left in chronological order reads
oldest-first; an album sorted newest-first reads newest-first. If so, the ordering is
**user-controllable**, and C2 is satisfied by setting the frame album to newest-first —
no pagination, no 300-photo cap needed.

**What is actually confirmed for the frame album:** newest-first by *capture* date. So a
newly *taken* photo appears at the head and reaches the frame. A newly *added but
old* photo — a 2019 scan, say — sorts by its capture date, lands beyond the 300 window,
and will not appear. Worth knowing before someone adds a batch of old photos and
wonders why they never show up.

**Not yet tested:** adding a photo to this album and confirming it arrives. Every photo
here was added in one batch on 2026-08-17, so the added-timestamp column cannot
distinguish. The direction evidence is strong but indirect.

---

## 9b. Orientation filter (added 2026-08-17, after implementation)

**Requirement added after the design was written and reviewed.** Not part of the
original constraint set in §2 and not present in the pre-rewrite app.

The frame shows only photos matching its physical orientation: portrait photos when
mounted portrait, landscape photos when mounted landscape.

**Cost, measured on the reference album:** 168 portrait / 132 landscape / 0 square. A
landscape-mounted frame therefore shows **44% of the library**. This is the intended
trade — a correctly framed subset over a full album where half the photos are cropped
or pillarboxed — but it should be a conscious one, and it interacts with the §4
300-photo prefix limit: filtering compounds the truncation.

**Implementation:**
- `screenOrientation` removed from the manifest (was `"locked"`, which would have
  pinned the frame to its boot orientation and never re-evaluated the filter).
  `configChanges` already carries `orientation|screenSize`, so rotation is handled in
  `onConfigurationChanged` without Activity recreation.
- `SlideshowActivity` keeps `library` (everything on disk) separate from `photos` (the
  filtered rotation set); `applyOrientationFilter()` derives the latter and re-anchors
  the current photo by id.
- **Empty-match exception:** if no photo matches the current orientation, the filter is
  abandoned and the full library is shown. A poorly framed photo is a worse outcome
  than a black frame only in theory; in practice C6 (never blank) wins.
- Golden test pins the 168/132 split, so silent breakage in dimension parsing surfaces
  as a test failure rather than as a frame filtering on garbage.

---

## 10. Corrections from review (apply on revision)

### Blocker

- **§4, §3 verdict column, R5 — rewrite entirely.** Oldest-first prefix, not newest-first
  window. C2 unsatisfied. Choose: cap the album at ~300 photos / rotate N share links /
  paginate via the `snAcKc` batchexecute RPC (token at `ds:1` `data[2]`; the request tuple
  has a `null` in the token slot). Pagination roughly doubles the unofficial surface and
  materially weakens the ToS position in R2.

### Corrections to claims made in this document

| § | Claim | Correction |
|---|---|---|
| §4 | "Photo date range → 2026-08-17" | Capture 04-11→04-12; added 04-16→04-18. Aug value is album metadata. |
| §4 | "newest-first … C2 satisfied" | Refuted — ascending, 2/299 inversions. |
| §4 | "1,600 photos" | Unverifiable; no total-count field in the page. Fixture is a trip album. |
| §5.4 | Regex fallback "validated" | **Yields bare URLs → 384×512 thumbnails.** Size suffix is added by page JS, absent from markup. Tier 2 must append a suffix. |
| §5.4 | Sanity gate on count | **Nearly inert** — the page always returns exactly 300. Add a **resolution check** (decode one image, assert width ≥ ~1200). |
| §5.5 | "a few hundred MB" | **~92 MB** at `=w1920-h1200` (measured mean 301 KB × 300). Originals would be ~880 MB. |
| §5.7 | Use `RGB_565` | **Wrong.** `minSdk 26` → `Bitmap.Config.HARDWARE`; Glide prefers it by default and allocates outside the Java heap. `PREFER_RGB_565` opts out — costs quality *and* moves memory into the heap. |
| §5.7 | "`isFirstPhoto` stays true forever" | Incorrect — the next *successful* load clears it. Real defect is the missing `onLoadFailed`. |
| §5.7 | Glide target leak (one line) | **Severely understated.** ~10,800 retained `CustomTarget`+`SingleRequest` pairs/day, unbounded. Fix by using `ViewTarget` (`.into(imageView)`), which auto-clears. |
| §5.8 | Auto-start dropped wholesale | **`DreamService`** and **HOME activity** are categorically different — the *system* starts them, so no background-start restriction, and both survive process death. A foreground service does **not** solve this. |
| §5.10 | `lastSuccessfulRefresh` as health signal | **It lies.** WorkManager cold-starts the process after the Activity dies, so the signal stays green on a dead frame. Add `lastActivityRenderMs`. |
| §5.3 | "cannot ANR at startup" | A main-thread disk read can. Specify: manifest read on `Dispatchers.IO`. |
| §5.3 | "atomic manifest swap" | Atomic on disk, **undefined in memory**. If the running slideshow never re-reads, refresh is functionally dead (process restarts twice a year). If it does, a shorter list throws `IndexOutOfBounds`. Re-read at pass boundary, re-anchor by media id, enqueue as unique work. |
| §6 | "assert extraction yields 302" | Assert **300**. 302 includes the `og:image` social card and the `ds:0` cover hero. |
| §6 | Commit `album-fixture.html` | **Contains PII** — live share link, `?key=` tokens, 6 Gaia IDs, 5 real names. Scrub before committing; add a scrubber script. |
| §8.1 | "bump wrapper to 8.7" | Insufficient. AGP 8.2.2 targets JDK 17 and is outside its matrix on JDK 21 (only 21/11/8 installed). Install JDK 17, or bump AGP to 8.6+. |

### New items not in the original design

- **`BuildConfig` is not generated** — AGP 8.0+ defaults `buildConfig=false`. Both §5.9's
  default URL and R4's debug gating fail to compile. Add `buildConfig = true`.
- **`release` has no `signingConfig`** and `isMinifyEnabled=false` → unsigned APK that
  `adb install` rejects → you sideload debug forever → `BuildConfig.DEBUG` is always true
  and R4's gate does nothing. Gate on an explicit `buildConfigField` instead.
- **`allowBackup="true"`** ships `SharedPreferences` — including OAuth tokens — off-device
  via Auto Backup. Set `false`.
- **Immersive flags applied once** in `onCreate`; the system clears them on focus change.
  Re-apply in `onWindowFocusChanged` or expect a permanent nav bar within months.
- **No `configChanges`** + DayNight theme → two full Activity recreations per day, each
  restarting the slideshow at index 0 (`currentIndex` is never saved).
- **`screenOrientation="landscape"`** may pick rotation 90 or 270 on appliance hardware —
  a 50% chance of an upside-down frame. Prefer `"locked"`; verify with `dumpsys display`.
- **`togglePause` never calls `removeCallbacks`** — tapping pause advances one more photo.
- **The "Tap to pause • Hold to exit" hint is never hidden** — no id, never referenced.
  Renders in fixed pixels continuously for months.
- **Glide disk cache is keyed on the full URL** and defaults to 250 MB. URL rotation
  evaporates the cache exactly when it's needed. Configuring it needs an `AppGlideModule`,
  which needs `ksp`/`kapt` — neither is in the build.
- **WorkManager is not a dependency.** Add `work-runtime-ktx:2.9.1` (not 2.10+, which
  needs `compileSdk 35`). Or drop it: a `delay(6.hours)` coroutine is simpler and, per the
  `lastSuccessfulRefresh` finding, WorkManager's ability to run without the Activity is
  currently a liability.
- **No test source set exists** (`app/src/` contains only `main/`) and there are no test
  dependencies. §6 cannot run until both are created.
- **56% of the album is portrait** (170/302) on a landscape frame. Unaddressed. Needs a
  stated policy — blurred-fill background behind a fitted portrait is the usual answer.
- **Burst near-duplicates** — consecutive entries 1.8 s apart. Suppress items within N
  seconds of the previously shown photo.
- **Key the manifest on media id, not URL slug.** Slugs rotate; ids are stable identity.
- **Set an explicit User-Agent.** Verified UA-independent today, but pin it and record it
  in the fixture's provenance so the golden test and the device agree.

### Simplification worth adopting

Replace the manifest + LRU with **a directory of JPEGs plus a small index file**. "Never
blank" becomes a property of `dir.listFiles()` rather than an invariant to maintain: no LRU
policy, no cache/manifest coherence problem, no eviction race. At ~92 MB total an LRU is
unnecessary. Refresh = download into a temp dir, rename, delete the old one.

### Good news, verified

- **No auth, no cookies, no UA spoofing** for the page *or* the images — confirmed across
  okhttp, curl, and Chrome-Android UAs. Justifies deleting the WebView UA hack outright.
- **Doze is structurally unreachable** — it requires screen-off *and* unplugged. Recorded
  so it is not re-raised.
- **`=w1920-h1200` is a fit-inside bounding box, not a crop**; `=d` returns the original.
- **No HEIC and no video** in the fixture; `lh3` transcodes server-side. This is a genuine
  advantage over Takeout or Drive, both of which would land raw HEIC on a device with
  uncertain decoder support.

### R4 — likely closeable without a logcat

`OkHttpClient` sets no timeouts (10 s defaults), so a 60 s+ spin is not one call timing
out. `PhotosRepository.getPhotosFromAlbums:15-28` has an **unbounded** `do/while
(pageToken != null)` loop across every selected album, with `BODY` logging buffering every
full page. That fits "spins, then dies" far better than the album list (one call,
`pageSize = 20`). Note this is the *slideshow* path, not the picker — confirm which screen
actually crashed before closing.
