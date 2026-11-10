#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/native/versions.env"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  sdkmanager=$(find "$ANDROID_HOME/cmdline-tools" -type f -name sdkmanager 2>/dev/null | head -1 || true)
fi
if [[ -z "$sdkmanager" || ! -x "$sdkmanager" ]]; then
  echo "Android SDK command-line tools not found under $ANDROID_HOME" >&2
  exit 1
fi
# Current command-line tools may require a newer JDK; Gradle still uses the pinned JDK 17.
export JAVA_HOME="${JAVA_HOME_21_X64:-$JAVA_HOME}"
"$sdkmanager" --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;$NDK_VERSION"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$ANDROID_HOME" "$ANDROID_HOME" >> "$GITHUB_ENV"
printf '%s\n' "$(dirname "$sdkmanager")" "$ANDROID_HOME/platform-tools" >> "$GITHUB_PATH"
