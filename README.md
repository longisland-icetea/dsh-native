# dsh-native

An Android client for DeepSeek Harness that talks to a DSH backend directly, over
the harness's own protocol.

> **A hobby project, written by an AI.** The code here was produced by a coding
> agent working with one person, not by a maintained team. It works for what it was
> built for, but expect rough edges, uneven readability, and no guarantee of upkeep
> or support. Issues and pull requests may go unanswered.

## What you need

**A DSH instance your phone can reach.** This app is a client, not a server: it
needs a running DSH whose HTTP API is reachable from the phone over the LAN,
together with a plugin that exposes that API for LAN use.

- **[`dsh-lan-access`](https://github.com/longisland-icetea/dsh-lan-access) —
  required.** DSH's HTTP API is bound to loopback by default; this plugin serves it
  on the LAN and is what makes the backend reachable from a phone at all. Install it
  in your DSH instance before using this app. The app talks to the endpoint that
  plugin exposes, so its routing and port are part of the setup.
- **`dsh-mobile` — not required**, deliberately. That plugin serves a web client
  whose bundle is re-downloaded on every reconnect; this app speaks the protocol
  instead and never fetches a client bundle.

## Security model

Worth reading before you point this at anything.

- **The connection is plain HTTP and WebSocket, with no authentication.** DSH's LAN
  API is not authenticated and this app adds nothing on top: anyone on the same
  network who knows the address can read your sessions and send prompts as you.
  That is the trade the underlying plugin makes. Use it on a network you trust --
  a home LAN, a private VPN -- and not on a shared or public one.
- **The app stores the endpoint address on the device**, in its private
  preferences. It stores no credentials because there are none to store.
- **It requests only `INTERNET` and `ACCESS_NETWORK_STATE`.** No storage, no
  location, no contacts.
- Cleartext traffic is enabled in the manifest because the protocol is cleartext;
  without it the app could not reach the endpoint at all.

## First run

Enter `host:port` (for example `192.168.1.20:3080`) in Settings and connect. The
app speaks plain HTTP and WebSocket on your LAN, so use it only on a network you
trust.

## What it does

- **Sessions** grouped by workspace, newest first, with the Host's archive set
  respected and archived sessions one toggle away. Each row shows its state: blue
  while a turn runs, orange when it wants an answer, green when a turn finished
  that you have not looked at yet.
- **Live transcript**: assistant replies as Markdown (headings, lists, tables,
  quotes, links, fenced code with syntax highlighting), tool calls as cards that
  fold in their results, background-job notices as cards, and a follow-the-newest
  behaviour that only follows while you are at the bottom.
- **The plan and the output**: `todo/write` renders as a checklist, and the files a
  turn delivers open in a preview — a Markdown deliverable laid out as a document
  the way a transcript message is, anything else as selectable source, and images
  full screen with pinch-to-zoom. A delivery is read from both places the harness
  offers: a reply's own references (`![fig](<out/fig1.png>)` is drawn in place,
  `[report](<out/report.md#L24-L30>)` opens on a tap, and the referenced lines are
  named in the sheet) and a `present` call's cards.
- **Control**: send prompts, cancel a running turn, switch model and reasoning
  effort, run `/compact`, answer approvals and questions the Host asks, and create
  sessions either in a chosen workspace or in the default directory.
- **Resilience**: reconnects on its own, and a dropped connection does not take the
  app down with it.

## Screenshots

Both were taken against a throwaway demo session ("watering a houseplant"), and the
session list is redacted: what you see is the app's rendering, not anyone's work.

| sessions | conversation |
|---|---|
| ![session list](docs/screenshots/drawer.png) | ![conversation](docs/screenshots/conversation.png) |

## Requirements

Android 10 (API 29) or newer; built against API 36.

## Installing

Take the APK from [Releases](../../releases). It is signed with a release key; the
debug APK CI uploads as an artifact is signed differently, and the two cannot
update each other in place.

## Building

Open the project in Android Studio, or build from the command line with the Gradle
wrapper (`./gradlew assembleDebug`).

`tools/` holds a second, Gradle-free build path (`tools/build.sh` for debug,
`tools/build-release.sh` for a minified release, `tools/run-jvm-tests.sh` for the
tests). It exists because the machine this was developed on cannot run Gradle at
all, and it drives `aapt2`, `kotlinc`, `d8`/R8 and `apksigner` directly. You do not
need it if Gradle works for you — the notes in `tools/README.md` explain what it
does and what it caught.

CI (`.github/workflows/android.yml`) runs the tests with Gradle and builds both
APKs; tagging `v*` publishes the signed release.

## Icon

The letters **DSH**, set as a monogram: white ground, near-black letters, nothing
else. An earlier attempt drew a rounded phone outline with a terminal prompt inside
it — three ideas (frame, notch, glyph) in a space that fits one — and at 48px it read
as a smudge. A monogram survives the size because the letters *are* the shape.

| legacy | adaptive, circular mask | adaptive, rounded-square mask |
|---|---|---|
| ![legacy icon](docs/images/icon-legacy.png) | ![circular icon](docs/images/icon-circle.png) | ![rounded icon](docs/images/icon-squircle.png) |

Drawn from primitives by `tools/make-icon.py` — no SVG, no downloaded asset, no
vendor artwork — and committed under `app/src/main/res/mipmap-*`, so it is covered by
this project's MIT licence along with the rest of the code.

It is deliberately *not* DeepSeek's logo, wordmark or mascot, and it uses none of
their artwork: a third-party client wearing the official mark invites exactly the
confusion about who made it that this project wants to avoid. "DSH" here is an
abbreviation of the API it speaks, not a claim to the brand. If the vendor objects to
the monogram anyway, [open an issue](../../issues) and it will be replaced.

Regenerate `app/src/main/res/mipmap-*` with `python3 tools/make-icon.py`, or these
previews with `python3 tools/make-icon.py --export`. `--preview` prints both shapes as
ASCII (dark meaning ink), which is how the mark was checked without an image viewer —
and how the rejected outline was caught.

## Layout

| path | what |
|---|---|
| `app/src/main/java/.../Protocol.kt` | wire types and decoders |
| `app/src/main/java/.../DshClient.kt` | OkHttp transport: unary calls, mux streams, reconnect loop |
| `app/src/main/java/.../AppState.kt` | state holder, event → transcript reducer, actions |
| `app/src/main/java/.../MainActivity.kt` | Compose UI |
| `app/src/main/java/.../SimpleMarkdown.kt` | hand-written Markdown subset |
| `app/src/main/java/.../DeliverableView.kt` | which deliverable opens as a document, and what it shows |
| `app/src/main/java/.../CodeHighlight.kt` | hand-written syntax highlighting |
| `docs/event-coverage.md` | every session event type, and how each is rendered |
| `docs/design-notes.md` | why the app works the way it does, one note per change |
| `tools/README.md` | the Gradle-free build, the local test runner, protocol probing |

## Protocol notes

Seven things about this harness are easy to get wrong, and each cost a bug here:

- **Event shapes come from the wire, never from a guess.** `docs/event-coverage.md`
  records where each one was observed, and the decoder tests pin them with captured
  payloads.
- **A `user/message` is an envelope, not a person.** Only `source.kind == "user"` is
  human; `plugin`, `agent-message`, `subagent-settled`, `agent-instructions` and
  `skill-catalog` are all machine-produced, and rendering them as user text puts
  words in the reader's mouth.
- **The Host owns workspace grouping and the archive set**; the client renders what
  `workspace/follow` reports. Archiving is reversible as of 0.1.7
  (`workspace/unarchiveSession`, which `DshClient` can call but the drawer offers no
  button for yet); sessions can also be pinned. An archived session also may not run
  a turn until it is restored — the controller's archived-session gate — so a
  message sent to one is admitted and never answered.
- **`session/create` takes `workspaceId` or `cwd`, never both**, and only the
  workspace route makes the new session a member of that group.
- **An argument's name is part of the contract, and names get renamed.** The
  gateway matches a call's fields against the endpoint descriptor before running
  it — an argument the descriptor does not declare is a refusal, not something
  ignored. `workspaceFiles/readBytes`'s byte window was `range` in 0.1.5 and is
  `options` in 0.1.7, which is what broke image preview on the phone.
- **A delivery is text in the reply, not a card.** The harness tells the model to
  hand over a file by *linking* it — `![Description](<path/to/figure.png>)`,
  `[Description](<path/to/file.md>)`, the destination relative to the session's
  working directory or absolute, `#L24` / `#L24-L30` for a few lines — and
  `present` only when a separate card adds something. A client that renders links
  as dead text therefore shows a turn that delivered nothing: figures arrive as
  literal `![fig1](out/fig1.png)`, and the path is the only thing on screen. Both
  spellings resolve the same way here, images included, through
  `workspaceFiles/read` and `workspaceFiles/readBytes`.
- **Bytes do not travel inside the JSON.** A result field holding binary comes back
  as a part of a `multipart/form-data` response: the value keeps the field with
  `null` in it, and an `attachments` entry beside the result says which part fills
  it (`[{path:["data"],codec:"bytes",part:"bytes-0"}]`). Reading only the JSON gets
  a null; reading the body as JSON gets nothing at all.
- **Live state arrives as projections, not as bespoke tables.** `session/control`
  answers a baseline of projection bags and then one `projection` frame per changed
  key; there is no `queues` or `jobs` map, and no `queue` or `jobs` frame, since
  0.1.7. Pending input is a session's `inbox` projection (`next-turn` waits for a
  turn, `next-step` is being folded into the running one — the list *is* the
  placement), and the job roster has its own `job/list` stream.

## License

[MIT](LICENSE) covers everything in this repository, including the launcher icon,
which is drawn here rather than taken from anyone. It is an unofficial client: not
affiliated with, endorsed by, or supported by DeepSeek. See [Icon](#icon).
