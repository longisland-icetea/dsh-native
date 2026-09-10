#!/usr/bin/env bash
# Build the APK without Gradle.
#
# Gradle cannot run in this WSL2 VM: networkingMode=VirtioProxy breaks a JVM's
# cross-process loopback socket, and Gradle always forks a daemon the client
# must reach over exactly that socket (see gradle.properties for the
# measurements). Everything Gradle orchestrates is three tools plus a compiler,
# so this script runs them directly:
#
#   aapt2      compile + link resources, emit R.java
#   kotlinc    compile Kotlin (with the Compose compiler plugin)
#   d8         dex the classes
#   zipalign + apksigner   package and sign
#
# Prerequisites, all user-local, no sudo:
#   ~/.local/jdk17                        Temurin 17
#   ~/.local/android-sdk                  build-tools 36.0.0 + platforms/android-36
#   ~/.local/kotlinc                      Kotlin 2.1.0 compiler
#   python3 tools/fetch-deps.py           resolves deps into ~/.local/m2
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/android-sdk}"
KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
M2="$HOME/.local/m2"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
# Compiler plugins come from the kotlinc distribution, not Maven: the bundled
# jars are built against the compiler's shaded IntelliJ classes, while the
# ...-embeddable artifacts fail with NoClassDefFoundError on
# org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap.
KOTLIN_LIB="$HOME/.local/kotlinc/lib"
COMPOSE_PLUGIN="$KOTLIN_LIB/compose-compiler-plugin.jar"
SERIALIZATION_PLUGIN="$KOTLIN_LIB/kotlinx-serialization-compiler-plugin.jar"
OUT="$ROOT/build/manual"
KEYSTORE="${KEYSTORE:-$HOME/.local/dsh-native-debug.jks}"
KEYPASS="${KEYPASS:-android}"
KEYALIAS="${KEYALIAS:-dshnative}"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

for required in "$JAVA_HOME/bin/java" "$KOTLINC" "$ANDROID_JAR" "$COMPOSE_PLUGIN" "$SERIALIZATION_PLUGIN" "$M2/classpath.txt"; do
  [ -e "$required" ] || { echo "missing prerequisite: $required" >&2; exit 1; }
done
echo "build-tools : $(basename "$BT")"
echo "platform    : $(basename "$PLATFORM")"
echo "kotlinc     : $("$KOTLINC" -version 2>&1 | head -1)"

rm -rf "$OUT"
mkdir -p "$OUT/res-compiled" "$OUT/classes" "$OUT/dex" "$OUT/gen"

# ── 1. resources ─────────────────────────────────────────────────────────────
echo "== aapt2 compile"
# Compile file by file, grouping output by resource type. `aapt2 compile --dir`
# exits 0 while emitting an empty archive for this tree, which then fails later
# as "resource string/app_name not found"; the per-file form is reliable.
aapt2_compile_files() {
  local type dir out
  for dir in "$ROOT"/app/src/main/res/*/; do
    [ -d "$dir" ] || continue
    type="$(basename "$dir")"
    out="$OUT/res-compiled/$type"
    mkdir -p "$out"
    local files=()
    while IFS= read -r -d '' f; do files+=("$f"); done < <(find "$dir" -maxdepth 1 -type f -print0)
    [ "${#files[@]}" -gt 0 ] || continue
    "$BT/aapt2" compile -o "$out" "${files[@]}"
  done
}
aapt2_compile_files

echo "== aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --version-code 1 --version-name 0.1.0 \
  $(find "$OUT/res-compiled" -name '*.flat' | sort)

# ── 2. R.java ────────────────────────────────────────────────────────────────
echo "== javac R.java"
if compgen -G "$OUT/gen/**/R.java" >/dev/null || [ -n "$(find "$OUT/gen" -name R.java -print -quit)" ]; then
  find "$OUT/gen" -name "*.java" > "$OUT/rjava.list"
  javac -source 17 -target 17 -nowarn -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/rjava.list"
else
  echo "   (no R.java generated; the app references no resources directly)"
fi

# ── 3. Kotlin ────────────────────────────────────────────────────────────────
echo "== kotlinc (Compose plugin)"
# `sed` guarantees a separator after the last entry even if classpath.txt has no
# trailing newline; otherwise android.jar is appended to the final jar path and
# every android.* import fails to resolve.
CP="$(sed 's/$/:/' "$M2/classpath.txt" | tr -d '\n')$ANDROID_JAR:$OUT/classes"
find "$ROOT/app/src/main/java" -name '*.kt' > "$OUT/kt.list"
"$KOTLINC" \
  -classpath "$CP" \
  -jvm-target 17 \
  -nowarn \
  -Xplugin="$COMPOSE_PLUGIN" \
  -Xplugin="$SERIALIZATION_PLUGIN" \
  -d "$OUT/classes" \
  @"$OUT/kt.list"

# ── 4. dex ───────────────────────────────────────────────────────────────────
echo "== d8"
# Every classpath jar is a dex input: Compose ships Kotlin/Java bytecode that
# has to end up in the APK's dex files, not on a compile classpath only.
CLASSES="$(find "$OUT/classes" -name '*.class')"
JARS="$(tr '\n' ' ' < "$M2/classpath.txt")"
"$BT/d8" --min-api 29 --lib "$ANDROID_JAR" --output "$OUT/dex" $JARS $CLASSES

# ── 5. package + sign ────────────────────────────────────────────────────────
echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
# -X drops the extra file attributes an APK does not want; the entry names must
# be exactly classes.dex, classes2.dex, ... at the archive root.
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" ./*.dex )

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating a debug keystore at $KEYSTORE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEYALIAS" \
    -storepass "$KEYPASS" -keypass "$KEYPASS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "== apksigner"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEYALIAS" \
  --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out "$ROOT/build/dsh-native-debug.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$ROOT/build/dsh-native-debug.apk" | head -3

echo
echo "APK: $ROOT/build/dsh-native-debug.apk ($(stat -c %s "$ROOT/build/dsh-native-debug.apk") bytes)"
