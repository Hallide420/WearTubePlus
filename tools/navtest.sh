#!/bin/bash
# Navigate every top-level route on the watch and report crash/alive per route.
# Run: WATCH_SERIAL=<serial> tools/navtest.sh
source "$(dirname "$0")/wt.sh" true
PKG=com.hallide.weartubeplus
S="${WT_SCRATCH:-/tmp/opencode/wtp-shots}"
mkdir -p "$S"
run() { # name route waitsec
  adb -s "$W" logcat -c
  adb -s "$W" shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb -s "$W" shell am force-stop $PKG
  adb -s "$W" shell am start -n $PKG/.MainActivity --es open_tab "$2" >/dev/null
  sleep "${3:-8}"
  local crash; crash=$(adb -s "$W" logcat -d | grep -cE "FATAL EXCEPTION|AndroidRuntime: .*Exception")
  local pid; pid=$(adb -s "$W" shell pidof $PKG | tr -d '\r')
  adb -s "$W" exec-out screencap -p > "$S/nav_$1.png"
  printf "%-24s crash=%s alive=%s\n" "$1" "$crash" "$([ -n "$pid" ] && echo yes || echo NO)"
}
