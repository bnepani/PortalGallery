# Design Review — Android Platform Correctness

**Reviewer lens:** Android platform behaviour and long-running-process realities
**Design under review:** `docs/plans/2026-08-17-portalgallery-photo-source-design.md`
**Date:** 2026-08-17

VERDICT: NEEDS_REVISION

## Summary Assessment

The data-source strategy and the "never block the UI on the network" invariant are sound, and
the memory arithmetic is correct — but the design has no answer for *who restarts the frame*,
recommends an image format that is wrong for a photo frame, and its two named build-time
mechanisms (`BuildConfig`, `AppGlideModule`) will not compile as the project is currently
configured. The Doze concern the brief asked me to attack is a non-issue for a mains-powered,
screen-on device; the real long-running-process hazard is an unbounded Glide target leak in
`SlideshowActivity.kt:79-105`.

---

## Critical Issues (must fix)

### C1. Nothing owns liveness. This is the biggest hole in the design.

§5.8 drops auto-start on the reasoning that reboots are rare. Reboots are not the threat model.
The threat model is: **the Activity or process goes away for any reason other than a reboot, and
no component in the system has the authority or the intent to bring it back.**

Ways the frame goes dark with the design as written:

- A family member long-presses. Today that is `finish()` (`SlideshowActivity.kt:53`). §5.9
  repurposes it to a settings sheet, which fixes this one path — but see C2 for how it
  reintroduces it.
- The Portal's own system UI (Assistant, an incoming-call surface, its native "Superframe"
  ambient screensaver, a system update dialog) takes foreground. Our Activity is now in the
  cached-process LRU with `oom_adj` in the cached band, which on a 2–4 GB appliance is a prime
  LMK candidate. It gets killed. Nothing brings it back.
- Any unhandled exception on the main thread. There is no `Thread.UncaughtExceptionHandler` and
  no crash-restart path.
- An OS or Portal-firmware update reboots the device (this does happen, and is not the "twice a
  year user reboot" the design budgeted for).

The failure is silent and worse than the design assumes, because **WorkManager keeps running
after the Activity dies.** JobScheduler cold-starts the app process to execute the worker, so
`lastSuccessfulRefresh` in §5.10 keeps ticking over and the manifest stays fresh while the screen
shows the Portal home screen. Every observability signal the design specifies will report
"healthy" on a dead frame. §5.10's stated purpose — "silent breakage is the principal enemy" —
is defeated by the one signal it chose.

The design evaluates four auto-start mechanisms in one sentence and drops all four. Three of them
deserved dropping. The fourth did not:

| Mechanism | Solves reboot | Solves process death | Background-activity-start problem |
|---|---|---|---|
| `BOOT_COMPLETED` receiver | nominally | no | **yes** — blocked on Android 10+, exactly as §5.8 notes |
| `SYSTEM_ALERT_WINDOW` appop | partly | no | works, but needs an appop grant per install |
| Lower `targetSdk` to 28 | yes | no | sidesteps it, at the cost of every other API-28 behaviour |
| **HOME activity (kiosk)** | **yes** | **yes** | **none — ActivityManager relaunches HOME itself** |
| **`DreamService`** | **yes** | **yes** | **none — the system starts and restarts the dream** |

HOME takeover and `DreamService` are categorically different from the other three: neither
involves a background activity start, because in both cases *the system* is the one starting the
activity. They are the only two mechanisms here that survive process death, which is the failure
the design has no answer for at all.

**Recommendation, in order:**

1. **`DreamService`.** This is the API Android actually provides for "wall-mounted display that
   shows something when idle," available since API 17, and it is configurable entirely over adb
   — which fits the sideload-only constraint better than anything requiring the Portal's Settings
   UI:
   ```
   adb shell settings put secure screensaver_components com.example.portalgallery/.PhotoDream
   adb shell settings put secure screensaver_enabled 1
   adb shell settings put secure screensaver_activate_on_dock 1
   adb shell settings put secure screensaver_activate_on_sleep 1
   ```
   The system relaunches the dream on its own after a kill, and after boot. **Caveat that must be
   tested first:** a dream only starts when the device would otherwise sleep, so you must *drop*
   `FLAG_KEEP_SCREEN_ON` and instead rely on `stay_on_while_plugged_in`. And Portal may not expose
   a functioning `DreamManagerService` — verify with `adb shell dumpsys dreams` before committing.
2. **HOME activity.** Add `<category android:name="android.intent.category.HOME" />` +
   `DEFAULT` to the launcher intent filter, `android:launchMode="singleTask"`. ActivityManager
   restarts the home activity whenever it dies, and starts it at boot, with no receiver and no
   background-start restriction. Rollback is `adb shell cmd package set-home-activity <other>` or
   `adb uninstall` — **document the rollback command before the first attempt**, because if the
   Portal has no other home activity you will need adb to recover.
3. A foreground service is **not** a solution to this problem and should not be adopted for it. It
   raises process priority so the process survives, but a FGS still cannot start an activity from
   the background on Android 10+, so the screen stays dark. On API 34 it additionally needs an
   `android:foregroundServiceType` and the matching runtime permission. All cost, no benefit here.

At absolute minimum, if the design keeps the plain-Activity model: add an
`UncaughtExceptionHandler` that reschedules the Activity via `AlarmManager` + `PendingIntent`
before dying (this *is* permitted — an alarm-triggered activity start from a `setExactAndAllowWhileIdle`
alarm is still subject to BAL restrictions on Android 10+, so treat it as best-effort), and make
the settings sheet display `lastActivityRenderMs` alongside `lastSuccessfulRefresh` so the two
signals can disagree.

### C2. `BuildConfig` does not exist in this project. §5.9 and R4 both silently fail.

`app/build.gradle.kts:18-20` declares `buildFeatures { viewBinding = true }` only. **AGP 8.0
changed `android.defaults.buildfeatures.buildconfig` to default `false`**, so with AGP 8.2.2
(`build.gradle.kts:2`) the `BuildConfig` class is not generated at all.

Two design items depend on it:

- §5.9: "Ship a `BuildConfig` default URL so a fresh install works with no setup." — won't compile.
- R4 mitigation: "gate `HttpLoggingInterceptor` to debug" — `BuildConfig.DEBUG` won't compile either.

Fix:
```kotlin
buildFeatures {
    viewBinding = true
    buildConfig = true
}
defaultConfig {
    buildConfigField("String", "DEFAULT_ALBUM_URL", "\"https://photos.app.goo.gl/...\"")
}
```

Related, and worse for R4: `buildTypes.release` has `isMinifyEnabled = false` and **no
`signingConfig`**. `assembleRelease` therefore emits an unsigned APK that `adb install` rejects.
The realistic outcome is that you sideload `assembleDebug` forever — at which point
`BuildConfig.DEBUG` is `true` and the R4 mitigation gates nothing. Either add a signing config so
release builds are installable, or gate the logging interceptor on something other than
`DEBUG` (an explicit `buildConfigField("boolean", "HTTP_LOGGING", "false")` is honest and works
for both build types).

### C3. RGB_565 is the wrong recommendation, and it disables a strictly better option.

§5.7's arithmetic is right (see Verified Claims), but the conclusion is wrong on two counts.

**Quality.** RGB_565 is 5/6/5 bits. The worst case for 5-bit blue is a smooth gradient — skies,
skin tones, and above all the fade-to-black at the edge of a `fitCenter` letterbox against the
pure-black background (`activity_slideshow.xml:6`). You will get visible contouring on a large
panel viewed at conversational distance, permanently, on a device whose only job is displaying
photographs. Trading image quality for 4.4 MB on the single-purpose photo appliance is the wrong
side of that trade.

**Heap.** `minSdk = 26` (`app/build.gradle.kts:12`), so `Bitmap.Config.HARDWARE` is available on
every device that can install this APK. Hardware bitmaps are allocated in graphics memory, not the
Java heap, so they do not count against `dalvik.vm.heapgrowthlimit` — which is exactly the
constraint §5.7 is trying to manage and exactly what open item #5 ("real heap ceiling") is worried
about. **Glide already prefers hardware bitmaps by default on API 26+.** Setting
`DecodeFormat.PREFER_RGB_565` opts *out* of that, because hardware bitmaps are ARGB_8888-backed.
So the recommendation costs image quality in order to move memory from graphics memory *into* the
Java heap. It is a downgrade on both axes.

**Do instead:** leave Glide's decode format alone (default `PREFER_ARGB_8888` → hardware bitmap on
O+). The only hardware-bitmap hazards are software-canvas draws and `getPixels()`, neither of which
this app does, and the per-process FD ceiling, which is irrelevant with 2–3 live bitmaps.

While here: `SlideshowActivity.kt:80` hardcodes `=w1920-h1200`. Portal panels are 1280×800
(Mini / Portal 10) or 1920×1080 (Portal+). Derive the size from
`resources.displayMetrics`/the view. At 1280×800 that is 4.1 MB per bitmap instead of 8.8, and —
more importantly for C5 — roughly 2.2× fewer bytes per photo on disk.

### C4. The Glide target leak is real, and it is the thing that kills a months-long process.

The design flags it in one line. It deserves more, because the mechanism determines the severity.

`showPhotoAt` (`SlideshowActivity.kt:82-104`) allocates a **fresh anonymous `CustomTarget`** on
every advance and never clears it. In Glide 4, `RequestManager.track()` registers the target in
`TargetTracker` and the request in `RequestTracker`; entries are removed **only** by
`RequestManager.clear(Target)` / `untrack` — *not* on request completion. At the default 8 s
interval (`AppPreferences.kt:28`) that is 10,800 retained `CustomTarget` + `SingleRequest` pairs
per day, ~324,000 per month, growing without bound for the life of the process.

Worse: `SingleRequest` holds a strong reference to its `Resource<R>` until it is cleared. If that
holds here — and I would confirm it with a heap dump rather than take my word for it — every
decoded bitmap is retained, and the process OOMs in **tens of minutes**, not months. That would
also be a strong candidate for the unexplained crash in R4, which the design currently attributes
only to the album screen.

Note this bug is invisible to the design's chosen fix framing ("clear the previous target"), which
implies one outstanding target. The correct fix removes the class entirely:

```kotlin
Glide.with(this).load(url).into(binding.ivPhotoA)   // ViewTarget, stored in the view tag
```
`ViewTarget` is keyed on the view, so Glide clears the previous request automatically on every
load into the same view. Drive the crossfade from `.listener(RequestListener)` or
`.transition(DrawableTransitionOptions.withCrossFade(1500))` instead of hand-rolling it. This also
fixes C6 and gives you `onLoadFailed` for free.

Two adjacent defects the design did not catch:

- **No `onLoadFailed` override.** `CustomTarget` requires one; the code has only
  `onLoadCleared` (`:103`). §5.6's row "Image fetch 403 → Glide failure → skip photo, keep
  advancing" is **unimplementable as written** without it — today a failed load does nothing at
  all. And because `tvLoading` is set `GONE` at `:64` *before* the first `showPhotoAt`, a failed
  first load yields a pure black screen with no text and no advance for a full interval
  (`ivPhotoB` starts at `alpha=0`, `activity_slideshow.xml:14`).
- **The same `Drawable` instance is set on two ImageViews** (`:92` and `:98`). `Drawable` has a
  single `Callback`; `ImageView.setImageDrawable` reassigns it, and bounds/alpha state is shared
  between the two views. Harmless for a static `BitmapDrawable` in the common case, broken for
  anything animated, and a real source of one-off rendering oddities. Use
  `resource.constantState?.newDrawable()`, or better, swap the A/B *roles* rather than copying the
  drawable across.

### C5. §5.5's offline guarantee does not survive URL rotation — the exact case it exists for.

§5.5 says "cache image bytes, not just URLs… `lh3` URL lifetime is unknown; a frame that dies when
they expire is unacceptable." Correct goal. But if the implementation is Glide's disk cache, the
goal is not met:

- Glide's disk cache is **keyed on the URL** (`GlideUrl.getCacheKey()` returns the full string by
  default). When Google rotates the `pw/` token or changes the size suffix, every key changes,
  every photo is re-downloaded, and the old entries are LRU-evicted. Offline capability
  evaporates precisely when the URLs expire.
- Glide's default disk cache is **250 MB** (`DiskCache.Factory.DEFAULT_DISK_CACHE_SIZE`). 302
  photos at 1920×1200 JPEG is roughly 300 MB. At steady state you sit permanently just over the
  ceiling and thrash — every full pass re-downloads. C3's display-resolution fix (1280×800) drops
  this to ~140 MB and makes the problem go away, which is a second reason to do it.

**Fix:** derive a stable key from the `pw/` media-key path segment (strip the size suffix and any
volatile query params) and either supply it via a custom `GlideUrl` subclass overriding
`getCacheKey()`, or keep your own content-addressed file store and hand Glide a `File`. Then
size the cache explicitly rather than inheriting 250 MB.

**But note:** configuring Glide's disk cache requires an `AppGlideModule`, and
`app/build.gradle.kts:51` declares `glide:4.16.0` **with no annotation processor**. There is no
`ksp`/`kapt` plugin and no `glide-compiler`/`glide-ksp` dependency, so `@GlideModule` is inert. You
need either:
```kotlin
plugins { id("com.google.devtools.ksp") version "1.9.22-1.0.17" }
dependencies { ksp("com.github.bumptech.glide:ksp:4.16.0") }
```
or the legacy manifest `<meta-data android:name="…GlideModule" android:value="GlideModule"/>` path.
Phase 2 cannot deliver §5.5 without this.

### C6. Immersive mode is applied once and will drift over months.

`SlideshowActivity.kt:38-44` sets `systemUiVisibility` in `onCreate` only. The system clears these
flags whenever the window loses and regains focus — a system dialog, the Assistant, a volume
overlay. `IMMERSIVE_STICKY` re-hides after a *transient* reveal, but it does not restore flags that
were reset on a focus change. The standard, necessary idiom is to re-apply in
`onWindowFocusChanged(hasFocus)`, which the code does not do. Over months, the expected end state
is a permanently visible navigation bar on the wall.

On the deprecation question: `setSystemUiVisibility` is deprecated at API 30 but still functional
through API 34 — it is not a correctness bug today. Migrate anyway, since the replacement is
cleaner and works back to API 21 through the compat shim:

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
WindowInsetsControllerCompat(window, binding.root).apply {
    hide(WindowInsetsCompat.Type.systemBars())
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
```
and re-apply it in `onWindowFocusChanged`.

### C7. Activity recreation is unguarded, and a night-mode schedule will trigger it twice a day.

The manifest sets `android:screenOrientation="landscape"` (`AndroidManifest.xml:36`) but **no
`android:configChanges`**. Any configuration change destroys and recreates the Activity. The one
that matters on this device: `uiMode`. `Theme.PortalGallery` parents
`Theme.MaterialComponents.DayNight.NoActionBar` (`themes.xml:3`), and a wall-mounted appliance is
very likely to have a scheduled day/night transition. That is two full Activity recreations per
day, ~700 per year, each re-inflating the layout, re-reading the manifest, and restarting the
slideshow at index 0 (`currentIndex` is not saved — no `onSaveInstanceState`).

Add:
```xml
android:configChanges="uiMode|orientation|screenSize|smallestScreenSize|screenLayout|density|fontScale|locale|layoutDirection|keyboardHidden|navigation"
android:resizeableActivity="false"
```
and pin `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_YES)` in `Application.onCreate`.

Separately, **verify `screenOrientation="landscape"` on the actual panel.** If the Portal's
display has a portrait natural orientation with the panel physically mounted rotated — which is
common on appliance hardware — `"landscape"` picks rotation 90 or 270 by the framework's rule, not
yours, and you have a 50 % chance of an upside-down frame. `android:screenOrientation="locked"`
(honour whatever the device boots into) is the safer choice here; confirm with
`adb shell dumpsys display | grep -i rotation`.

---

## Suggestions

### S1. On Doze — the brief asked me to attack the 6 h refresh. It survives.

I cannot make the Doze objection stick, and the design should record why so nobody re-raises it:

- **Doze does not apply.** `DeviceIdleController` requires *screen off* **and** *unplugged from
  power* **and** stationary. A mains-powered, always-on wall frame never satisfies the first two.
  Doze is structurally unreachable on this device. Confirm on the unit with
  `adb shell dumpsys deviceidle` — expect `mCharging=true`, `mScreenOn=true`, state `ACTIVE`.
- **App Standby buckets do not bite either.** Bucket assignment is usage-driven; an app whose
  Activity is the foreground task 24/7 is pinned to `ACTIVE`, which has no job throttling. The
  restrictive buckets (`RARE`, `RESTRICTED`) require prolonged non-use.
- **Battery optimisation whitelisting is irrelevant** while plugged in.

The 15-minute floor is a *minimum*, not a maximum:
`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS = 900_000`. `PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)`
is entirely valid.

The one real caveat, worth writing into §5.7: **a `PeriodicWorkRequest` with no explicit flex gets
`flexDuration == repeatInterval`**, so the job may execute anywhere within each 6 h window —
meaning up to ~12 h between consecutive actual executions. Harmless for a photo frame, but state
it so it is not diagnosed as a bug later. If you want tighter bounds, pass an explicit flex:
`PeriodicWorkRequestBuilder<RefreshWorker>(6, HOURS, 30, MINUTES)`.

WorkManager also does not need Play Services (it uses `JobScheduler` on API 23+), which matters
because Portal is not a GMS device.

### S2. The first launch shows a black screen for up to 6 hours.

§5.3 renders the on-disk manifest at startup, but on a fresh install there is no manifest, and a
`PeriodicWorkRequest`'s first execution lands somewhere inside the first flex window (S1). Enqueue
a `OneTimeWorkRequest` on first launch, or simply kick a refresh from the Activity's
`lifecycleScope` on cold start. Use `ExistingPeriodicWorkPolicy.UPDATE` (WorkManager 2.8+) rather
than `KEEP` so reinstalls pick up interval changes.

**Do not make the initial refresh expedited.** On API 26–30, `setExpedited()` runs the worker as a
foreground service, which requires `getForegroundInfo()`, a notification channel, and the
`FOREGROUND_SERVICE` permission — none of which are in the manifest. Given the Portal is most
likely API 28–30, this would bite immediately. A plain one-time request is fine.

### S3. WorkManager is not in the dependency list, and version choice is constrained.

`app/build.gradle.kts:37-57` has no `androidx.work` entry. Add:
```kotlin
implementation("androidx.work:work-runtime-ktx:2.9.1")
```
**Pin to 2.9.x, not 2.10+** — WorkManager 2.10 requires `compileSdk = 35` and this project is on
34 (`app/build.gradle.kts:8`).

Broader point: WorkManager is defensible here but is not obviously the right tool. The app is a
single Activity that is in the foreground permanently; a `delay(6.hours)` loop in a
`lifecycleScope`/`repeatOnLifecycle` coroutine gives deterministic timing, is trivially unit
testable, and adds no dependency, no Room database, and no `ContentProvider` initialiser. The one
genuine advantage WorkManager has is that it runs when the Activity is dead — which, per C1, is
currently a *liability* rather than a feature, because it makes a dead frame look healthy. Once C1
is resolved, either choice is fine; I would take the coroutine for simplicity and add a one-shot
refresh on every Activity start.

### S4. Static overlay burn-in — a wall-frame-specific defect the design missed.

`activity_slideshow.xml:45-52` contains a `TextView` reading "Tap to pause • Hold to exit". The
comment says "shown briefly on first touch." **No code ever hides it.** It has no id and is never
referenced from `SlideshowActivity`. It is therefore rendered continuously, in the same pixels, on
an always-on display, for months. Portal panels are LCD so this is image persistence rather than
true OLED burn-in — lower risk, but non-zero over a multi-month duty cycle, and in any case it is
visible clutter on a photo frame. Give it an id and hide it after a few seconds.

Same category: `fitCenter` (`activity_slideshow.xml:12`, `:21`) against black means the pillarbox
bars sit in fixed pixel columns for the life of the device. Consider a blurred-fill background or
a slow Ken Burns pan so no region is perfectly static.

### S5. Watchdog caveats (§5.7).

- A main-thread `Handler` watchdog cannot detect a main-thread stall. It correctly covers the
  stated case (a dropped Glide callback), but the design should say that explicitly so nobody
  believes it covers ANRs.
- It must not fire while paused, and it needs a `lastRenderedAtMs` written from
  `onResourceReady`/`onLoadFailed` to have anything to compare against.
- Extend it: after N consecutive load failures, fall back to a cached image or a bundled default
  rather than leaving the frame on a stale or black surface. C6 in the design's own constraint
  list ("frame must never go blank") is not currently enforced by anything.

### S6. `togglePause()` has a real off-by-one bug the design did not list.

`togglePause` (`SlideshowActivity.kt:121-125`) sets `isPaused = true` but never calls
`handler.removeCallbacks(advanceRunnable)`. The already-posted callback still fires, so
**tapping to pause advances one more photo before stopping.** The pause overlay appears
immediately, so from the user's point of view the frame changes photo while displaying a pause
icon. Add the `removeCallbacks` to the pause branch.

Also note: `advancePhoto()` (`:107-112`) does not check `isPaused`, which is what lets the stale
callback through.

### S7. Correction to the design's second listed bug.

§5.7 claims: "If the *first* image fails, `isFirstPhoto` stays `true` forever and crossfade never
initialises." **Not accurate.** `isFirstPhoto` is only set false inside `onResourceReady`
(`:86-87`), so on failure it stays true — but the *next successful* load takes the first-photo
branch, sets `ivPhotoB` directly, and clears the flag. Crossfade initialises normally from then on.
That behaviour is actually correct.

The real defect at that site is the one in C4: no `onLoadFailed`, so failures are entirely silent,
and a failed first load leaves a black screen with `tvLoading` already hidden. Fix the right thing.

### S8. Dead dependencies and manifest changes after the OAuth deletion.

Answering the brief's question directly — deleting the auth stack leaves several loose ends:

**Manifest** (`AndroidManifest.xml`):
- `SignInActivity` is the `LAUNCHER` (`:13-21`). `SlideshowActivity` must inherit the
  `MAIN`/`LAUNCHER` intent filter **and be changed to `android:exported="true"`** (`:35` currently
  `false`). On API 31+, a component with an intent filter and no explicit `exported` fails to
  install with `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED`, and `exported="false"` with a LAUNCHER
  filter means no icon.
- `android:allowBackup="true"` (`:7`) — with Auto Backup this ships `SharedPreferences`, i.e. the
  OAuth **access and refresh tokens** (`AppPreferences.kt:11-21`), off-device. Set
  `allowBackup="false"`. This belongs next to the "revoke the leaked client secret" action item in
  §5.1, which is correct and urgent (`AuthManager.kt:16-17`).
- Add `android:windowSoftInputMode="adjustResize"` for the §5.9 settings sheet — a text field in a
  fullscreen immersive window will otherwise pan the whole slideshow, and immersive flags need
  re-applying after the IME dismisses (see C6).

**Dependencies** (`app/build.gradle.kts`):
- **`okhttp` is only on the classpath transitively.** It arrives via `retrofit` and
  `logging-interceptor` (`:46-48`). If `SharedAlbumSource` uses OkHttp to fetch the share page —
  and it should — add an **explicit** `implementation("com.squareup.okhttp3:okhttp:4.12.0")`
  before removing Retrofit, or the build breaks in a confusing way.
- Remove: `retrofit`, `converter-gson`, `lifecycle-viewmodel-ktx` (already unused — there are no
  ViewModels in the codebase today).
- Remove `recyclerview` and `cardview`: verified used only by `activity_album_picker.xml` and
  `item_album.xml` respectively, both of which are being deleted.
- **Keep `material`** — `constraintlayout` is only used by the two deleted layouts and can go, but
  `themes.xml:3` and `:12` both parent `Theme.MaterialComponents.*`, so removing the Material
  dependency breaks the theme. It is also what you want for the §5.9 settings sheet
  (`BottomSheetDialogFragment` + `Slider`).
- Delete the orphaned layouts (`activity_sign_in.xml`, `activity_album_picker.xml`,
  `item_album.xml`) — otherwise ViewBinding keeps generating binding classes for them.
- §5.1's deletion list omits `data/model/Album.kt`, `data/model/MediaItem.kt`,
  `data/repository/PhotosRepository.kt`, and `ui/albums/AlbumAdapter.kt`. All are unreachable
  after the listed deletions.
- **No test dependencies exist.** §6 calls golden-file parser tests "the highest-value tests in the
  project" but there is no `src/test/` directory (verified: `app/src/` contains only `main/`) and
  no `testImplementation("junit:junit:4.13.2")`. Phase 2 must create the source set and the
  `src/test/resources/` directory for `album-fixture.html`.
- `android.enableJetifier=true` (`gradle.properties:2`) is obsolete — nothing here is a support-library
  artifact. Removing it speeds up builds.

### S9. Phase 1's toolchain plan will not work with the JDKs on this machine.

§8.1 is right that the wrapper is missing (verified: `gradle/wrapper/` contains only
`gradle-wrapper.properties`; no `gradlew`, no `gradle-wrapper.jar`) and right that Gradle 8.4
(`gradle-wrapper.properties:3`) predates Java 21 support, which landed in Gradle 8.5.

But bumping the wrapper to 8.7 does not finish the job. Verified available JDKs: **21, 11, 8 — no
17.** AGP 8.2.2 (`build.gradle.kts:2`) targets JDK 17 and is outside its supported matrix on JDK
21; running it there is a well-known source of `Unsupported class file major version 65` from
bundled tooling. Two workable paths:

- **Install JDK 17** and point Gradle at it (`org.gradle.java.home` or a
  `kotlin { jvmToolchain(17) }` + matching daemon JVM). Least disruptive — keeps AGP and Kotlin
  pinned. Recommended.
- **Bump AGP to 8.6+**, which supports JDK 21, and bump the Kotlin plugin to match.

Either way, note the wrapper jar has to be regenerated from a system Gradle
(`gradle wrapper --gradle-version 8.7`), since there is no `gradlew` to bootstrap from.

### S10. Verify the device before anything else — the API level is a hard gate.

The brief says the API level is unknown, and `minSdk = 26`. Portal generations shipped on
Android 7.1 (API 25), 9 (28), and 10 (29) depending on model. **If the unit is API 25, this APK
cannot install at all**, and several assumptions in this review (hardware bitmaps, expedited-work
behaviour) change. The fact that it previously ran is the only evidence that API ≥ 26.

One adb session answers most of the design's open items:
```
adb shell getprop ro.build.version.sdk        # hard gate on minSdk 26
adb shell getprop ro.product.model
adb shell dumpsys deviceidle                  # confirms Doze is unreachable (S1)
adb shell dumpsys power | grep -i 'wake\|stay_on'
adb shell dumpsys dreams                      # is DreamService viable? (C1)
adb shell settings get secure screensaver_components   # what owns the screen today?
adb shell dumpsys display | grep -i rotation  # natural orientation (C7)
adb shell getprop dalvik.vm.heapgrowthlimit   # open item #5, the real heap ceiling
adb shell df /data                            # disk cache budget (C5)
```
Add these as an explicit Phase 0. Several Phase 2/3 decisions are unmakeable without them.

### S11. Smaller items.

- **§5.3's absolutism invites a bug.** "A frame reading from disk cannot spin, cannot ANR at
  startup" — a disk read *on the main thread* absolutely can ANR, and StrictMode will flag it.
  Say explicitly: manifest read on `Dispatchers.IO`, render on `Main`.
- **Atomic swap needs a sync.** `File.renameTo` on the same filesystem is atomic with respect to
  concurrent readers, but not durable across power loss without `FileOutputStream.fd.sync()`
  before the rename. Cheap insurance; a torn manifest is a black frame. Also guard the
  read/write race — the worker and the Activity run in the same process by default.
- **Reshuffling each pass** (§5.7) can repeat a photo across the boundary if the last of pass N is
  the first of pass N+1. Reshuffle, then rotate if `newList.first() == oldList.last()`.
- **Set an explicit `User-Agent`** on the share-page fetch. The 302-URL result was obtained with
  curl's default UA; Google serves different markup by UA and OkHttp's default (`okhttp/4.12.0`)
  is not what was validated. Pin a browser UA and record it in the fixture's provenance so the
  golden test and the device agree.
- **Config via Intent extra.** §5.9 puts the share URL behind a touch-typed field. Also accept it
  from an Intent extra so it can be set over adb
  (`adb shell am start -n …/.SlideshowActivity -e album_url "https://…"`), which fits the
  sideload-only model and avoids typing a URL on an on-screen keyboard.
- **`android:hardwareAccelerated="true"`** (`AndroidManifest.xml:11`) has been the default since
  API 14; harmless, but it is noise.
- **`IMMERSIVE_STICKY` may eat the tap-to-pause gesture** near screen edges, since the first swipe
  or tap in the transient-bar region is consumed by the system. Minor, but worth knowing before
  someone debugs "the tap doesn't work sometimes."

---

## Verified Claims

Checked directly against the repository and the fixture:

| Design claim | Status | Evidence |
|---|---|---|
| 1920×1200 ARGB_8888 ≈ 9 MB (§5.7) | **Correct** | 1920 × 1200 × 4 = 9,216,000 B = 8.79 MiB |
| RGB_565 "halves it" (§5.7) | **Correct arithmetically** | 2 B/px → 4.39 MiB. But the wrong choice — see C3 |
| "Preload exactly one ahead" (§5.7) | **Correct** | At an 8 s interval one lookahead is ample; Glide's `PreloadTarget` self-clears on completion, so it does not add to the C4 leak |
| ~6 h WorkManager interval is expressible | **Correct** | 15 min is a floor (`MIN_PERIODIC_INTERVAL_MILLIS = 900_000`), not a ceiling |
| Doze breaks a 6 h refresh | **Not established — I believe it is false** | Doze needs screen-off + unplugged; a mains-powered always-on frame never qualifies. See S1 |
| `showPhotoAt` never clears the previous `CustomTarget` (§5.7) | **Correct, and understated** | `SlideshowActivity.kt:82-104`; see C4 for the retention mechanism and rate |
| Loads "can land out of order" (§5.7) | **Correct** | Independent targets, no request cancellation, no index guard in `onResourceReady` |
| `isFirstPhoto` stays true "forever" (§5.7) | **Incorrect as stated** | The next *successful* load clears it (`:86-87`). The real defect is the missing `onLoadFailed` — see S7 |
| `photos.shuffled()` runs once at startup (§5.7) | **Correct** | `SlideshowActivity.kt:63` |
| `HttpLoggingInterceptor.Level.BODY` unconditional (§1) | **Correct** | `PhotosApiClient.kt:25`; buffers every response body in full. A plausible contributor to R4, and it would also buffer the 1.4 MB share page |
| `buildErrorMessage` calls `errorBody()?.string()` on the main thread (§1) | **Correct** | `AlbumPickerActivity.kt:111-118`, invoked from a `lifecycleScope` (Main) catch block at `:83` |
| Hardcoded `CLIENT_ID`/`CLIENT_SECRET` ship in the APK (§5.1) | **Correct and urgent** | `AuthManager.kt:16-17`; compounded by `allowBackup="true"` — see S8 |
| Interval slider is lost with `AlbumPickerActivity` (§5.9) | **Correct** | `AlbumPickerActivity.kt:62-67` is the only writer of `slideshowIntervalSeconds`; `SlideshowActivity.kt:117` is the only reader |
| Gradle wrapper missing (§8.1) | **Correct** | No `gradlew`, no `gradle/wrapper/gradle-wrapper.jar` |
| Only JDK 21/11/8 present (§8.1) | **Correct** | `/usr/libexec/java_home -V`: 21.0.7, 11.0.6, 1.8.0_181. No 17 — see S9 |
| Gradle 8.4 predates Java 21 support (§8.1) | **Correct** | Java 21 support landed in Gradle 8.5. But AGP 8.2.2 on JDK 21 is a second, unaddressed problem |
| Fixture: 302 unique `lh3/pw/` URLs (§4) | **Reproduced** | The design's own §5.4 regex yields exactly 302 unique |
| Fixture: 362 total `lh3` references (§4) | **Reproduced** | 362 occurrences of `lh3.googleusercontent.com`; 341 of them are `/pw/` paths, so 39 dupes among photo URLs rather than the stated ~60 |
| Fixture: 2 `AF_initDataCallback` blocks (§4) | **Reproduced** | `grep -c` → 2 |
| `setSystemUiVisibility` deprecated at API 30 | **Correct, but not yet broken** | Still functional through API 34. The actual bug is the missing re-apply — see C6 |

---

## What would change my verdict

C1 (a restart authority, or an explicit written acceptance that a dead frame is a
walk-over-and-tap event *plus* a liveness signal that does not lie), C2 (`buildConfig = true`
and a coherent debug/release story), C3 (drop RGB_565, use display-resolution decoding), and C4
(switch to `ViewTarget` and add `onLoadFailed`) are the four I would block on. C5–C7 and S1–S3 are
things I would expect to see reflected in the document before implementation starts, but they are
corrections rather than rethinks.
