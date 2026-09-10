# Gradle-free build

`./tools/build.sh` builds the APK without Gradle, which cannot run in this WSL2
VM (see `gradle.properties` for the measurements). It drives the same tools
Gradle orchestrates:

| step | tool |
|---|---|
| dependency graph | `tools/fetch-deps.py` (walks POMs, arbitrates versions, extracts aar resources) |
| resources | `aapt2 compile` + `aapt2 link`, over a merged tree from `tools/merge-res.py` |
| library R classes | `tools/gen-r.py` (the aars ship none; they are a build-time product) |
| Kotlin | `kotlinc` with the bundled Compose and serialization plugins |
| dexing | `d8` |
| packaging | `zip`, `zipalign`, `apksigner` |

## Installing

With a device on adb there is no download step: `adb install --no-incremental -r
build/dsh-native-debug.apk`. A local HTTP server was used earlier, before adb was
available, and is not part of this flow.

## Running the unit tests

`./tools/run-jvm-tests.sh` compiles the main and test sources and runs every
`@Test` method. Gradle runs the same tests in CI; this exists because Gradle
cannot run here and waiting for CI to find a broken assertion is slow.

Two things make it work without Gradle:

- `tools/junit-stub/org/junit/Assert.kt` supplies the `org.junit` surface the
  tests use, because the local dependency set is built from the app's graph and
  contains no JUnit. It is compiled ahead of the test sources and never reaches
  the APK.
- `tools/JvmTestRunner.kt` is a reflection runner: it finds compiled test
  classes, instantiates them, and calls their `@Test` methods, reporting
  `AssertionError` messages by test name.

## Probing one class without a device

For a quick check of a decoder, a throwaway `main` against the app's own sources
is faster than a test file:

```sh
export JAVA_HOME=$HOME/.local/jdk17 PATH=$HOME/.local/jdk17/bin:$PATH
M2=$HOME/.local/m2; ANDROID_JAR=$HOME/.local/android-sdk/platforms/android-36/android.jar
CP="$(sed 's/$/:/' "$M2/classpath.txt" | tr -d '\n')$ANDROID_JAR"
# The app sources carry the Compose plugin's output, so they are compiled with
# the same plugins rather than against build/manual/classes, which holds
# library classes too.
find app/src/main/java -name '*.kt' > /tmp/probe/kt.list
$HOME/.local/kotlinc/bin/kotlinc -classpath "$CP" -jvm-target 17 -nowarn \
  -Xplugin="$HOME/.local/kotlinc/lib/compose-compiler-plugin.jar" \
  -Xplugin="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar" \
  -d /tmp/probe/app @/tmp/probe/kt.list
java -cp "/tmp/probe/app:$CP:/tmp/probe/out" ProbeKt
```

Keep the payloads in a file rather than in Kotlin string literals: host JSON
contains `${`, which Kotlin reads as interpolation.

## Where the event shapes come from

Guessing an event payload has broken this app twice (the tool-result fold and the
`modelCatalog` call). Two sources are authoritative, and both are cheap:

- the type vocabulary: `@deepseek-ai/dsh-session/lib/types/known-event-types.js`,
  a generated list of every `SessionEventMap` member in the build;
- the observed shapes: follow a spread of sessions over the real WS protocol and
  record one raw sample per `(type, payload shape)`. Transcripts on disk are the
  other option, but their framing is not plain concatenated zstd frames, so the
  live stream is less work.

The resulting table lives in `docs/event-coverage.md`.

## Editing hazard

`tools/*.py` that patch sources use a `sub()` helper that raises when its anchor
is missing. `str.replace` silently no-ops on a missing anchor, which twice
produced a "successful" edit and a build of unchanged code. Keep using it.
