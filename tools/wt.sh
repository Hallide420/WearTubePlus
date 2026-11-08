#!/bin/bash
# WearTubePlus on-watch test helper.
#
# HARD RULE: only ever talk to the watch. A phone on the same LAN can also
# advertise adb, so every candidate is verified by ro.serialno before use.
# Set WATCH_SERIAL to the value of `adb shell getprop ro.serialno` on the watch.
PKG=com.hallide.weartubeplus
WATCH_SERIAL="${WATCH_SERIAL:-}"
SCRATCH="${WT_SCRATCH:-/tmp/opencode/wtp-shots}"
mkdir -p "$SCRATCH"

# true only if $1 is genuinely the watch
wt_is_watch() {
  [ -n "$1" ] || return 1
  [ -n "$WATCH_SERIAL" ] || return 1
  local s
  s=$(adb -s "$1" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
  [ "$s" = "$WATCH_SERIAL" ]
}

wt_find() {
  local cand
  # 1. anything already connected that IS the watch
  for cand in $(adb devices | awk '$2=="device"{print $1}'); do
    wt_is_watch "$cand" && { echo "$cand"; return 0; }
  done
  # 2. mdns entries — connect, then verify serial before accepting
  for cand in $(adb mdns services 2>/dev/null | grep -oE '(adb-[A-Za-z0-9_.-]+|19[0-9.]+:[0-9]+)' | sort -u); do
    adb connect "$cand" >/dev/null 2>&1
    sleep 1
    wt_is_watch "$cand" && { echo "$cand"; return 0; }
  done
  return 1
}

if [ -z "$WATCH_SERIAL" ]; then
  echo "Set WATCH_SERIAL to your watch's ro.serialno (adb shell getprop ro.serialno)."
  exit 2
fi

W=$(wt_find) || { echo "WATCH UNREACHABLE (serial $WATCH_SERIAL not found)"; exit 3; }
export W
wake()  { adb -s "$W" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; }
tap()   { wake; adb -s "$W" shell input tap "$1" "$2"; }
swipe() { adb -s "$W" shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
back()  { adb -s "$W" shell input keyevent KEYCODE_BACK; }
shot()  { adb -s "$W" exec-out screencap -p > "$SCRATCH/$1"; }
logc()  { adb -s "$W" logcat -c; }
logs()  { adb -s "$W" logcat -d | grep -E "WTStream|WTAuth|AndroidRuntime|FATAL|Playback error"; }
launch(){ wake; adb -s "$W" shell am force-stop $PKG; adb -s "$W" shell am start -n $PKG/.MainActivity "$@" >/dev/null; }
focus() { adb -s "$W" shell "dumpsys window | grep mCurrentFocus" | sed 's/.*mCurrentFocus=//'; }
alive() { adb -s "$W" shell pidof $PKG >/dev/null 2>&1 && echo ALIVE || echo DEAD; }
# install, but only ever to the verified watch
wtinstall() { adb -s "$W" install -r "$1" 2>&1 | tail -1; }
"$@"
