#!/usr/bin/env bash
# End-to-end verification for PortalGallery on an emulator or device.
#
#   ./tools/verify.sh "https://photos.app.goo.gl/YOURLINK"
#
# Runs: unit tests -> build -> install -> launch -> follow the sync.
# Stops at the first failure so you see the real error, not a cascade.

set -o pipefail
cd "$(dirname "$0")/.." || exit 1

ALBUM_URL="${1:-}"
PKG=com.example.portalgallery
ACT="$PKG/.ui.slideshow.SlideshowActivity"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
ok()   { printf '\033[1;32m    ✓ %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31m    ✗ %s\033[0m\n' "$1"; exit 1; }

step "0. Preflight"
command -v adb >/dev/null || die "adb not on PATH"
[ -n "$(adb devices | sed -n '2p')" ] || die "no device — start the emulator first"
ok "device: $(adb devices | sed -n '2p' | awk '{print $1}')"
[ -n "$ALBUM_URL" ] || die "pass a share link as the first argument"

step "1. Parser unit tests (no device needed)"
./gradlew :app:testDebugUnitTest --console=plain -q \
  || die "tests failed — see app/build/reports/tests/testDebugUnitTest/index.html"
ok "parser tests passed"

step "2. Build and install"
./gradlew installDebug --console=plain -q || die "build failed"
ok "installed"

step "3. Reset app state (fresh first-run)"
adb shell pm clear "$PKG" >/dev/null 2>&1
adb logcat -c
ok "cleared app data and logcat"

step "4. Launch with album URL"
adb shell am start -n "$ACT" -e album_url "$ALBUM_URL" >/dev/null || die "launch failed"
ok "launched — watch the emulator screen"

step "5. Following sync (Ctrl-C when the slideshow starts)"
echo "    Expect: 'sync: N in album, N to download' then 'sync ok: ...'"
echo "    First sync pulls ~300 photos at 4-way concurrency — a couple of minutes."
echo
adb logcat -s PortalGallery:V AndroidRuntime:E
