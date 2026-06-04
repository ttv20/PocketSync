#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${1:-}" in
  ""|"--only")
    if [ "${1:-}" = "--only" ] && [ "${2:-}" != "tsnet-nc" ]; then
      printf 'Only tsnet-nc has an F-Droid native source-build path right now; got %s.\n' "${2:-}" >&2
      exit 2
    fi
    "$project_dir/scripts/build-tsnet-helper.sh"
    ;;
  "--from-source")
    printf 'Full rsync/OpenSSH source builds are Phase 3 work and are not implemented yet.\n' >&2
    exit 2
    ;;
  *)
    printf 'usage: %s [--only tsnet-nc|--from-source]\n' "$0" >&2
    exit 2
    ;;
esac
