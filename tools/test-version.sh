#!/usr/bin/env bash
# Tests for tools/version.sh.
#
# The version is derived by a shell script that both build paths call, so a
# mistake in it breaks CI and the local build at once -- and the failure mode is
# silent: an APK that builds fine and claims the wrong version, which is how
# three releases went out stamped 0.1.0. So the derivation is tested against
# throwaway repositories rather than trusted.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pass=0
fail=0

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf 'ok   %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'FAIL %s\n       expected: %s\n       actual:   %s\n' "$label" "$expected" "$actual"
    fail=$((fail + 1))
  fi
}

# A repository with only the tags asked for. The script is copied in because it
# resolves the repository from its own location.
scenario() {
  local dir
  dir="$(mktemp -d)"
  mkdir -p "$dir/tools"
  cp "$ROOT/tools/version.sh" "$dir/tools/"
  git -C "$dir" init -q .
  git -C "$dir" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init
  while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
    git -C "$dir" -c user.name=t -c user.email=t@t tag "$1"
    shift
  done
  if [ "${1:-}" = "--" ]; then
    shift
    while [ "$#" -gt 0 ]; do
      git -C "$dir" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$1"
      shift
    done
  fi
  ( cd "$dir" && ./tools/version.sh 2>/dev/null | tr '\n' ' ' | tr -d '"' )
  rm -rf "$dir"
}

check "a tagged commit names the release" \
  "VERSION_NAME=0.1.2 VERSION_CODE=1002 " "$(scenario v0.1.2)"

check "commits after a tag say how far ahead" \
  "VERSION_NAME=0.1.2+2 VERSION_CODE=1002 " "$(scenario v0.1.2 -- a b)"

check "the newest version tag wins" \
  "VERSION_NAME=0.2.0 VERSION_CODE=2000 " "$(scenario v0.1.0 v0.2.0)"

check "component widths do not collide" \
  "VERSION_NAME=0.10.0 VERSION_CODE=10000 " "$(scenario v0.10.0)"

check "patch versions increase the code" \
  "VERSION_NAME=1.0.9 VERSION_CODE=1000009 " "$(scenario v1.0.9)"

check "a repository with no version tag is not a release" \
  "VERSION_NAME=0.0.0-dev VERSION_CODE=1 " "$(scenario)"

check "a non-version tag is ignored" \
  "VERSION_NAME=0.0.0-dev VERSION_CODE=1 " "$(scenario docs-2026)"

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
