#!/usr/bin/env bash
# Run the JVM unit tests without Gradle or a JUnit jar.
#
# The app's tests are plain classes with @Test methods and org.junit.Assert calls,
# so Gradle is not actually needed to execute them -- only to fetch JUnit in CI.
# This script compiles the main sources plus the test sources, then runs
# tools/JvmTestRunner.kt, a reflection runner that stands in for the JUnit
# runner. Assertion failures throw AssertionError; the runner reports them by
# name. `kotlin.test`-style fixtures are not supported, and neither are
# parameterized or rule-based tests -- all of which this project has none of.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
M2="${M2:-$HOME/.local/m2}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/.local/android-sdk/platforms/android-36/android.jar}"
OUT="${OUT:-$ROOT/build/jvm-test}"

for required in "$KOTLINC" "$M2/classpath.txt" "$ANDROID_JAR"; do
  [ -e "$required" ] || { echo "missing: $required" >&2; exit 1; }
done

CP="$(sed 's/$/:/' "$M2/classpath.txt" | tr -d '\n')$ANDROID_JAR"
COMPOSE_PLUGIN="$HOME/.local/kotlinc/lib/compose-compiler-plugin.jar"
SERIALIZATION_PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"

rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test" "$OUT/runner" "$OUT/stub"

echo "== compile the JUnit stub"
# The JVM dependency set has no JUnit (it is built from the app graph), so the
# small org.junit surface the tests use is compiled here instead. Gradle in CI
# uses the real junit:junit; nothing about this stub reaches the APK.
"$KOTLINC" -jvm-target 17 -nowarn -d "$OUT/stub" "$ROOT/tools/junit-stub/org/junit/Assert.kt"

echo "== compile main sources"
find "$ROOT/app/src/main/java" -name '*.kt' > "$OUT/main.list"
"$KOTLINC" -classpath "$CP" -jvm-target 17 -nowarn \
  -Xplugin="$COMPOSE_PLUGIN" -Xplugin="$SERIALIZATION_PLUGIN" \
  -d "$OUT/main" @"$OUT/main.list"

echo "== compile test sources"
find "$ROOT/app/src/test/java" -name '*.kt' > "$OUT/test.list"
# Test resources (the live session/list capture) must be on the test classpath
# the same way Gradle puts them there.
# `-Xfriend-paths` is how Gradle makes `internal` visible to the test source set;
# without it a test cannot reach a helper that is deliberately not public API.
"$KOTLINC" -classpath "$CP:$OUT/main:$OUT/stub" -jvm-target 17 -nowarn \
  -Xplugin="$SERIALIZATION_PLUGIN" \
  -Xfriend-paths="$OUT/main" \
  -d "$OUT/test" @"$OUT/test.list"

echo "== compile runner"
"$KOTLINC" -classpath "$CP:$OUT/main:$OUT/test" -jvm-target 17 -nowarn \
  -d "$OUT/runner" "$ROOT/tools/JvmTestRunner.kt"

# kotlin-reflect is needed by the runner (KClass annotations and memberFunctions).
# The app itself never uses it, so it is not in the dependency set; the compiler
# distribution ships the same version.
REFLECT="$(find "$M2/org/jetbrains/kotlin/kotlin-reflect" -name 'kotlin-reflect-*.jar' 2>/dev/null | head -1)"
[ -n "$REFLECT" ] || { echo "kotlin-reflect jar not found under $M2" >&2; exit 1; }

echo "== run"
java -cp "$REFLECT:$CP:$OUT/stub:$OUT/main:$OUT/test:$OUT/runner:$ROOT/app/src/test/resources" JvmTestRunnerKt "$OUT/test"
