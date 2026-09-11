# dsh-native

An Android client for DeepSeek Harness that talks to a DSH backend directly, over
the harness's own protocol.

## What you need

**A DSH instance your phone can reach.** This app is a client, not a server: it
needs a running DSH whose HTTP API is reachable from the phone over the LAN,
together with a plugin that exposes that API for LAN use.

- **`dsh-lan-access` — required.** DSH's HTTP API is bound to loopback by default;
  this plugin serves it on the LAN and is what makes the backend reachable from a
  phone at all. Install it in your DSH instance before using this app.
- **`dsh-mobile` — not required**, deliberately. That plugin serves a web client
  whose bundle is re-downloaded on every reconnect; this app speaks the protocol
  instead and never fetches a client bundle.

## First run

Enter `host:port` (for example `192.168.1.20:3080`) in Settings and connect. The
app speaks plain HTTP and WebSocket on your LAN, so use it only on a network you
trust.

## What it does

- **Sessions** grouped by workspace, newest first, with the Host's archive set
  respected and archived sessions one toggle away.
- **Live transcript**: assistant replies as Markdown (headings, lists, tables,
  quotes, links, fenced code with syntax highlighting), tool calls as cards that
  fold in their results, background-job notices as cards, and a follow-the-newest
  behaviour that only follows while you are at the bottom.
- **The plan and the output**: `todo/write` renders as a checklist, and files a turn
  presents open in a preview — text inline, images full screen with pinch-to-zoom.
- **Control**: send prompts, cancel a running turn, switch model and reasoning
  effort, run `/compact`, answer approvals and questions the Host asks, and create
  sessions either in a chosen workspace or in the default directory.
- **Resilience**: reconnects on its own, and a dropped connection does not take the
  app down with it.

## Requirements

Android 10 (API 29) or newer; built against API 36.

## Installing

Take the APK from [Releases](../../releases). It is signed with a release key; the
debug APK CI uploads as an artifact is signed differently, and the two cannot
update each other in place.

## Building

`./tools/build.sh` builds a debug APK without Gradle (which cannot run in the
development VM — see `gradle.properties`) by driving `aapt2`, `kotlinc`, `d8`,
`zipalign` and `apksigner` directly. `./tools/run-jvm-tests.sh` runs the unit tests
the same way.

`./tools/build-release.sh` builds a minified release APK: it runs R8 over the debug
build's classes with the project's keep rules. Worth running before tagging,
because R8 removes things Gradle would have kept — `app/proguard-rules.pro` records
what and why.

CI (`.github/workflows/android.yml`) runs the tests with Gradle and builds both
APKs; tagging `v*` publishes the signed release.

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
| `tools/README.md` | the build scripts, the local test runner, protocol probing |

## Protocol notes

Four things about this harness are easy to get wrong, and each cost a bug here:

- **Event shapes come from the wire, never from a guess.** `docs/event-coverage.md`
  records where each one was observed, and the decoder tests pin them with captured
  payloads.
- **A `user/message` is an envelope, not a person.** Only `source.kind == "user"` is
  human; `plugin`, `agent-message`, `subagent-settled`, `agent-instructions` and
  `skill-catalog` are all machine-produced, and rendering them as user text puts
  words in the reader's mouth.
- **The Host owns workspace grouping and the archive set**; the client renders what
  `workspace/follow` reports, and archiving is one-way — there is no unarchive API.
- **`session/create` takes `workspaceId` or `cwd`, never both**, and only the
  workspace route makes the new session a member of that group.
