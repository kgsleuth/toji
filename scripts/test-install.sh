#!/usr/bin/env bash
# Smoke-test install.sh without downloading (platform detection + syntax).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash -n "${root}/install.sh"

# shellcheck source=install.sh
source "${root}/install.sh"

asset="$(detect_asset)"
case "$asset" in
  toji-linux-x86_64 | toji-linux-aarch64 | toji-macos-x86_64 | toji-macos-aarch64)
    printf 'ok: detect_asset -> %s\n' "$asset"
    ;;
  *)
    printf 'fail: unexpected asset %s\n' "$asset" >&2
    exit 1
    ;;
esac

version="$(normalize_version latest)"
[[ "$version" == "latest" ]]

version="$(normalize_version 0.1.0)"
[[ "$version" == "v0.1.0" ]]

path="$(release_api_path v0.1.0)"
[[ "$path" == "/repos/kgsleuth/toji/releases/tags/v0.1.0" ]]

printf 'install.sh tests passed\n'
