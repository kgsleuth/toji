#!/usr/bin/env bash
# toji installer — detects OS/arch, downloads the matching release binary, installs to PATH.
#
# One-liner (latest release):
#   curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | bash
#
# Pin a version:
#   curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | TOJI_VERSION=v0.1.0 bash
#
# Environment:
#   TOJI_REPO             GitHub repo (default: kgsleuth/toji)
#   TOJI_VERSION          Release tag or "latest" (default: latest)
#   TOJI_INSTALL_DIR      Install directory (default: ~/.local/bin)
#   TOJI_GITHUB_TOKEN     PAT (or use GITHUB_TOKEN / GH_TOKEN). Preferred: gh auth login

set -euo pipefail

TOJI_REPO="${TOJI_REPO:-kgsleuth/toji}"
TOJI_VERSION="${TOJI_VERSION:-latest}"
TOJI_INSTALL_DIR="${TOJI_INSTALL_DIR:-${HOME}/.local/bin}"

info() { printf 'toji install: %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

curl_auth_args() {
  if [[ -n "${TOJI_GITHUB_TOKEN:-}" ]]; then
    printf '%s\n' "-H" "Authorization: Bearer ${TOJI_GITHUB_TOKEN}"
  elif [[ -n "${GITHUB_TOKEN:-}" ]]; then
    printf '%s\n' "-H" "Authorization: Bearer ${GITHUB_TOKEN}"
  elif [[ -n "${GH_TOKEN:-}" ]]; then
    printf '%s\n' "-H" "Authorization: Bearer ${GH_TOKEN}"
  fi
}

detect_asset() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"

  case "${os}-${arch}" in
    Linux-x86_64 | Linux-amd64)
      printf '%s\n' "toji-linux-x86_64"
      ;;
    Linux-aarch64 | Linux-arm64)
      printf '%s\n' "toji-linux-aarch64"
      ;;
    Darwin-x86_64)
      printf '%s\n' "toji-macos-x86_64"
      ;;
    Darwin-arm64 | Darwin-aarch64)
      printf '%s\n' "toji-macos-aarch64"
      ;;
    *)
      die "unsupported platform: ${os} ${arch}

Supported:
  Linux x86_64 / amd64
  Linux aarch64 / arm64
  macOS Intel (x86_64)
  macOS Apple Silicon (arm64)

Build from source: https://github.com/${TOJI_REPO}#install"
      ;;
  esac
}

normalize_version() {
  local version="$1"
  case "$version" in
    latest) printf '%s\n' "latest" ;;
    v*)     printf '%s\n' "$version" ;;
    *)      printf '%s\n' "v${version}" ;;
  esac
}

release_api_path() {
  local version="$1"
  if [[ "$version" == "latest" ]]; then
    printf '/repos/%s/releases/latest' "$TOJI_REPO"
  else
    printf '/repos/%s/releases/tags/%s' "$TOJI_REPO" "$version"
  fi
}

fetch_release_json() {
  local version="$1"
  local url path args=()
  path="$(release_api_path "$version")"
  url="https://api.github.com${path}"
  while IFS= read -r arg; do
    [[ -n "$arg" ]] && args+=("$arg")
  done < <(curl_auth_args)
  # Capture both body and HTTP status
  curl -fsSL -w "\n%{http_code}" "${args[@]}" "$url"
}

resolve_asset_id() {
  local version="$1"
  local asset_name="$2"
  local json tag available id
  local raw status
  raw="$(fetch_release_json "$version" 2>/dev/null || true)"
  json="${raw%$'\n'*}"
  status="${raw##*$'\n'}"

  if [[ "$status" != "200" || -z "$json" ]]; then
    if [[ "$status" == "404" && -z "${TOJI_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
      die "Could not find release \"${version}\" for ${TOJI_REPO}.

This usually means no releases have been published yet (public repo).

As the maintainer, push a version tag:
  git tag v0.1.1 && git push origin v0.1.1

Then wait for the GitHub Actions 'Release' workflow to finish.

See https://github.com/${TOJI_REPO}/releases"
    fi
    if [[ -z "${TOJI_GITHUB_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" && -z "${GH_TOKEN:-}" ]]; then
      die "Could not find release \"${version}\" for ${TOJI_REPO}.

If https://github.com/${TOJI_REPO} is private, run 'gh auth login' (recommended)
or set TOJI_GITHUB_TOKEN (PAT with repo scope).

See https://github.com/${TOJI_REPO}/releases"
    fi
    die "Could not find release \"${version}\" for ${TOJI_REPO}.

Check the tag name or token permissions.

See https://github.com/${TOJI_REPO}/releases"
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    die "python3 is required to parse GitHub release metadata"
  fi
  id="$(RELEASE_JSON="$json" ASSET_NAME="$asset_name" python3 - <<'PY'
import json, os, sys
data = json.loads(os.environ["RELEASE_JSON"])
name = os.environ["ASSET_NAME"]
tag = data.get("tag_name", "?")
assets = data.get("assets") or []
for a in assets:
    if a.get("name") == name:
        print(a["id"])
        sys.exit(0)
names = ", ".join(sorted(a.get("name", "?") for a in assets))
print(f"MISSING:{tag}:{names}", file=sys.stderr)
sys.exit(1)
PY
)" || {
    tag="$(printf '%s' "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tag_name','?'))")"
    available="$(printf '%s' "$json" | python3 -c "import sys,json; a=json.load(sys.stdin).get('assets') or []; print(', '.join(sorted(x.get('name','?') for x in a)) or '(none)')")"
    die "Release ${tag} has no asset \"${asset_name}\".

Available assets: ${available}
The release may still be publishing — retry in a few minutes or pin an older version.

See https://github.com/${TOJI_REPO}/releases"
  }
  printf '%s\n' "$id"
}

fetch_asset() {
  local asset_id="$1"
  local dest="$2"
  local asset_label="${3:-release asset}"
  local url args=()
  url="https://api.github.com/repos/${TOJI_REPO}/releases/assets/${asset_id}"
  while IFS= read -r arg; do
    [[ -n "$arg" ]] && args+=("$arg")
  done < <(curl_auth_args)
  if ! curl -fsSL "${args[@]}" -H "Accept: application/octet-stream" "$url" -o "$dest" 2>/dev/null; then
    die "Failed to download ${asset_label} from ${TOJI_REPO}.

If the repo is private, ensure TOJI_GITHUB_TOKEN is set (or run gh auth login before piping).

See https://github.com/${TOJI_REPO}/releases"
  fi
}

path_contains_dir() {
  local dir="$1"
  case ":${PATH}:" in
    *":${dir}:"*) return 0 ;;
    *)            return 1 ;;
  esac
}

update_state_path() {
  local base="${XDG_STATE_HOME:-${HOME}/.local/state}"
  printf '%s/toji/toji.json' "$base"
}

seed_update_state() {
  local dest="$1"
  local state_path version_label
  state_path="$(update_state_path)"
  version_label="$("$dest" --version 2>/dev/null || echo "toji unknown")"
  version_label="${version_label#toji }"
  version_label="${version_label%% *}"
  mkdir -p "$(dirname "$state_path")"
  printf '{\n  "last_checked_at": "%s",\n  "version": "%s"\n}\n' \
    "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
    "$version_label" >"$state_path"
}

main() {
  need_cmd uname
  need_cmd chmod
  need_cmd mkdir
  need_cmd curl

  local asset version asset_id tmp dest
  asset="$(detect_asset)"
  version="$(normalize_version "$TOJI_VERSION")"
  dest="${TOJI_INSTALL_DIR}/toji"

  info "platform ${asset}"
  info "version ${version}"
  info "install ${dest}"

  asset_id="$(resolve_asset_id "$version" "$asset")"

  mkdir -p "$TOJI_INSTALL_DIR"
  tmp="$(mktemp "${TMPDIR:-/tmp}/toji-install.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT

  fetch_asset "$asset_id" "$tmp" "$asset"

  chmod +x "$tmp"
  if ! "$tmp" --version >/dev/null 2>&1; then
    die "downloaded binary failed smoke test (--version)"
  fi

  mv "$tmp" "$dest"
  trap - EXIT

  seed_update_state "$dest"

  info "installed $("$dest" --version)"
  info "binary: ${dest}"

  if path_contains_dir "$TOJI_INSTALL_DIR"; then
    info "run: toji --version"
    info "auth: toji auth"
  else
    info "add ${TOJI_INSTALL_DIR} to PATH, then run: toji --version"
    cat <<EOF

Add to ~/.bashrc, ~/.zshrc, or equivalent:

  export PATH="${TOJI_INSTALL_DIR}:\$PATH"

Then:

  toji --version
  toji auth
  toji install curtain
EOF
  fi
}

# Run when executed directly or piped (curl ... | bash). Skip when sourced.
if [[ "${BASH_SOURCE[0]:-}" == "${0}" ]] || [[ -z "${BASH_SOURCE[0]:-}" ]]; then
  main "$@"
fi
