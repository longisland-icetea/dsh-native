#!/usr/bin/env bash
# The version this checkout is, derived from its own tags.
#
# Both build paths need the same answer, and neither can be the source of truth:
# the Gradle build is what CI publishes, and the Gradle-free build is what works
# on a machine where Gradle cannot run at all. So the derivation lives here, in a
# script both call, and the tag is the only thing anyone has to set.
#
#   versionCode  monotonic, one per tag: v0.1.2 -> 102. Android compares these
#                as integers, so they must not repeat and must not go backwards.
#   versionName  "0.1.2" on a tagged commit; "0.1.2+7" seven commits later, so a
#                build from main says which release it is based on.
#
# Prints shell assignments, so a caller can `eval "$(tools/version.sh)"`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The highest version tag reachable from HEAD, not the nearest one.
#
# `git describe` picks by commit distance, so a repository holding both v0.1.0
# and v0.2.0 on one commit could report either -- and one of them is a release
# claiming to be an older release. Sorting by version and taking the first is what
# "the version this checkout is" means. `--merged` keeps tags on other branches
# out of it, and the match pattern keeps a date or a docs tag from being read as
# a release. The test suite pins this: "the newest version tag wins".
TAG="$(git -C "$ROOT" tag --list 'v[0-9]*' --merged HEAD --sort=-v:refname 2>/dev/null | head -n 1 || true)"

if [ -z "$TAG" ]; then
  echo 'VERSION_NAME="0.0.0-dev"'
  echo 'VERSION_CODE=1'
  exit 0
fi

CLEAN="${TAG#v}"
CLEAN="${CLEAN%%-*}"          # v0.2.0-rc.1 -> 0.2.0, for the arithmetic below
MAJOR="${CLEAN%%.*}"
REST="${CLEAN#*.}"
MINOR="${REST%%.*}"
PATCH="${REST#*.}"
PATCH="${PATCH%%[!0-9]*}"     # tolerate a suffix

# Digits only -- a malformed tag should not silently become version 0.
case "$MAJOR$MINOR$PATCH" in
  ''|*[!0-9]*)
    echo "version.sh: tag '$TAG' does not parse as v<major>.<minor>.<patch>" >&2
    exit 1
    ;;
esac

# Three digits per component: room for 999 minors and 999 patches, which is more
# than this project will use and still inside Android's 32-bit limit.
CODE=$(( 10#$MAJOR * 1000000 + 10#$MINOR * 1000 + 10#$PATCH ))

if [ "$CODE" -lt 1 ]; then
  echo "version.sh: tag '$TAG' yields versionCode $CODE; it must be positive" >&2
  exit 1
fi

# Commits since the tag, so an untagged build is identifiable without pretending
# to be a release.
AHEAD="$(git -C "$ROOT" rev-list --count "$TAG..HEAD" 2>/dev/null || echo 0)"

if [ "$AHEAD" = "0" ]; then
  NAME="$CLEAN"
else
  NAME="$CLEAN+$AHEAD"
fi

echo "VERSION_NAME=\"$NAME\""
echo "VERSION_CODE=$CODE"
