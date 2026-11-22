#!/usr/bin/env bash
# Random-tap crash test: installs the app, opens it, and lets Android's "monkey" tap, swipe and
# press keys inside it. Any crash or "app not responding" is printed and fails the run.
# Usage: tools/ci/monkey.sh EVENTS
set -u
EVENTS="${1:-0}"
[ "$EVENTS" -gt 0 ] 2>/dev/null || exit 0
PKG=com.reelforge.editor
./gradlew installDebug -q || exit 1
for p in android.permission.POST_NOTIFICATIONS android.permission.READ_MEDIA_IMAGES \
         android.permission.READ_MEDIA_VIDEO android.permission.READ_MEDIA_AUDIO android.permission.RECORD_AUDIO; do
  adb shell pm grant $PKG $p 2>/dev/null || true
done
adb logcat -c
fails=0
# Several short runs with different seeds: one crash does not hide the rest.
for seed in 11 23 37 41 59; do
  echo "=== monkey seed $seed ==="
  adb shell monkey -p $PKG -s $seed --throttle 120 --pct-syskeys 0 --pct-appswitch 2 \
    --pct-anyevent 0 --ignore-security-exceptions -v $((EVENTS / 5)) > monkey_$seed.txt 2>&1
  if grep -qE "// CRASH|// NOT RESPONDING|ANR in" monkey_$seed.txt; then
    fails=$((fails + 1))
    echo "!!! problem with seed $seed"
    grep -A40 -E "// CRASH|// NOT RESPONDING" monkey_$seed.txt | head -80
  else
    tail -2 monkey_$seed.txt
  fi
  adb shell am force-stop $PKG
done
echo "=== crashes in logcat ==="
adb logcat -d -s AndroidRuntime:E | grep -A30 "FATAL EXCEPTION" | head -150
[ "$fails" -eq 0 ]
