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
# Defaults: ~/.local/dsh-native-keys/dsh-native-release.jks, alias `dshnative`. Leave
# the alias empty to check every entry. Set CERT_SHA256 to expect a different
# fingerprint.
#
# The password comes from ~/.local/dsh-native-keys/keystore.properties -- the file
# the key rotation wrote, readable only by this user -- so the check is one command
# rather than one command plus a prompt. With no properties file, keytool asks on
# the terminal, and the password is never an argument or an environment variable.

set -euo pipefail

# `${2-default}` and not `${2:-default}`: the colon form also substitutes on an
# explicitly empty argument, which would make "check every entry" unreachable.
PROPERTIES="${DSH_KEYSTORE_PROPERTIES:-$HOME/.local/dsh-native-keys/keystore.properties}"
KEYSTORE="${1-$HOME/.local/dsh-native-keys/dsh-native-release.jks}"
ALIAS="${2-dshnative}"

# The certificate of the current release key (rotated 2026-09-29). A keystore that
# does not match this can still be a perfectly good key -- it is just not the key
# users already have, and one signed with it cannot install over their app.
EXPECTED="${CERT_SHA256:-B0E86743F8028C9888D5F2EC3824DC7792891C832A628BD4506C9C7C8EEF982A}"
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

# With no properties file, `-storepass` is omitted on purpose so keytool asks on the
# terminal: reading it with a shell builtin would not work everywhere (zsh's `read`
# has no -p), and a password on the command line would reach `ps`.
STORE_PASS="${STOREPASS:-}"
if [ -z "$STORE_PASS" ] && [ -f "$PROPERTIES" ]; then
  STORE_PASS="$(sed -n 's/^release\.storePassword=//p' "$PROPERTIES" | head -1)"
fi
pass_args=()
[ -n "$STORE_PASS" ] && pass_args=(-storepass "$STORE_PASS")
alias_args=()
[ -n "$ALIAS" ] && alias_args=(-alias "$ALIAS")

output="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" "${pass_args[@]}" "${alias_args[@]}" 2>&1)" || {
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
  echo "  This machine's key, alias and password are written down in:" >&2
  echo "    ~/DSH-NATIVE-SIGNING.md" >&2
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
