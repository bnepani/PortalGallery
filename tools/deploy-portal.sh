#!/usr/bin/env bash
# Sideload PortalGallery to a Meta Portal and capture the device facts we still need.
#
#   ./tools/deploy-portal.sh                     # full deploy + quiet hours 00:00-07:00
#   SLEEP_START=22:30 SLEEP_END=06:30 ./tools/deploy-portal.sh
#   RELEASE=1 ./tools/deploy-portal.sh           # signed, R8-shrunk build
#   ./tools/deploy-portal.sh --facts-only        # capture device info, change nothing
#   ./tools/deploy-portal.sh --restore-screensaver
#
# Requires the Portal in developer mode with adb authorised (USB, or `adb connect <ip>:5555`).

set -o pipefail
cd "$(dirname "$0")/.." || exit 1

SLEEP_START="${SLEEP_START:-00:00}"
SLEEP_END="${SLEEP_END:-07:00}"
PKG=com.example.portalgallery
ACT="$PKG/.ui.slideshow.SlideshowActivity"
FACTS="docs/portal-device-facts.txt"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    ✓ %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m    ! %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31m    ✗ %s\033[0m\n' "$1"; exit 1; }
q()    { adb shell "$@" 2>/dev/null | tr -d '\r'; }

step "1. Device"
[ -n "$(adb devices | sed -n '2p')" ] || die "no device. USB + developer mode, or: adb connect <portal-ip>:5555"
ok "$(q getprop ro.product.model) — $(adb devices | sed -n '2p' | awk '{print $1}')"

# Undo the one device-level change this script makes.
if [ "${1:-}" = "--restore-screensaver" ]; then
  adb shell settings put secure screensaver_enabled 1
  ok "screensaver re-enabled (now: $(q settings get secure screensaver_enabled))"
  exit 0
fi

step "2. Preflight — the questions the emulator could not answer"
SDK=$(q getprop ro.build.version.sdk)
{
  echo "captured: $(date)"
  echo "model            : $(q getprop ro.product.model)"
  echo "device           : $(q getprop ro.product.device)"
  echo "android release  : $(q getprop ro.build.version.release)"
  echo "api level        : $SDK"
  echo "heap growth limit: $(q getprop dalvik.vm.heapgrowthlimit)"
  echo "heap size        : $(q getprop dalvik.vm.heapsize)"
  echo "natural rotation : $(q dumpsys display | grep -iE 'mCurrentOrientation|rotation' | head -3)"
  echo "screensaver comp : $(q settings get secure screensaver_components)"
  echo "screensaver on   : $(q settings get secure screensaver_enabled)"
  echo "stay on plugged  : $(q settings get global stay_on_while_plugged_in)"
  echo "screen off tmout : $(q settings get system screen_off_timeout)"
  echo "current home     : $(q cmd package resolve-activity -c android.intent.category.HOME | grep -i name | head -2)"
  echo "dreams service   : $(q dumpsys dreams | head -5)"
  echo "free space /data : $(q df /data | tail -1)"
  echo
  echo "--- voice capability ---"
  echo "recognition svc  : $(q cmd package query-services --brief android.speech.RecognitionService | tr '\n' ' ')"
  echo "voice interaction: $(q dumpsys package | grep -i voiceinteraction | head -3 | tr '\n' ' ')"
  echo "assistant pkgs   : $(q pm list packages | grep -iE 'assistant|speech|voice|alexa|recognition' | tr '\n' ' ')"
  echo "mic feature      : $(q pm list features | grep -i microphone | tr '\n' ' ')"
  echo "current assistant: $(q settings get secure assistant)"
  echo "voice recognizer : $(q settings get secure voice_recognition_service)"
  echo
  echo "--- camera capability (presence detection) ---"
  echo "camera features  : $(q pm list features | grep -i camera | tr '\n' ' ')"
  echo "camera devices   : $(q dumpsys media.camera | grep -iE 'Camera [0-9]+ information|Device [0-9]+|Number of camera devices' | head -6 | tr '\n' ' ')"
  echo "camera service   : $(q dumpsys media.camera | head -3 | tr '\n' ' ')"
  echo "camera perm held : $(q dumpsys package com.example.portalgallery | grep -i camera | tr '\n' ' ')"
  echo "privacy shutter  : $(q dumpsys sensorprivacy 2>/dev/null | head -3 | tr '\n' ' ')"
  echo "gms present      : $(q pm list packages | grep -cE 'com.google.android.gms')"
} | tee "$FACTS"
ok "saved to $FACTS"

[ "${1:-}" = "--facts-only" ] && exit 0

# minSdk is 26. Portal generations shipped on API 25, 28 and 29 depending on model.
if [ -n "$SDK" ] && [ "$SDK" -lt 26 ] 2>/dev/null; then
  die "API $SDK is below minSdk 26 — this APK cannot install. Lower minSdk and rebuild."
fi
ok "API $SDK meets minSdk 26"

step "3. Remove the old build"
# Uninstall rather than update: the previous version stored OAuth tokens in
# SharedPreferences, and this clears them along with any stale album config.
if q pm list packages | grep -q "$PKG"; then
  adb uninstall "$PKG" >/dev/null 2>&1 && ok "uninstalled previous version" || warn "uninstall failed — continuing"
else
  ok "not currently installed"
fi

step "4. Build and install"
# Debug by default. Release adds R8 shrinking and needs keystore.properties; try it
# only once a debug build is confirmed working on the device, since R8 problems
# (obfuscated Gson fields, renamed enum constants) show up at runtime, not build time.
if [ "${RELEASE:-}" = "1" ]; then
  [ -f keystore.properties ] || die "RELEASE=1 needs keystore.properties (see README)"
  ./gradlew installRelease --console=plain -q || die "release build/install failed"
  ok "installed (release, R8 enabled)"
else
  ./gradlew installDebug --console=plain -q || die "build/install failed"
  ok "installed (debug — StrictMode active; RELEASE=1 for a shrunk, signed build)"
fi

step "5. Let the panel actually power down at night"
# Portal ships its own dream (com.facebook.aloha...HomeDreamService) with
# screensaver_enabled=1. When the app releases KEEP_SCREEN_ON at sleep time, the
# device would start that dream instead of powering the panel down — so quiet hours
# would swap the frame for Portal's ambient screen rather than going dark.
#
# Undo with: ./tools/deploy-portal.sh --restore-screensaver
PREV_SS=$(q settings get secure screensaver_enabled)
if [ "$PREV_SS" = "1" ]; then
  adb shell settings put secure screensaver_enabled 0
  ok "system screensaver disabled (was $PREV_SS) — restore with --restore-screensaver"
else
  ok "system screensaver already off"
fi
ok "screen_off_timeout is $(q settings get system screen_off_timeout) ms — the panel darkens that long after sleep begins"

step "5b. Camera permission (presence detection)"
# A wall-mounted frame should never throw a runtime permission dialog, so grant it
# here. Harmless if the device has no camera — presence simply reports UNAVAILABLE and
# the frame falls back to its quiet-hours schedule.
if adb shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1; then
  ok "CAMERA granted (presence detection is still OFF until enabled in settings)"
else
  warn "could not grant CAMERA — presence detection will fall back to the schedule"
fi

step "6. Launch"
adb logcat -c
adb shell am start -n "$ACT" >/dev/null || die "launch failed"
ok "launched — the frame should show 'Syncing photos…'"

step "7. Quiet hours"
adb shell am start -n "$ACT" -e sleep_start "$SLEEP_START" -e sleep_end "$SLEEP_END" >/dev/null
ok "asleep $SLEEP_START–$SLEEP_END local"

step "8. Following the first sync (Ctrl-C to stop)"
echo "    ~300 photos at 4-way concurrency; a few minutes on first run."
echo
echo "    To test quiet hours without waiting for midnight:"
echo "      adb shell am start -n $ACT -e command sleep"
echo "      adb shell am start -n $ACT -e command wake"
echo
adb logcat -s PortalGallery:V AndroidRuntime:E
