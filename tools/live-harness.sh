#!/usr/bin/env bash
# Drive the app's real client and state machine against a live Host.
#
# The phone is the only place Compose layout can be checked, but it is not the
# only place the *state* can be: `DshClient` and `AppStateHolder` are plain JVM
# code once `android.util.Log` is stood in for, and Android's own stub jar
# cannot run. This script compiles them against the JVM dependency set, puts a
# local `android.util.Log` ahead of the stub jar, and runs the harness.
#
# It talks to a running DSH Host, creates one throwaway session and archives it,
# and spends a few thousand tokens of that Host's model quota. Nothing else on
# the Host is read or written.
#
#   tools/live-harness.sh                     # 192.168.255.5:3080
#   DSH_HOST=10.0.0.5 tools/live-harness.sh   # somewhere else
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
M2="${M2:-$HOME/.local/m2}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/.local/android-sdk/platforms/android-36/android.jar}"
OUT="${OUT:-$ROOT/build/live-harness}"

for required in "$KOTLINC" "$M2/classpath.txt" "$ANDROID_JAR"; do
  [ -e "$required" ] || { echo "missing: $required" >&2; exit 1; }
done

CP="$(sed 's/$/:/' "$M2/classpath.txt" | tr -d '\n')$ANDROID_JAR"
SERIALIZATION_PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
# The main sources include the Compose UI, so they need the same compiler plugin
# the app build uses even though the harness itself never draws anything.
COMPOSE_PLUGIN="$HOME/.local/kotlinc/lib/compose-compiler-plugin.jar"
REFLECT="$(find "$M2/org/jetbrains/kotlin/kotlin-reflect" -name 'kotlin-reflect-*.jar' 2>/dev/null | head -1)"

rm -rf "$OUT"
mkdir -p "$OUT/stub" "$OUT/main" "$OUT/harness"

echo "== compile the android.util.Log stand-in"
# Ahead of android.jar on the classpath: the stub jar's methods throw, and the
# app logs on nearly every path it takes.
"$KOTLINC" -jvm-target 17 -nowarn -d "$OUT/stub" \
  "$ROOT/tools/live-harness/android/util/Log.kt"

echo "== compile the app's sources"
find "$ROOT/app/src/main/java" -name '*.kt' > "$OUT/main.list"
# `BuildInfo` is generated per build; the harness only needs the class to exist.
cat > "$OUT/BuildInfo.kt" <<'EOF'
package io.github.longislandicetea.dshnative

internal object BuildInfo {
    const val VERSION_NAME = "0.0.0-harness"
    const val VERSION_CODE = 0
}
EOF
echo "$OUT/BuildInfo.kt" >> "$OUT/main.list"
"$KOTLINC" -classpath "$CP" -jvm-target 17 -nowarn \
  -Xplugin="$COMPOSE_PLUGIN" -Xplugin="$SERIALIZATION_PLUGIN" \
  -d "$OUT/main" @"$OUT/main.list"

echo "== compile the harness"
"$KOTLINC" -classpath "$CP:$OUT/main" -jvm-target 17 -nowarn \
  -Xplugin="$SERIALIZATION_PLUGIN" \
  -Xfriend-paths="$OUT/main" \
  -d "$OUT/harness" "$ROOT/tools/live-harness/LiveHarness.kt"

echo "== run against ${DSH_HOST:-192.168.255.5}:${DSH_PORT:-3080}"
# The stub directory comes before android.jar, which is the whole trick.
java -cp "$OUT/stub:$REFLECT:$CP:$OUT/main:$OUT/harness" \
  io.github.longislandicetea.dshnative.LiveHarnessKt "$@"
