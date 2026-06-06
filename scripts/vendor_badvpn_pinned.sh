#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSIONS_FILE="$ROOT_DIR/third_party/VERSIONS.env"
BADVPN_DIR="$ROOT_DIR/third_party/badvpn"
ANCILLARY_DIR="$ROOT_DIR/third_party/libancillary"

if [[ ! -f "$VERSIONS_FILE" ]]; then
  echo "Missing $VERSIONS_FILE"
  exit 1
fi

# shellcheck disable=SC1090
source "$VERSIONS_FILE"

require_pinned_commit() {
  local name="$1"
  local value="$2"
  if [[ "$value" == REPLACE_WITH_* ]] || [[ ! "$value" =~ ^[0-9a-fA-F]{7,40}$ ]]; then
    echo "$name must be a pinned commit hash in $VERSIONS_FILE"
    exit 1
  fi
}

sync_repo() {
  local repo_url="$1"
  local commit="$2"
  local dest="$3"

  if [[ ! -d "$dest/.git" ]]; then
    rm -rf "$dest"
    git clone "$repo_url" "$dest"
  fi

  git -C "$dest" fetch --all --tags --prune
  git -C "$dest" checkout --detach "$commit"

  local current
  current="$(git -C "$dest" rev-parse HEAD)"
  if [[ "$current" != "$commit"* ]]; then
    echo "Failed to pin $dest to $commit"
    exit 1
  fi
}

require_pinned_commit BADVPN_COMMIT "$BADVPN_COMMIT"
require_pinned_commit LIBANCILLARY_COMMIT "$LIBANCILLARY_COMMIT"

sync_repo "$BADVPN_REPO" "$BADVPN_COMMIT" "$BADVPN_DIR"
sync_repo "$LIBANCILLARY_REPO" "$LIBANCILLARY_COMMIT" "$ANCILLARY_DIR"

echo "Pinned vendor sources ready:"
echo "  badvpn       $(git -C "$BADVPN_DIR" rev-parse HEAD)"
echo "  libancillary $(git -C "$ANCILLARY_DIR" rev-parse HEAD)"
