# dsh-native

An Android client that talks to a DSH backend directly over its own protocol, so
a phone can read and drive a session without the browser bundle.

## Why this exists

The mobile web client re-downloads its bundle on every reconnect — tens of
megabytes over a phone connection. This app speaks the harness's own protocol
instead: unary `POST /api/<ns>/<method>` for calls and one WebSocket
(`/api/remote.mux`) for streams, with the transcript rendered natively.

## Layout

| path | what |
|---|---|
| `app/src/main/java/.../Protocol.kt` | wire types and decoders |
| `app/src/main/java/.../DshClient.kt` | OkHttp transport: unary calls, mux streams, reconnect loop |
| `app/src/main/java/.../AppState.kt` | state holder, event → transcript reducer, actions |
| `app/src/main/java/.../MainActivity.kt` | Compose UI |
| `app/src/main/java/.../SimpleMarkdown.kt` | hand-written Markdown subset |
| `app/src/main/java/.../CodeHighlight.kt` | hand-written syntax highlighting |
| `docs/event-coverage.md` | every session event type, and how each is rendered |
| `tools/README.md` | Gradle-free build, local test runner, protocol probing |

## Building

Gradle cannot run in the development VM, so `./tools/build.sh` drives the same
tools Gradle would (`aapt2`, `kotlinc`, `d8`, `zipalign`, `apksigner`) and
produces `build/dsh-native-debug.apk`. `./tools/run-jvm-tests.sh` runs the unit
tests locally. CI (`.github/workflows/android.yml`) runs the same tests through
Gradle and builds a debug APK.

## Notes that are easy to get wrong

- **Event shapes come from the wire, never from a guess.** Two features were
  broken by invented payloads (the tool-result fold and the model catalog call),
  so `docs/event-coverage.md` records where each shape was observed and
  `EventDecodeTest` pins it with a captured payload.
- **Uncaught `IOException`s from socket threads kill the process.** OkHttp hands
  a dropped connection to the thread's uncaught-exception handler, so
  `DshClient.installSocketCrashGuard` absorbs exactly that class of failure —
  every other throwable still crashes loudly.
- **Network work belongs on `Dispatchers.IO`.** The mux reader loop on the main
  dispatcher died with `NetworkOnMainThreadException` and took the process with
  it.
- **The Host owns workspace grouping and the archive set**; the client only
  renders what `workspace/follow` reports. There is no unarchive API.
