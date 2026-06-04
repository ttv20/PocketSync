#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="$project_dir/native/fdroid-native-sources.lock"

read_lock_value() {
  local key="$1"
  awk -v key="$key" '$1 == key { for (i = 2; i <= NF; i++) printf "%s%s", $i, (i == NF ? ORS : OFS) }' "$lock_file"
}

termux_repo_line="$(read_lock_value termux-packages)"
termux_repo_url="$(printf '%s\n' "$termux_repo_line" | awk '{ print $2 }')"
termux_commit="$(printf '%s\n' "$termux_repo_line" | awk '{ print $3 }')"
builder_image="$(read_lock_value termux-builder-image)"
termux_arch="$(read_lock_value termux-arch)"
packages="$(awk '$1 == "termux-package" { print $2 }' "$lock_file" | paste -sd, -)"
tsnet_ref="$(read_lock_value tsnet-helper)"

printf 'termux-packages=%s@%s;termux-builder-image=%s;termux-arch=%s;termux-packages-root=%s;tsnet-helper=%s\n' \
  "$termux_repo_url" \
  "$termux_commit" \
  "$builder_image" \
  "$termux_arch" \
  "$packages" \
  "$tsnet_ref"
