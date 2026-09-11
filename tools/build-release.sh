#!/usr/bin/env bash
# Build a minified release APK without Gradle.
#
# Origin: written for this repository.
#
# `tools/build.sh` builds a debug APK with no shrinking; the Gradle release build
# in CI runs R8 over the same sources. Gradle cannot run in this VM, which left the
# release path -- and specifically whether R8 strips something the app needs at
# runtime -- verifiable only by pushing a tag and hoping. This script closes that
# gap: it reuses the debug build's resources and classes, then applies R8 with the
# same keep rules the Gradle build uses, so the minified app can be installed and
# exercised locally first.
#
# R8 is not in build-tools (only `d8` is), so it is fetched once into
# ~/.local/r8. The Android default rules come from the same AGP artifact Gradle
# feeds to R8.
#
#   ./tools/build-release.sh            # minified, signed with the debug key
#   KEYSTORE=... KEYPASS=... ./tools/...  # a real release key
#
# What this does NOT reproduce: Gradle's resource shrinking, and the release
# signing secrets. It is a check on R8's effect, not a substitute for CI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/android-sdk}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
M2="$HOME/.local/m2"
R8_HOME="${R8_HOME:-$HOME/.local/r8}"
R8_VERSION="${R8_VERSION:-8.11.18}"
MIRROR="${MIRROR:-https://maven.aliyun.com/repository/google}"
DEBUG_OUT="$ROOT/build/manual"
OUT="$ROOT/build/release"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# ── 1. R8 and the Android default rules ──────────────────────────────────────
mkdir -p "$R8_HOME"
if [ ! -f "$R8_HOME/r8-$R8_VERSION.jar" ]; then
  echo "== fetch r8 $R8_VERSION"
  curl -fsSL -o "$R8_HOME/r8-$R8_VERSION.jar" \
    "$MIRROR/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
fi
# AGP ships its default rules as a class, not as a file, so they are mirrored in
# `tools/proguard-android-optimize.txt` and used from there.
DEFAULT_RULES="$ROOT/tools/proguard-android-optimize.txt"
[ -f "$DEFAULT_RULES" ] || { echo "missing $DEFAULT_RULES" >&2; exit 1; }

# ── 2. the debug build's outputs ─────────────────────────────────────────────
# Resources are identical between the two build types here (no resource
# shrinking), so the debug build supplies them and R8 only has to handle code.
# `DEBUGGABLE=false` because this build's manifest is copied into the release APK:
# a release carrying `android:debuggable="true"` is exactly what lint's
# HardcodedDebugMode check exists to prevent.
if [ ! -f "$DEBUG_OUT/aligned.apk" ] || [ "${REBUILD:-1}" = "1" ]; then
  echo "== base build (resources and classes, not debuggable)"
  DEBUGGABLE=false "$ROOT/tools/build.sh" >/dev/null
fi

# ── 3. R8 ────────────────────────────────────────────────────────────────────
echo "== r8 (minify + shrink)"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/in"
# Program classes go in as one jar: R8 accepts class files, jars and apks, and the
# build output directory also holds a `META-INF/main.kotlin_module`, which it
# rejects as an unsupported source file type.
( cd "$DEBUG_OUT/classes" && "$JAVA_HOME/bin/jar" cf "$OUT/in/program.jar" . )
find "$M2/classes" -maxdepth 2 -name 'classes.jar' >> "$OUT/in/libs.list"
grep -E '\.jar$' "$M2/classpath.txt" >> "$OUT/in/libs.list"
sort -u "$OUT/in/libs.list" -o "$OUT/in/libs.list"
LIB_COUNT="$(wc -l < "$OUT/in/libs.list")"
echo "   program jar + $LIB_COUNT library jars"
# shellcheck disable=SC2046  # the library list is expanded deliberately: R8 treats
# an `@file` argument as a literal path rather than an argument file.
"$JAVA_HOME/bin/java" -cp "$R8_HOME/r8-$R8_VERSION.jar" com.android.tools.r8.R8 \
  --release \
  --lib "$ANDROID_JAR" \
  --min-api 29 \
  --output "$OUT/classes" \
  --pg-conf "$ROOT/app/proguard-rules.pro" \
  --pg-conf "$DEFAULT_RULES" \
  "$OUT/in/program.jar" $(cat "$OUT/in/libs.list")

# ── 4. package and sign ──────────────────────────────────────────────────────
# No d8 step: R8 dexes as it shrinks when given `--min-api`, exactly as the Gradle
# release task does, so `$OUT/classes` already holds the dex files.
echo "== package"
cp "$DEBUG_OUT/base.apk" "$OUT/unsigned.apk"
# The dex plus the service registrations. `META-INF/services` is how
# kotlinx-coroutines finds its Main dispatcher, and packaging only `*.dex` left it
# out: the app then died with "Module with the Main dispatcher is missing" even
# though the dispatcher class itself was in the dex.
( cd "$OUT/classes" && zip -q -X -r "$OUT/unsigned.apk" ./*.dex META-INF )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KEYSTORE="${KEYSTORE:-$HOME/.local/dsh-native-debug.jks}"
KEYPASS="${KEYPASS:-android}"
KEYALIAS="${KEYALIAS:-dshnative}"
echo "== apksigner (keystore: $KEYSTORE)"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" \
  --ks-key-alias "$KEYALIAS" --key-pass "pass:$KEYPASS" \
  --out "$ROOT/build/dsh-native-release.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$ROOT/build/dsh-native-release.apk" | head -3

if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$ROOT/build/dsh-native-release.apk" 2>/dev/null \
    | grep -q "debuggable.*=true"; then
  echo "refusing to publish: the release APK is marked debuggable" >&2
  exit 1
fi

echo
echo "APK: $ROOT/build/dsh-native-release.apk ($(stat -c %s "$ROOT/build/dsh-native-release.apk") bytes)"
