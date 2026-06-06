#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STAGE_DIR="$ROOT_DIR/native-build/tun2socks"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set"
  exit 1
fi

"$ROOT_DIR/scripts/prepare_badvpn_vendor.sh"

NDK_BUILD="$ANDROID_NDK_HOME/ndk-build"
if [[ ! -x "$NDK_BUILD" ]]; then
  echo "ndk-build not found at $NDK_BUILD"
  exit 1
fi

pushd "$STAGE_DIR" >/dev/null
"$NDK_BUILD" \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK=Application.mk \
  APP_ABI="arm64-v8a armeabi-v7a x86_64"
popd >/dev/null

for ABI in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "$OUT_DIR/$ABI"
  if [[ -f "$STAGE_DIR/libs/$ABI/libtun2socks.so" ]]; then
    cp "$STAGE_DIR/libs/$ABI/libtun2socks.so" "$OUT_DIR/$ABI/libtun2socks.so"
  else
    echo "Missing build artifact for $ABI"
    exit 1
  fi
done

echo "Build complete. Artifacts copied to $OUT_DIR"
