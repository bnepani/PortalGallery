# Task 1 code quality review — `phase1-collage` (e3c45df..2867cb1)

Reviewer: code-quality pass. Scope: code quality only; spec compliance was reviewed
separately and is not re-litigated here.

Commits under review:

- `715b2dd` — Fix watchdog double-post accumulating one loop per presence wake
- `dc04693` — Disable Glide disk cache for local files
- `33ef041` — Make AlbumSync download counters atomic; fix duplicate resolution-gate runs
- `2867cb1` — Declare foregroundServiceType on PresenceService

## Verification run

- `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true GRADLE_OPTS=-Djava.net.preferIPv4Stack=true ./gradlew :app:testDebugUnitTest --rerun-tasks`
  → 60 tests across 7 classes, 0 failures, 0 errors.
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL with `abortOnError = true`. No
  `UnusedAttribute` warning for the API-29 attribute on `minSdk = 26`. Remaining lint
  output is 14 pre-existing warnings unrelated to this range (`SetTextI18n`,
  `GradleDependency`, `StaticFieldLeak`, `MissingPermission` on `setExactAndAllowWhileIdle`,
  etc.).

Caveat: none of the 60 tests touch any code in this range. There is no `AlbumSyncTest`,
and `SlideshowActivity` is not unit-testable as written. The tests confirm nothing was
broken; they confirm nothing was fixed.

Note on tree state: `HEAD` at review time was `28ca623` (one commit past the range) with a
modified `CollageLayoutTest.kt`. The three files under review are unchanged since
`2867cb1`.

---

## Strengths

- **All three are real bugs, minimally fixed.** Each was traced independently. The
  watchdog double-post is genuine: `exitSleep()` at `SlideshowActivity.kt:520` posts, its
  own `startActivity(REORDER_TO_FRONT)` at `:501` later delivers `onResume`, which posts
  again at `:886`. The `failures = Int.MAX_VALUE` race is genuine: `async` inside
  `withContext(Dispatchers.IO)` is real parallelism, and a concurrent `failures++` could
  clobber the sentinel and let a thumbnailed sync be indexed.
- **`aborted` as a separate flag is better than the obvious fix.** Making `failures` an
  `AtomicInteger` alone would have preserved the actual defect — overloading a counter
  with a sentinel. Splitting them is the right call.
- **`restartWatchdog()` is the right shape and the right place** — immediately after
  `scheduleNext()`, its sibling. The name says what it does.
- **The manifest fix is correct on every axis checked.** `camera` is the right type for a
  CameraX `ImageAnalysis` service; `FOREGROUND_SERVICE_CAMERA` is the matching normal
  permission; unrecognised permissions and manifest attributes are ignored by the
  installer and by binary-XML resolution on API 26–28, so nothing breaks on the Portal.
  The "Harmless on the Portal's API 28" note is exactly the right pre-emption. This is
  the best-written of the four commits and the only one with a body.

---

## Critical

### 1. The resolution gate still writes thumbnails to disk, and they eventually get indexed as full photos

Pre-existing — the old `failures = Int.MAX_VALUE` code did the same thing. Flagged as
Critical anyway because commit `33ef041`'s stated purpose is hardening this gate, and the
new comment at `AlbumSync.kt:124-125` asserts it is now protected. This is a false sense
of safety.

`resolutionChecked.compareAndSet(false, true)` means exactly one coroutine in the chunk
runs the gate. The other three skip straight to `store.writePhoto()` at
`AlbumSync.kt:163` and write their bytes — which are thumbnails, same as the one that
failed the gate. Trace:

- **Sync 1:** gate fails, `aborted.set(true)`, `Result.Failure`. Index untouched
  (invariant held, display unaffected) — but 3 thumbnail JPEGs are now on disk.
- **Sync 2:** `missing = wanted.filterKeys { !store.hasPhoto(it) }` at `:121`. Those 3
  files exist with length > 0, so `hasPhoto` is true → excluded from `missing` → **never
  re-downloaded, never re-validated.** Gate fires on a different photo, aborts, 3 more
  thumbnails land.
- **Sync N:** `missing` is finally empty. No downloads → the gate never runs → `aborted`
  stays false → sync **succeeds** → `present = wanted.filterKeys { store.hasPhoto(it) }`
  at `:179` is the whole album → `saveIndex()` writes entries with the *parser's*
  width/height, not the files'.

Result: a frame full of upscaled 384×512 thumbnails, reported as a successful sync. The
gate's own failure message — "refusing to index thumbnails" — is defeated by the sync
that follows it.

The fix is small: on the abort path, delete what this pass wrote (or write to a
quarantine name until the gate clears). Alternatively, run the gate on the first download
synchronously before dispatching the rest of the chunk, so nothing is written before the
verdict is known.

**Out of stated scope for Task 1.** Not requested in this diff, but it should be a
tracked follow-up before collage work builds on top, and the comment at `:124-125` should
not imply the gate is now sound.

---

## Important

### 2. `restartWatchdog()`'s KDoc opens with a false statement

`SlideshowActivity.kt:802` says *"The only place watchdogRunnable is posted."* It is not —
`watchdogRunnable.run()` posts itself at `SlideshowActivity.kt:132`. In a codebase where
comments are the primary maintenance artifact, a load-bearing claim that is contradicted
670 lines up is worse than no comment: the next person adding a post site will read it,
believe the invariant is enforced, and not look.

The clean fix makes the claim true *and* closes the fifth-post-site trap: have line 132
call `restartWatchdog()` instead of `handler.postDelayed(this, interval)`. It is
behaviourally identical (both recompute the interval from prefs), and it makes the loop
self-healing — any stray duplicate is removed the next time the runnable fires. A one-line
back-reference at the `watchdogRunnable` declaration (`:121`) pointing to
`restartWatchdog()` would close the remaining gap.

### 3. The KDoc's account of the bug's severity is wrong

`:806` says *"one extra loop per presence wake, forever."* `Handler.removeCallbacks(Runnable)`
removes **all** pending posts of that runnable, and both `enterSleep()` (`:448`) and
`onPause()` (`:867`) call it. So duplicates collapse at the next sleep or pause and cannot
accumulate across wakes. `exitSleep()`/`enterSleep()` strictly alternate (guarded by
`shouldSleep != isAsleep`), and `onPause`/`onResume` are balanced. The real ceiling is two
concurrent loops within a single awake period.

The fix is still correct and worth having — but this repo's comments earn their keep by
being precisely accurate about the failure they prevent, and "forever" is not. Suggested:
*"…one extra loop, until the next enterSleep()/onPause() clears them."*

### 4. The CAS narrows the gate, under a "fix duplicate runs" banner

The old racy `!resolutionChecked` read let up to 4 coroutines enter the gate; the
`compareAndSet` at `AlbumSync.kt:156` guarantees exactly 1. If a thumbnail substitution
ever affected only some URLs, the old code sampled more and the new code samples fewer.
This is a strict reduction in detection sensitivity sold as a bug fix. The saving is one
avoided `BitmapFactory` bounds decode per sync — negligible against a gate whose whole job
is catching silent degradation. Worth reconsidering, or at minimum saying in the comment
that sampling exactly one still is a deliberate choice.

### 5. `store.writePhoto()` can throw, and nothing catches it

On the concurrency axis it is safe: the temp name is `"${target.name}.tmp"`
(`PhotoStore.kt:90`), unique per id, and `wanted` is a map so no two coroutines ever share
an id. A leftover `.tmp` after a crash is cleaned by `prune()`, since
`substringBeforeLast('.')` yields `<id>.jpg`, never a real id.

The hazard is the exception path. Unlike `download`, which is wrapped in `runCatching` at
`AlbumSync.kt:148`, `writePhoto` is bare — and both the `out.write()` and the
`check(tmp.renameTo(target))` at `PhotoStore.kt:96` can throw. Inside `async`, that cancels
the `coroutineScope`, propagates out of `sync()` entirely (so no `Result.Failure`), and
lands in `refreshLoop()` at `SlideshowActivity.kt:289`, which has no try/catch, inside a
bare `lifecycleScope.launch` with no `CoroutineExceptionHandler`. That is an app crash.
One ENOSPC during a 6-hourly sync takes the frame down — directly against "the frame never
blanks," on a device that is supposed to run unattended for months.

Pre-existing and out of scope for this diff, but it is the highest-value follow-up here
and it is one `runCatching` away.

### 6. Three of the four commits have empty bodies

The repo standard is unambiguous — `e3c45df`, `2c33402`, `1291ac0` and `ceaca6b` all carry
multi-paragraph bodies explaining the observed failure and the reasoning. `2867cb1`
matches that bar. `715b2dd`, `dc04693` and `33ef041` are subject-only, and `33ef041` is
the subtlest change in the set: a lost-update race across four coroutines with a
sentinel-overload defect, explained in nothing but a subject line and a two-line inline
comment that describes the design being removed.

---

## Minor

7. **`coerceAtMost(missing.size)` at `AlbumSync.kt:213` is now dead code.** It existed
   solely to clamp the `Int.MAX_VALUE` sentinel. `failures` increments at most once per
   entry in `missing`, so it can never exceed `missing.size`. Leftover scaffolding from
   the removed sentinel — remove it, or it will read as a live guard forever.
8. **The comment at `AlbumSync.kt:124-125` describes the old design.** "`failures++` …
   could overwrite the abort flag" makes no sense against the code beneath it, where
   `failures` and `aborted` are separate variables. It needs to say *that this is why they
   are now separate*. It also says nothing about why `done` and `resolutionChecked` are
   atomic (lost progress increments; check-then-act on the gate) — a reader cannot tell
   whether those were converted for a reason or for symmetry.
9. **`aborted` is vaguer than it needs to be.** It is set in exactly one place, for
   exactly one reason. `resolutionGateFailed` would be self-documenting and would match
   the failure string at `:174`.
10. **No `stopWatchdog()` counterpart.** The two removal sites (`SlideshowActivity.kt:448`,
    `:867`) remain bare `handler.removeCallbacks(watchdogRunnable)`. If the point is a
    single guarded entry point, the exit should be symmetric.
11. **`restartWatchdog()` has no state guard where `scheduleNext()` has three.** Harmless
    today — all four call sites guard correctly, and the runnable checks `!isAsleep`
    itself — but a guard-free helper presented as *the* entry point invites a future
    caller to arm a timer during quiet hours.
12. **"Pure churn" at `SlideshowActivity.kt:656` overstates.** `AUTOMATIC` caches a
    *downsampled* resource, so `NONE` does trade CPU (full-JPEG decode per show) for
    avoided writes. The trade is clearly right at a 30s dwell, but the comment hides that
    a trade exists — and it names the wrong harm. The real reason is flash write wear on a
    months-long deployment plus duplicating a ~92MB library that `PhotoStore` already
    guarantees is permanently on disk. That framing would tie it to `PhotoStore.kt:8-19`
    and match the repo's standard.
13. **The manifest comment implies the permission+type pair is sufficient on API 34. It
    is not.** On 34, `startForeground()` with type `camera` also requires CAMERA to be
    *granted at call time* or it throws `SecurityException`. `PresenceService.onCreate()`
    calls `startForeground()` unconditionally at `PresenceService.kt:86`, with no
    permission check. Unreachable on API 28, and the fix correctly satisfies lint — but
    the comment currently reads as "Android 14 is now handled," and it is not.
14. **Two stacked comment blocks above `<service>`** (`AndroidManifest.xml:107-112`) read
    awkwardly; they should be one. And *"which went unnoticed because lint had never run
    here"* is process history, not a reason this attribute exists — it will be stale the
    moment lint is in CI.
15. **The abort path skips `onProgress`.** `AlbumSync.kt:159-160` returns before the
    progress callback, so the "Syncing photos… n / total" text stalls one short on the
    aborting pass. Cosmetic, pre-existing, and only visible on a first sync.

---

## Assessment

**Needs changes — small ones, then approved.**

The engineering is sound. Three real bugs, correctly diagnosed, fixed minimally and in the
right places, with no scope creep and lint now green. `restartWatchdog()` and the
`aborted`/`failures` split are both better designs than the minimum that would have worked.

What blocks it is that two of the three claims in the `restartWatchdog()` KDoc are false —
"the only place it is posted" (contradicted by `SlideshowActivity.kt:132`) and "forever"
(contradicted by `removeCallbacks` semantics). In most codebases those are nits. Here they
are not: comments are this repo's distinguishing asset, this KDoc is the sole thing
preventing the exact regression it describes, and a maintainer who trusts it will be
misled about both where the runnable is posted and how bad it is when it goes wrong.

### Before merge (all small, mostly comment-only)

- Fix the two false claims in the `restartWatchdog()` KDoc; make `SlideshowActivity.kt:132`
  call `restartWatchdog()` so the "only place" claim becomes true and the loop self-heals.
- Rewrite `AlbumSync.kt:124-125` to describe the code that is there, and drop the now-dead
  `coerceAtMost` at `:213`.
- Soften the API-34 claim on the manifest comment, or note the CAMERA-granted requirement.
- Give `715b2dd`, `dc04693` and `33ef041` commit bodies at the repo's standard.

### Track as follow-ups, not for this diff

- The resolution-gate disk leak (Critical #1).
- The unguarded `writePhoto` throw (Important #5).

Both are pre-existing, both are load-bearing for the stated invariants, and #1 in
particular should not be sitting under collage work that adds more concurrent rendering
paths.
