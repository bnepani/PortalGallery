# PortalGallery

A photo frame for Meta Portal that displays a Google Photos **public shared album**.

No sign-in, no OAuth, no Google API. The app fetches a public share page, downloads the
photos to local storage, and renders from disk — so the frame keeps working when the
network is down, and there are no credentials to expire or leak.

> The Google Photos Library API was ruled out deliberately: the March 2025 restriction
> removed read access to a user's existing library. Confirmed empirically —
> `403 PERMISSION_DENIED "Request had insufficient authentication scopes."` See
> `docs/plans/2026-08-17-portalgallery-photo-source-design.md` for the full rationale
> and the three adversarial reviews of it.

---

## Changing the album

### 1. Get a share link

In Google Photos: open the album → **Share** → **Create link**. You want a
`https://photos.app.goo.gl/...` URL.

A `photos.google.com/album/...` URL will **not** work — that is your private owner-side
view and requires signing in. The app rejects it with an explanatory message.

### 2. Check it before committing to it

```bash
python3 tools/check_album_ordering.py "https://photos.app.goo.gl/YOURLINK"
```

Confirms the album is publicly readable, and reports photo count, date range and
ordering. Ordering matters — see *Known limits* below.

### 3. Point the app at it

**Per device, no rebuild** (the usual way):

```bash
adb shell am start -n com.example.portalgallery/.ui.slideshow.SlideshowActivity \
  -e album_url "https://photos.app.goo.gl/YOURLINK"
```

Persists to `SharedPreferences` and takes precedence over the built-in default.

**Change the built-in default** (survives `pm clear`, applies to fresh installs):
edit `DEFAULT_ALBUM_URL` in `app/build.gradle.kts`, then reinstall.

**Reset to the built-in default:**

```bash
adb shell pm clear com.example.portalgallery   # also deletes downloaded photos
```

**See what is currently set:**

```bash
adb shell run-as com.example.portalgallery \
  cat /data/data/com.example.portalgallery/shared_prefs/portal_gallery.xml
```

### What happens next

The frame keeps showing the old photos until the new album syncs — it never blanks
during a switch. After the first successful sync, photos not in the new album are
deleted automatically, so albums do not accumulate on disk.

Resolution order: **SharedPreferences → `BuildConfig.DEFAULT_ALBUM_URL` → nothing**.

---

## On-device settings

**Long-press the frame** to open settings. No adb, no laptop.

| Setting | Notes |
|---|---|
| Transition | Crossfade, Slide, Zoom, Cut, or Random |
| Transition speed | 200–3000 ms |
| Slow pan & zoom | Ken Burns drift across each photo; also keeps pixels moving on an always-on panel |
| Time per photo | 3–120 s |
| Quiet hours | Enable, and set sleep/wake times with a picker |
| Sleep now | Same as the adb command; clears at the next scheduled boundary |
| Album | Read-only — URL, photo count, orientation split, last sync |

Changes apply from the next photo. The slideshow reads preferences live, so nothing
needs restarting.

The album URL is intentionally not editable here: typing a share link on a wall-mounted
touchscreen is unpleasant, and a typo silently breaks the frame. Change it over adb.

---

## Videos

Off by default. Enable in on-device settings.

Shared albums mix photos and video. Before this existed, clips displayed as **silent
frozen poster frames** — the app requested a sized still for every item, and for a video
that is what the URL returns.

**Detection uses a metadata key.** Nothing obvious marks an item as video: no MIME type,
no `"video"` string, and the `=dv` suffix returns an MP4 for stills too, because Google
synthesises video from Motion Photos. What does mark them is key `76647426` in the
entry's trailing metadata object, whose value is `[durationMs, null, width, height, …]` —
so clip length comes free with the detection.

*An earlier version got this wrong in an instructive way.* It used the media sub-array
length: videos have 10 elements, stills 12. That matched perfectly on the first album —
7 short entries, 7 videos — and was simply luck. The second album has 13 short entries of
which only 7 are video; the rest are ordinary 4:3 stills and a panorama, which would have
been downloaded as MP4 and played as clips. A short sub-array is necessary but not
sufficient.

Both rules are undocumented structure and will eventually break, so the golden tests pin
the counts, the aspect ratios, and the durations. One test exists specifically to assert
the old heuristic *would* over-detect, so nobody reintroduces it.

**Size.** Videos download via `=dv` at original quality — the size suffix that bounds
photos does not apply. One clip measured 38 MB against a 4.2 MB figure in the album
metadata, so expect the library to grow several times over. This is why it is opt-in.

**Playback.** Clips play in full, then advance; the interval timer is suspended so
nothing is cut off mid-action. Pausing pauses the video.

**Audio is gated on presence.** Sound plays only while someone is actually in view, so
the frame never talks to an empty house — and because an empty room sleeps the frame,
there is no separate night-time rule to get wrong. With presence detection off, clips
play silently and the settings screen says so rather than pretending sound is on.

---

## Presence detection

Off by default. Enable in on-device settings; the frame then wakes when someone is in
the room and sleeps after an empty room for N minutes (default 5).

**Two-stage detection.** A cheap luminance frame-difference check samples one frame a
second; only when that fires does ML Kit's bundled face detector run. Motion alone would
sleep on someone sitting still watching photos and would trip on pets and curtains; face
detection on every frame would run a neural net forever on a wall-mounted device.

**Nothing leaves the device.** No frame is written to disk or transmitted. The only
output is a timestamp.

### Precedence

| Condition | Result |
|---|---|
| Manual sleep/wake | Wins over everything, until the next scheduled boundary |
| Camera working, someone in view | Awake — **even during quiet hours** |
| Camera working, room empty | Asleep — even at midday |
| Camera unavailable | Falls back to the quiet-hours schedule |

So while presence is running the schedule does nothing. That is deliberate: the schedule
is the **fallback** for when the camera cannot answer — privacy shutter closed,
permission denied, detection switched off, hardware busy. Treating an unanswerable
camera as "nobody is here" would sleep the frame with nothing able to wake it.

Closing Portal's physical shutter is therefore a safe, hard off-switch: presence goes
`UNAVAILABLE` and the frame reverts to schedule behaviour.

### Why there is a foreground service

Android 9 disconnects the camera from any app whose process has no foreground activity
and no foreground service. When the frame sleeps, the panel powers off, the Activity is
stopped, the process goes to background — and the system revokes camera access.

The symptom is a very specific asymmetry: **the frame sleeps on absence correctly and
then can never wake**, because the only thing that could notice someone arriving is the
camera, and it died with the screen.

`PresenceService` exists solely to hold the process foreground. It does not own the
camera or the detector; process-level foreground state is what the restriction keys on.
The persistent notification is the price Android charges for that status — set to minimum
importance so it stays silent and collapsed in the shade rather than appearing on the
frame.

If the camera is lost anyway (another app taking it at higher priority), the detector
retries every 30 seconds rather than staying dead until the app restarts.

### Permission

`deploy-portal.sh` grants it, since a wall-mounted frame should not throw runtime
permission dialogs:

```bash
adb shell pm grant com.example.portalgallery android.permission.CAMERA
```

Without it, presence reports unavailable and the schedule takes over.

---

## Quiet hours

Off by default — a frame that unexpectedly goes dark reads as broken.

```bash
A=com.example.portalgallery/.ui.slideshow.SlideshowActivity

# Sleep midnight to 7am local (setting the window turns the schedule on)
adb shell am start -n $A -e sleep_start "00:00" -e sleep_end "07:00"

# Turn the schedule off / back on without losing the window
adb shell am start -n $A -e sleep off
adb shell am start -n $A -e sleep on

# Sleep or wake right now, until the next scheduled boundary
adb shell am start -n $A -e command sleep
adb shell am start -n $A -e command wake
```

Times are 24-hour `HH:mm` in the device's local zone, so a Portal set to Pacific sleeps
on Pacific and follows DST. Windows crossing midnight (`22:00` → `07:00`) work.

**Behaviour asleep:** the slideshow stops, the screen goes black immediately, brightness
drops to the floor, and `KEEP_SCREEN_ON` is released so the device's own timeout can
power the panel down. Syncing continues — the frame wakes with fresh photos.

### Portal requires one device-level change

Portal ships its own screensaver (`com.facebook.aloha…HomeDreamService`) with
`screensaver_enabled=1`. Releasing `KEEP_SCREEN_ON` would therefore start *that* dream
rather than powering the panel down — quiet hours would swap your photos for Portal's
ambient screen instead of going dark.

`deploy-portal.sh` disables it as part of deployment. Manually:

```bash
adb shell settings put secure screensaver_enabled 0   # panel can power down
adb shell settings put secure screensaver_enabled 1   # restore Portal's screensaver
# or: ./tools/deploy-portal.sh --restore-screensaver
```

The panel goes fully dark `screen_off_timeout` after sleep begins — 5 minutes on
Portal+ (`settings get system screen_off_timeout`). The screen is black from the moment
sleep starts; only the backlight lingers.

### How waking works

Sleeping is easy; waking is not, for two reasons that both bite:

1. Once the panel powers down the Activity is paused, so any timer tied to its lifecycle
   stops. An early version evaluated the schedule on a 60-second `Handler` tick and
   cancelled it in `onPause` — the frame slept correctly and then had nothing left
   running to wake it.
2. `FLAG_KEEP_SCREEN_ON` only *prevents* a screen from sleeping. Nothing in the window
   flags turns a dark panel back on.

So `enterSleep()` schedules an exact `AlarmManager` alarm at the wake boundary. The
receiver takes a `SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP`, which does power
the display on, then brings the frame forward. The alarm fires 5 seconds *past* the
boundary so clock skew cannot leave the schedule still reading "asleep" and send it
straight back down with the alarm already spent.

**Known gap:** alarms do not survive a reboot. If the Portal restarts during quiet hours
the frame stays dark until someone taps it. Reboots are rare; a `BOOT_COMPLETED`
receiver would close this, and works on API 28.

**Tapping a sleeping frame wakes it** until the next scheduled boundary, so quiet hours
cannot be permanently disabled by accident.

A manual `sleep`/`wake` lasts only until the next scheduled transition, then normal
behaviour resumes.

---

## Deploying

```bash
./tools/deploy-portal.sh                  # debug build (default)
RELEASE=1 ./tools/deploy-portal.sh        # signed, R8-shrunk build
./tools/deploy-portal.sh --facts-only     # capture device info, change nothing
./tools/verify.sh "<share link>"          # same flow against an emulator
```

### Debug vs release

| | Debug | Release |
|---|---|---|
| StrictMode | on (logs main-thread disk/network) | off |
| R8 shrink + obfuscate | off | on |
| Signing | debug keystore | `keystore.properties` |

Debug is the default. Prove a change works there first: R8 failures in this app are
runtime failures, not build failures — obfuscated Gson field names would invalidate the
photo index, and a renamed enum constant would reset the transition setting. Both are
covered by `app/proguard-rules.pro`, but neither would announce itself at build time.

### Signing

`keystore.properties` and `portalgallery-release.jks` are gitignored. If the file is
absent the build still configures; it just cannot produce an installable release APK.

**Back up the keystore outside the repo.** Android identifies an app by its signature —
lose the key and you cannot update an installed build, only uninstall and reinstall,
which wipes the downloaded photo library and every setting.

Switching between debug and release also changes the signature, so the first switch
needs an uninstall. `deploy-portal.sh` uninstalls anyway.

---

## Tools

| Script | Purpose |
|---|---|
| `tools/check_album_ordering.py` | Is the album public? How many photos, what order? |
| `tools/scrub_fixture.py` | Turn a captured page into a committable test fixture |
| `tools/verify.sh` | End-to-end emulator check |
| `tools/deploy-portal.sh` | Sideload to Portal + capture device facts |

**Never commit a raw album capture.** It contains the live share link, `?key=` tokens,
contributor names, and Google account ids. `.gitignore` blocks `album-fixture.html`;
use the scrubber to produce `app/src/test/resources/shared_album_fixture.html`.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Golden-file tests against a real (scrubbed) share page. They pin the photo count, the
orientation split, the size-suffix rule, and the fail-loudly-never-empty contract. When
Google changes the page structure **these are what tell you** — refresh the fixture with
the scrubber and the diff shows exactly what moved.

---

## Portal platform notes

Checked against Meta's own Portal skill
([meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools/tree/main/skills/portal)),
which documents constraints that are easy to get wrong and hard to diagnose.

| Constraint | Status here |
|---|---|
| `minSdk ≤ 28` | 26 ✓ |
| `targetSdk` > 29 | 34 — fine for porting, verified upstream to 36 |
| `MAIN + LAUNCHER` intent-filter | Present ✓ |
| PNG icon in `mipmap-xxxhdpi/` | Added — see `tools/make_icon.py` |
| `android:icon` on the launcher activity | Added ✓ |
| **No GMS** | See below |
| Top 64 dp system overlay | Slideshow is dark and full-bleed; settings needs review |
| Far-field mic unavailable to sideloaded apps | Why voice control was dropped |
| Raw `Camera2` frames available | Why presence detection is viable |

### The no-GMS problem, and ML Kit

Portal ships without Google Mobile Services (`pm list packages | grep -c gms` → 0).
Meta's guidance lists **ML Kit** among the libraries that do not work, and recommends
TFLite instead.

This matters because `com.google.mlkit:face-detection` — the *bundled model* artifact,
chosen here precisely to avoid GMS — declares hard dependencies on
`play-services-base`, `play-services-basement`, `play-services-tasks` and
`firebase-components`. Bundling the model does not bundle away the GMS shim.

So on Portal, face detection is expected to be unavailable and presence degrades to
**motion-only**. The detector probes it once, catches `Throwable` (a missing GMS class
arrives as `NoClassDefFoundError`, which is an *Error* — catching only `Exception` would
turn "feature unavailable" into "app dies the first time somebody moves"), and reports
`motion (face detection unavailable)`.

Motion-only means someone sitting perfectly still for the whole absence timeout reads as
absent. With the default 5 minutes that is rarely a problem in practice.

---

## Known limits

- **300 photos per album.** The share page returns a 300-item prefix with a continuation
  token. Reading further means reverse-engineering an internal RPC, which this project
  does not do. Keep a frame album under 300 and the limit never applies.
- **Ordering follows the album's sort order.** An album sorted newest-first puts new
  photos in the visible 300; sorted oldest-first, new photos land beyond it and never
  appear. Sorting is by *capture* date, so an old photo added today still sorts to its
  original date. Check with `check_album_ordering.py`.
- **Orientation filter.** The frame shows only photos matching its physical orientation.
  This can cut the visible set substantially (the reference album is 56% portrait). If
  no photo matches, the filter is abandoned rather than showing a black screen.
- **No in-app settings yet.** Album URL and interval are adb-only. The slideshow interval
  is fixed at 8s.
- **Nothing restarts the app** if the OS kills the process. `DreamService` or HOME
  takeover would fix it; both need testing on the actual Portal.
- **This depends on undocumented page structure** and will break when Google changes it.
  When that happens the frame keeps showing the last synced set — it degrades, it does
  not go blank.
