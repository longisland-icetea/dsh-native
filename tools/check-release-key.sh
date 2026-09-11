#!/usr/bin/env bash
# Check that a keystore is the release key: the one that signed the published APK.
#
# Why this exists: the keystore and its password live apart on purpose, so "do these
# two go together?" is a question that comes up long after the answer was obvious.
# Getting it wrong is quiet and expensive -- a release signed with the wrong key
# cannot update anything already installed, and only users find out.
#
# Usage:
#   ./tools/check-release-key.sh [keystore] [alias]
#
# Defaults: ~/.local/dsh-native-release.jks, alias `dshnative`. Leave the alias empty
# to check every entry. Set CERT_SHA256 to expect a different fingerprint.
#
# The password is read by keytool from stdin: it is never an argument, never in the
# environment, and never in the shell history.

set -euo pipefail

# `${2-default}` and not `${2:-default}`: the colon form also substitutes on an
# explicitly empty argument, which would make "check every entry" unreachable.
KEYSTORE="${1-$HOME/.local/dsh-native-release.jks}"
ALIAS="${2-dshnative}"

# The certificate of the v0.1.0 release. A keystore that does not match this can
# still be a perfectly good key -- it is just not the key users already have.
EXPECTED="${CERT_SHA256:-508da7362f15844cb519a3e25fb703ad564b51725ab1901f5da244f4f19be4e7}"
norm() { printf '%s' "$1" | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]'; }
EXPECTED_NORM="$(norm "$EXPECTED")"

find_keytool() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    printf '%s\n' "$JAVA_HOME/bin/keytool"; return
  fi
  for jdk in "$HOME"/.local/jdk*; do
    [ -x "$jdk/bin/keytool" ] && { printf '%s\n' "$jdk/bin/keytool"; return; }
  done
  command -v keytool || true
}

KEYTOOL="$(find_keytool)"
if [ -z "$KEYTOOL" ]; then
  echo "error: no keytool found (set JAVA_HOME, or install a JDK)." >&2
  exit 2
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "error: no keystore at $KEYSTORE" >&2
  exit 2
fi

echo "keystore: $KEYSTORE"
echo "alias:    ${ALIAS:-(every entry)}"
echo "expected: $EXPECTED_NORM"
echo

# `-storepass` is omitted on purpose so keytool asks, on the terminal. Reading it
# with a shell builtin would not work everywhere: zsh's `read` has no -p.
alias_args=()
[ -n "$ALIAS" ] && alias_args=(-alias "$ALIAS")

output="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" "${alias_args[@]}" 2>&1)" || {
  # keytool's failure output is a Java stack trace that buries the one line that
  # matters, so show it only when the cause is not the password.
  if ! printf '%s' "$output" | grep -q "password was incorrect"; then
    printf '%s\n' "$output" >&2
    echo >&2
  fi
  echo "FAILED: the password did not open the keystore${ALIAS:+, or '$ALIAS' is not an entry in it}." >&2
  echo >&2
  echo "  If the password is right and this still fails, the keystore is not the one" >&2
  echo "  that password belongs to -- suspect the file, not the password." >&2
  echo "  The pair that certainly exists is in CI:" >&2
  echo "    gh secret list --repo longisland-icetea/dsh-native" >&2
  exit 1
}

# Not anchored: keytool indents continuation lines with a tab, so `^Owner:` would
# quietly drop the owner on a keystore whose first alias is a root entry.
printf '%s\n' "$output" | grep -E 'Alias name:|Owner:|SHA256:'
echo

fps="$(printf '%s\n' "$output" | awk '/SHA256:/ {print $2}')"
if [ -z "$fps" ]; then
  echo "FAILED: opened the keystore but found no certificate in it." >&2
  exit 1
fi

while IFS= read -r fp; do
  [ -z "$fp" ] && continue
  if [ "$(norm "$fp")" = "$EXPECTED_NORM" ]; then
    echo "OK: this keystore holds the release key."
    echo "    $fp"
    echo
    echo "    tools/build-release.sh can sign an upgrade that installs over an"
    echo "    existing install."
    exit 0
  fi
done <<< "$fps"

echo "MISMATCH: the keystore opened, but it is a different key."
echo "    got:      $(printf '%s\n' "$fps" | head -1)"
echo "    expected: $EXPECTED_NORM"
echo
echo "    A release signed with this key would NOT update an existing install."
exit 1
