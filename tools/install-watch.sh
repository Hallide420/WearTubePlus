#!/bin/bash
# Build an APK and install it on the watch over adb.
#
# Usage:
#   tools/install-watch.sh                     # debug, one already-connected device
#   tools/install-watch.sh <ip:port>           # debug, adb connect first
#   tools/install-watch.sh --release [ip:port] # signed R8 release
#
# The watch must have Settings -> Developer options -> Wireless debugging on.
# That screen shows the IP:port; it changes whenever the watch sleeps.
#
# Release builds need the signing credentials. They are read from
# $WTP_SIGNING_ENV (default ~/.config/wtp/signing.env) and never committed.
set -e
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

RELEASE=0
ADDR=""
for arg in "$@"; do
  case "$arg" in
    --release|-r) RELEASE=1 ;;
    -*) echo "Unknown option: $arg" >&2; exit 2 ;;
    *) ADDR="$arg" ;;
  esac
done

PKG=com.hallide.weartubeplus

if [ "$RELEASE" = "1" ]; then
  APK=app/build/outputs/apk/release/app-release.apk
  SIGNING_ENV="${WTP_SIGNING_ENV:-$HOME/.config/wtp/signing.env}"
  if [ ! -f "$SIGNING_ENV" ]; then
    echo "Missing signing credentials: $SIGNING_ENV" >&2
    echo "Set WTP_SIGNING_ENV or create the file with WEARTUBE_* exports." >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  . "$SIGNING_ENV"
  echo "Building signed release APK…"
  ./gradlew :app:assembleRelease
else
  APK=app/build/outputs/apk/debug/app-debug.apk
  echo "Building debug APK…"
  ./gradlew :app:assembleDebug
fi

if [ -n "$ADDR" ]; then
  adb connect "$ADDR"
fi

mapfile -t DEVICES < <(adb devices | awk '$2=="device"{print $1}')
case ${#DEVICES[@]} in
  0)
    echo "No device connected."
    echo "Enable Wireless debugging on the watch, then: tools/install-watch.sh $([ "$RELEASE" = 1 ] && echo '--release ') <ip:port>"
    exit 1
    ;;
  1)
    SERIAL=${DEVICES[0]}
    ;;
  *)
    echo "More than one device. Pass the watch's ip:port, or set ANDROID_SERIAL."
    printf '  %s\n' "${DEVICES[@]}"
    exit 1
    ;;
esac

echo "Installing $APK on $SERIAL…"
if ! adb -s "$SERIAL" install -r "$APK" 2>&1 | tee /tmp/wtp-install.log | grep -q "Success"; then
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/wtp-install.log; then
    cat /tmp/wtp-install.log >&2
    echo "" >&2
    echo "This device has $PKG signed with a different key." >&2
    echo "Uninstall it first — this deletes settings and downloads:" >&2
    echo "  adb -s $SERIAL uninstall $PKG" >&2
  fi
  exit 1
fi

adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Launched $PKG on $SERIAL"
