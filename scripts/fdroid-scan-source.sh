#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-}"
problems=()

scan_dirs=(
  "$project_dir/app/src/main/assets"
  "$project_dir/app/src/main/jniLibs"
  "$project_dir/app/src/fdroidRelease/assets"
  "$project_dir/app/src/fdroidRelease/jniLibs"
)

while IFS= read -r path; do
  rel="${path#$project_dir/}"
  base="$(basename "$path")"

  case "$base" in
    rsync|ssh|scp|sftp|ssh-keygen|ssh-keyscan|tsnet-nc)
      problems+=("$rel: native executable name is not allowed in F-Droid source sets")
      continue
      ;;
  esac

  case "$base" in
    *.a|*.so|*.so.*)
      problems+=("$rel: native library name is not allowed in F-Droid source sets")
      continue
      ;;
  esac

  if command -v file >/dev/null 2>&1 && file -b "$path" | grep -q 'ELF'; then
    problems+=("$rel: ELF file is not allowed in F-Droid source sets")
  fi
done < <(
  for dir in "${scan_dirs[@]}"; do
    if [ -d "$dir" ]; then
      find "$dir" -type f
    fi
  done | sort
)

if [ "${#problems[@]}" -gt 0 ]; then
  printf 'F-Droid source scan failed:\n' >&2
  printf '  %s\n' "${problems[@]}" >&2
  exit 1
fi

if [ "$mode" != "--gradle" ]; then
  printf 'F-Droid source scan passed.\n'
fi
