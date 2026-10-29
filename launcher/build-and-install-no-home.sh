#!/usr/bin/env bash
# Build the release APK and install/update it without changing the Home role or stock launcher state.
# Use this on TVs where set-home-activity reports success but the physical Home button still opens stock Home.
# Set RESTORE_KEY_SERVICES=1 to grant WRITE_SECURE_SETTINGS for restore_key_services.
# Set LAUNCH_HOME_AFTER_BOOT=1 to also grant WRITE_SECURE_SETTINGS and persist TCL's AUTO_START app-op.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
TOOLS="$(cd ../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "ERROR: run python -m venv tools/venv and install tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1

if [ -z "${JAVA_HOME:-}" ]; then
  JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JH" ] && ! "$JH/bin/java" -version 2>&1 | grep -q 'version "17\.'; then JH=""; fi
  if [ -z "$JH" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then JH=/opt/homebrew/opt/openjdk@17; fi
  if [ -z "$JH" ]; then JH="$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')"; fi
  JAVA_HOME="$JH"
fi
export JAVA_HOME
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -d "$ANDROID_HOME" ] || { echo "ERROR: Android SDK not found; set ANDROID_HOME" >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || printf 'sdk.dir=%s\n' "${ANDROID_HOME//\\//}" > local.properties

./gradlew assembleRelease --no-daemon -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: release APK not found: $APK" >&2; exit 1; }
REMOTE=/data/local/tmp/tvhome-no-home.apk
"$PY" -I "$TOOLS/b64push.py" "$APK" "$REMOTE"
OUT=$("$PY" -I "$TOOLS/tv.py" "pm install -r $REMOTE; rm -f $REMOTE") || {
  echo "ERROR: APK installation failed; Home and the stock launcher were not changed." >&2
  exit 1
}
printf '%s\n' "$OUT"
case "$OUT" in
  *Success*) echo "Installed/updated com.gathin.tvhome. Home selection, stock-launcher state, and accessibility services were left untouched." ;;
  *) echo "ERROR: package manager did not confirm installation; Home and the stock launcher were not changed." >&2; exit 1 ;;
esac
if [ "${RESTORE_KEY_SERVICES:-0}" = 1 ] || [ "${LAUNCH_HOME_AFTER_BOOT:-0}" = 1 ]; then
  OUT=$("$PY" -I "$TOOLS/tv.py" 'pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS && echo restore-granted') || {
    echo "ERROR: launcher installed, but WRITE_SECURE_SETTINGS was not granted." >&2
    exit 1
  }
  printf '%s\n' "$OUT"
  case "$OUT" in
    *restore-granted*) echo "Granted WRITE_SECURE_SETTINGS for the explicitly requested restore_key_services option." ;;
    *) echo "ERROR: launcher installed, but WRITE_SECURE_SETTINGS was not granted." >&2; exit 1 ;;
  esac
fi
if [ "${LAUNCH_HOME_AFTER_BOOT:-0}" = 1 ]; then
  OUT=$("$PY" -I "$TOOLS/tv.py" 'cmd appops set com.gathin.tvhome AUTO_START allow && cmd appops write-settings && cmd appops get com.gathin.tvhome AUTO_START') || {
    echo "ERROR: launcher installed, but TCL AUTO_START was not granted." >&2
    exit 1
  }
  printf '%s\n' "$OUT"
  case "$OUT" in
    *"AUTO_START: allow"*) echo "Granted WRITE_SECURE_SETTINGS and saved TCL AUTO_START for launch_home_after_boot." ;;
    *) echo "ERROR: launcher installed, but TCL AUTO_START was not granted." >&2; exit 1 ;;
  esac
fi
