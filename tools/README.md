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

## Testing against a live Host, without a device

`./tools/live-harness.sh` drives the app's **real** `DshClient` and
`AppStateHolder` against a running DSH Host and reads the state back:

```
== live harness against http://192.168.255.5:3080
ok   the mux socket connects
ok   a sent message is on screen before the Host logs it
ok   the Host's own copy replaces it
ok   the steer appears in the Host's queue as steering
ok   and the Host's inbox admits it
ok   a message removed elsewhere says so instead of waiting forever
ALL PASS (16 checks)
```

It is not a re-implementation: it constructs the real classes and then reads the
same `queues`, pending rows and transcript rows the UI is drawn from. Compose
layout is the one thing that still needs a device; every bug of the "a frame
arrived and the state went wrong" kind is visible here.

Two things make it possible:

- `tools/live-harness/android/util/Log.kt` stands in for `android.util.Log`,
  whose stub in `android.jar` throws. It is compiled into its own directory and
  put *first* on the classpath, ahead of `android.jar`.
- The main sources need the Compose compiler plugin, because they contain the UI
  even though the harness never draws anything.

It creates one throwaway session, works only inside it, and archives it on the
way out; a run spends a few thousand tokens of the Host's model quota. A
waterfall delivered to the harness is left for the human's clients to answer --
the Host resolves one on the first answer, so a silent client cannot block it.

The emulator is not an option here: WSL2 exposes no `/dev/kvm` unless the
Windows host provides nested virtualization, which needs Windows 11 (this host
is Windows 10 19044), and the x86_64 Android images refuse to boot without it.

## Where the client's state rules live

`docs/reconnect.md` is the rulebook for anything that arrives over a stream: what
a snapshot means versus a delta, which mirrors are re-read on a reconnect, and
which test pins each rule. Read it before adding a stream or a cached list; the
same "the screen was stale" bug was fixed once per stream before the pattern was
written down.

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

## Icon

`python3 tools/make-icon.py` draws the launcher icons: the letters **DSH** as a
monogram, composed from primitives so nothing is traced from an SVG and no vendor's
mark is involved. It sizes the letters to a fraction of the canvas width rather than
to a point size, and keeps them inside an adaptive icon's 72dp safe zone.
`--preview` prints both shapes as ASCII (dark meaning ink) and `--export` writes the
README's preview images from the same code — how the mark was checked here, since
this VM has no image viewer. The ASCII preview is what rejected the phone-outline
mark that preceded it.

## Editing hazard

`tools/*.py` that patch sources use a `sub()` helper that raises when its anchor
is missing. `str.replace` silently no-ops on a missing anchor, which twice
produced a "successful" edit and a build of unchanged code. Keep using it.

## Release builds

`./tools/build-release.sh` runs R8 over the debug build's classes and packages the
result, so a minified build can be installed and exercised before a tag is pushed.

Two things it exists to catch, both found this way and neither visible in a debug
build:

- R8 removes `kotlinx-coroutines-android`'s `AndroidDispatcherFactory`, which is
  reached through `META-INF/services` rather than by reference. The app then died on
  `MainActivity.onCreate` with "Module with the Main dispatcher is missing".
- Packaging only `*.dex` left `META-INF/services` out of the APK, which breaks the
  same lookup again even once the class is kept.

Gradle merges the keep rules that prevent the first from the libraries' AARs;
invoking R8 directly does not, so they live in `app/proguard-rules.pro`.

### Signing

The script signs with the debug key by default, so it works out of the box. A real
key is passed in, and then the password is asked for on the terminal rather than
taken from `KEYPASS` — a password in the environment reaches `ps` and the shell
history:

```sh
KEYSTORE=~/.local/dsh-native-release.jks ./tools/build-release.sh
```

It prompts through `apksigner`, which needs no shell trickery to hide input; a
`read -p` prompt would not work in zsh, whose `read` has no such option. `KEYPASS`
still works and skips the prompt, for scripted builds.

`./tools/check-release-key.sh` answers the other question — *is this keystore the key
that signed the published APK?* — before you find out from users who cannot install
the update. Wrong key, wrong password and unknown alias each produce a distinct
nonzero exit, and it takes `CERT_SHA256` to expect a different fingerprint. The
keystore and its password are kept apart on purpose, so this gets asked eventually.

Both scripts assume the keystore and key passwords are the same, which is how CI's
secrets are set up. If they ever diverge, pass `KEYPASS` and the key password
separately by editing the `apksigner sign` line.

## Testing against a live harness

Some of this app's behaviour can only be checked against a running DSH, and that
harness holds real work. Two rules, both learned the hard way:

- **Create a session to test with.** Archiving is one-way in DSH and there is no
  unarchive API, so a state-changing test run against a session the user is working
  in destroys something that cannot be restored from the app. A throwaway session
  costs nothing and can be archived afterwards.
- **Read the corpus, do not write to it.** Auditing event shapes, table parsing and
  notice classification is read-only and safe; it also caught more than any
  hand-written case did.
