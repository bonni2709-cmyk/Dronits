#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/native/versions.env"
: "${ANDROID_HOME:?Android SDK must be supplied by GitHub Actions}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$ROOT/app/build/generated/native/jniLibs"
"$ROOT/native/fetch-hev.sh"
"$NDK/ndk-build" NDK_PROJECT_PATH="$ROOT/.work/hev" \
  APP_BUILD_SCRIPT="$ROOT/.work/hev/Android.mk" NDK_APPLICATION_MK="$ROOT/native/hev/Application.mk" \
  NDK_OUT="$ROOT/.work/ndk/obj" NDK_LIBS_OUT="$OUT" -j2
for abi in arm64-v8a armeabi-v7a x86_64; do
  unset GOARM
  case "$abi" in
    arm64-v8a) export GOARCH=arm64; compiler=aarch64-linux-android ;;
    armeabi-v7a) export GOARCH=arm GOARM=7; compiler=armv7a-linux-androideabi ;;
    x86_64) export GOARCH=amd64; compiler=x86_64-linux-android ;;
  esac
  mkdir -p "$OUT/$abi"
  (
    cd "$ROOT/native/xray"
    export GOOS=android CGO_ENABLED=1 CC="$TOOLCHAIN/${compiler}${ANDROID_API}-clang"
    go build -mod=readonly -p 2 -trimpath -buildvcs=false -buildmode=c-shared \
      -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' \
      -o "$OUT/$abi/libxray.so" .
  )
  rm -f "$OUT/$abi/libxray.h"
  for library in "$OUT/$abi/"*.so; do
    "$TOOLCHAIN/llvm-readelf" --program-headers --wide "$library" | python3 -c '
import sys
loads = [line.split() for line in sys.stdin if line.lstrip().startswith("LOAD")]
assert loads and all(int(line[-1], 16) >= 16384 for line in loads), "ELF must support 16 KiB pages"
'
  done
done
python3 "$ROOT/native/collect-licenses.py"
