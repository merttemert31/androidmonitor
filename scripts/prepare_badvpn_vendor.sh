#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSIONS_FILE="$ROOT_DIR/third_party/VERSIONS.env"
BADVPN_DIR="$ROOT_DIR/third_party/badvpn"
ANCILLARY_DIR="$ROOT_DIR/third_party/libancillary"
STAGE_DIR="$ROOT_DIR/native-build/tun2socks"

if [[ ! -f "$VERSIONS_FILE" ]]; then
  echo "Missing $VERSIONS_FILE"
  exit 1
fi

# shellcheck disable=SC1090
source "$VERSIONS_FILE"

assert_repo_dir() {
  local path="$1"
  if [[ ! -d "$path" ]]; then
    echo "Missing $path"
    exit 1
  fi
}

assert_commit_match() {
  local path="$1"
  local expected="$2"
  local label="$3"

  if [[ "$expected" == REPLACE_WITH_* ]]; then
    echo "$label commit pin is not set in $VERSIONS_FILE"
    exit 1
  fi

  if [[ -d "$path/.git" ]]; then
    local current
    current="$(git -C "$path" rev-parse HEAD)"
    if [[ "$current" != "$expected"* ]]; then
      echo "$label commit mismatch"
      echo "  expected: $expected"
      echo "  current : $current"
      echo "Run scripts/vendor_badvpn_pinned.sh first."
      exit 1
    fi
  fi
}

assert_repo_dir "$BADVPN_DIR"
assert_repo_dir "$ANCILLARY_DIR"
assert_commit_match "$BADVPN_DIR" "$BADVPN_COMMIT" badvpn
assert_commit_match "$ANCILLARY_DIR" "$LIBANCILLARY_COMMIT" libancillary

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

cp "$ROOT_DIR/scripts/ndk/Android.mk" "$STAGE_DIR/Android.mk"
cp "$ROOT_DIR/scripts/ndk/Application.mk" "$STAGE_DIR/Application.mk"
cp "$ROOT_DIR/scripts/ndk/build-shared-executable.mk" "$STAGE_DIR/build-shared-executable.mk"
ln -s "$BADVPN_DIR" "$STAGE_DIR/badvpn"
ln -s "$ANCILLARY_DIR" "$STAGE_DIR/libancillary"

echo "Prepared NDK stage at: $STAGE_DIR"
ls -la "$STAGE_DIR"
