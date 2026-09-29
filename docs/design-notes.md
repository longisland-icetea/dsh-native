# Design notes

Why this app is built the way it is, kept from the development history that
was squashed before publication. Each entry is one change and the problem it
solved; the code comments carry the same reasoning next to the code itself.

## Prototype: protocol-level native client for DSH over LAN

Speaks the harness Remote protocol directly instead of embedding the web GUI:

- unary calls as POST /api/<ns>/<method> with the {type,rpcId,method,payload}
  envelope, named args, and the SessionAddress union
- streams over one /api/remote.mux socket, reopened with capped backoff
- session list, follow with live deltas, older-history paging, prompt, cancel
- Compose transcript with tool activity rows, fenced-code highlighting and
  inline markdown; no WebView and no markdown/highlight dependency

Protocol shapes and byte sizes were probed against a live 0.1.5-rc.1 host and
are recorded in README.md.

## Drop two unused imports

## Fix Kotlin compilation errors found by CI

- OkHttp url extension: a fully qualified call cannot resolve
  HttpUrl.Companion.toHttpUrl, so import it and call the extension form
- MainActivity: a delegated property cannot be smart-cast, so read
  state.endpoint into a local before the null check

## Use a reachable Gradle distribution mirror; document the local-build blocker

The wrapper downloaded from services.gradle.org timed out from this network
and the wrapper timeout (10s) was too short, so point it at a mirror and raise
the timeout.

Also record why local builds cannot run in this WSL2 VM: with
networkingMode=VirtioProxy a ServerSocket bound to 127.0.0.1 refuses a
connection from the same machine, so Gradle's client never reaches its own
daemon. CI is the build path.

## Decode event payloads by content-block type instead of guessing

Probing a live host showed three assumptions in the first draft were wrong:

- assistant messages mix 'reasoning', 'text' and 'tool-call' blocks in one
  content array, so joining every block displayed the model's private
  chain-of-thought as if it were the reply. Only 'text' is rendered now, for
  finished messages and for live deltas alike.
- tool/call carries data.name and data.arguments; the label read a non-existent
  field and rendered every activity row as '?'. The arguments now appear as the
  row detail, and tool/result reads data.message.
- the message wrapper is shared by the transcript and by tool results, so one
  typed decoder handles both.

## Decode session/list by hand; add regression tests from a live capture

The app connected but listed no sessions. A generated serializer was the wrong
tool: projections.values carries heterogeneous server-owned state (goal is
null-or-string, permissions and modelSelection are nested objects, turnOutline
is an array), so decoding threw on the first unknown shape, the throw was
swallowed, and the list came back empty. SessionListCodec now reads only the
fields the UI renders and ignores the rest.

Tests use a response captured from a live host rather than hand-written JSON,
so the fixture keeps the heterogeneous fields that caused the failure, and CI
now runs testDebugUnitTest before assembling.

## Qualify the chunkText call site across objects

FollowCodec.decodeChunk called chunkText unqualified, but it lives on
EventPayload; the reference did not resolve and the build failed.

## Fix the title-fallback assertion and lock the live stream table

The assertion expected twelve characters where takeLast(8) yields eight; the
code was right and the test was wrong.

openStream now shares a lock with the socket callbacks: the stream table is a
plain HashMap mutated from an OkHttp thread (onMessage) and a coroutine
(openStream/awaitClose), and the stream is registered before the open frame is
sent so a snapshot cannot arrive with nowhere to land.

## Surface session-list failures instead of swallowing them

An empty session list was indistinguishable from a failed one, which turned a
one-line diagnosis into guesswork. session/list now reports the decoded body
size, a failure names the first bytes it could not parse, and the drawer shows
the error, the session count, the body size, and a short log tail.

The wire behaviour was verified independently with a forwarding proxy: the app
sends a correct envelope and the host answers 200 with gzip, so the remaining
suspect is client-side decoding and it now has to say so out loud.

## Report the leading body bytes in session/list diagnostics

A decode failure now prints the first 16 bytes in hex, so 'compressed bytes
that were never decoded' (1f 8b) is distinguishable from 'an envelope this
client mis-parses' without another round trip. The forwarding proxy proved the
host answers 200 with 55 items, so the fault is client-side and the message has
to say which client-side step failed.

## Gunzip a response body that is still compressed

OkHttp normally decodes gzip transparently, but the failure mode this client
kept hitting is precisely 'compressed bytes reached the JSON parser'. Rather
than depend on that behaviour, sniff the two-byte gzip magic and decompress in
the client; a correctly decoded body passes straight through. This closes the
class of failure independently of which layer stripped the encoding.

## Run every network call and stream collection on Dispatchers.IO

The app reported NetworkOnMainThreadException for session/list: every call was
launched from lifecycleScope, which dispatches on the main thread, and Android
forbids socket I/O there. The exception was one of the failures the previous
roundCatching swallowed, which is why the list merely looked empty.

All HTTP calls, the mux connection loop, stream collection, prompt, cancel and
older-page paging now run on Dispatchers.IO.

## Record why local Gradle builds cannot run in this WSL2 VM

networkingMode=VirtioProxy breaks a JVM's cross-process loopback socket: a
ServerSocket bound to 127.0.0.1 refuses a connection from another Java process
(0/10 measured, 10/10 with -Djava.net.preferIPv4Stack=true on both JVMs). Gradle
always forks a daemon and the client reaches it over that socket, so the
handshake cannot succeed; the flag cannot be delivered to the daemon either,
because Gradle filters java.net.* out of org.gradle.jvmargs and the daemon does
not inherit JAVA_TOOL_OPTIONS. Builds run in CI.

## Add a Gradle-free build that works locally

Gradle cannot run in this WSL2 VM (networkingMode=VirtioProxy breaks the JVM
loopback socket its client uses to reach its own daemon), so tools/build.sh
drives the same tools Gradle orchestrates, directly:

  tools/fetch-deps.py   resolves the dependency graph from POMs and writes a
                        deduplicated classpath
  aapt2 / kotlinc / d8 / zipalign / apksigner

Findings that took iterations and are now encoded in the scripts:
- aapt2 compile --dir silently emits an empty archive here; the per-file form works
- aapt2 needs an explicit package attribute, which Gradle injects from
- the compose/serialization compiler plugins must come from the kotlinc
  distribution; the -embeddable artifacts fail on shaded IntelliJ classes
- classpath.txt needs a trailing newline or android.jar is concatenated onto the
  previous entry and every android.* import fails
- dependency resolution needs version arbitration (d8 rejects duplicate classes)
  and must drop collection-ktx/core-ktx/runtime-livedata for the same reason
- dropping every -jvm artifact is wrong: kotlinx-coroutines-android is a thin
  wrapper whose implementation lives in -jvm
- extracted aar classes must be rebuilt per run, otherwise d8 sees stale versions

The APK it produces is 13 MB against 54 MB from the Gradle debug build.

## Use the system zip for APK packaging now that it is installed

Kept the archive step on the standard tool rather than Python's zipfile; the
-dir and manifest findings that the scripts encode are unaffected.

## Merge library resources for the Gradle-free build

The first Gradle-free APK installed but died immediately: it carried no library
resources, so the Material theme could not resolve.

Two wrong turns are worth recording, because both looked right:

- Passing compiled library archives to  treats them as overlays
  ('resource X does not override an existing resource'), and  has the same
  meaning. Application and library resources have to reach the table as one
  compilation.
- Renaming staged files to avoid collisions renames the resources themselves:
  aapt2 takes a resource's name from its file name, so
   became a drawable called
   and the link failed with 'resource
  drawable/notification_action_background not found'.

tools/merge-res.py now renames nothing: values*.xml documents are merged
element-wise per configuration (7472 entries across 88 configurations here) and
every other type is copied verbatim, since two libraries declaring one resource
name is an upstream conflict rather than something to paper over.

## Fully qualify the launcher activity and mark the debug build debuggable

The hand-built manifest declared android:name=".MainActivity" while the APK
Gradle produces for the same source carries the qualified name; the launch
component is the one place a wrong name cannot be recovered from.

The Gradle-free build also has no manifest merger, so anything library manifests
contribute is absent by construction; the qualified name is stated explicitly
rather than relying on relative-name expansion.

## Generate library R classes and restore core-ktx for the Gradle-free build

Verified on a device: the hand-built APK launches, connects to a live harness,
lists all 55 sessions with titles and byte counts, and opens a conversation. Two
runtime failures were found by running it rather than reasoning about it:

- NoClassDefFoundError androidx.customview.poolingcontainer.R$id. The aars in
  this graph ship no R.class in classes.jar at all, because R classes are a
  build-time product; AGP generates one per library package and this build
  generated only the app's. tools/gen-r.py now writes one per package from
  aapt2's symbol dump. --extra-packages cannot do it in a single pass (it takes
  one package name; a comma-separated list produces a directory named 'a,b').
- NoClassDefFoundError androidx.core.view.ViewKt. core-ktx had been dropped as a
  duplicate-class source, which was wrong: unlike collection-ktx it carries
  unique Kotlin extensions that core does not.

Also: the launcher activity is fully qualified (the hand-built manifest had
'.MainActivity'), and debuggable is set.

Tooling notes recorded in the scripts: --stable-ids is an input file, not an
output; --output-text-symbols takes a file path, not a directory.

## Receive Host waterfalls and present approval requests

Approvals and user questions are agent-scoped Remote Event waterfalls: the
Host calls the client on the $events stream and the listener's *return value* is
the answer, sent back through the $events/result RPC. A client that never opens
that stream cannot be asked anything, so the agent simply waits -- which is the
one gap that makes a phone useless in practice.

- HostEvent models ready/emit/waterfall/cancel; the ready frame carries the
  clientId every answer must name
- DshClient opens $events and answers or delegates a waterfall
- an approval/request is presented as a card above the composer with the two
  decisions the Host accepts (allowed-once, rejected)
- unsupported waterfalls are delegated so the Host falls through instead of
  hanging
- stream failures retry instead of surfacing as an unhandled exception: closing
  a callbackFlow with a cause while nothing collects killed the process
- every record() line also goes to logcat; the drawer log sits past the session
  list and is unreachable with dozens of sessions

Verified on device: mux connected, 55 sessions listed, 'events ready (client
03e54a7d)' logged, no crash.

## Group the session list by workspace, newest first, with archive

session/list carries no workspace or archive field: the desktop derives the
workspace from cwd and keeps its own view state in browser storage. This does
the same derivation in the client:

- visible = origin != subagent AND not archived AND (not blank OR current),
  matching the desktop's own sessionVisible rule
- groups come from cwd, labelled with its last path segment, ordered by the
  newest member and collapsed per group
- rows show running/subagent state; a long press archives, and an archived
  session stays reachable through the show-archived toggle

Archive and collapse are per device, like the desktop's own view state; sharing
them across clients would need server-side state in a plugin.

Verified on device: groups render with member counts, collapse hides the rows,
and the list shows 55 sessions across cwd groups with no crash.

## Take workspace grouping and the archive set from the Host

The desktop does not keep this state locally: the Host owns a Workspace registry
(workspaces with canonical membership and order, plus one archivedSessionIds
set) persisted under the DSH home, and the desktop reads it through
workspace/follow. Deriving groups from cwd locally, with a device-local archive
set, meant the phone and the desktop disagreed about what was archived and
labelled groups by folder name instead of the workspace title.

- workspace/follow drives grouping: baseline (items + archived set) then ordered
  upserts; a session the Host has not placed lands in a trailing Other group
  rather than disappearing
- archive goes through workspace/archiveSession, whose reply is the complete
  resulting set
- session/list now retries while the harness is unreachable instead of settling
  on an empty list

Two crashes fixed on the way, both found by running it:
- OkHttp ran the mux WebSocket's reader on the thread that created it, so a
  transport error arrived on the main thread as an unhandled SocketException.
  The client now owns a dispatcher, and every stream collection is wrapped so a
  carrier-thread failure retries instead of killing the process.
- the archive action passed the same argument twice after the signature change.

Verified on device: 'workspaces: 5 groups, 22 archived', groups show Host titles
(ips-SC, BSE_hBN, R_MoTe2, AC_intrinsic) with a Show-archived toggle over the
Host's set, and no crash.

## Present user-questions batches, not just approvals

Approvals and questions arrive as waterfalls on the same stream but carry
different payloads: an approval's return value is one decision string, while a
question's is a structured batch {answers:[{id, selected, custom?}]} keyed by
the caller's question ids. Only approvals were handled, and every other
waterfall was delegated, so an agent that asked a question waited forever.

- QuestionItem/QuestionAnswer model the batch; plan-review is recognised as the
  single-question form of the same request
- options answer on tap, multi-select and option-less questions accept free
  text, and one Submit answers the whole batch
- the delegated-on-unsupported path still exists, but a question no longer
  falls into it

Also fixes a comment that broke the build: a doc comment contained the sequence
star-slash inside a command name, which closed the comment early and produced
about fifty misleading cascade errors from files that were fine.

## Fix user-question parsing: decode the request object, not its array

The waterfall's request IS the QuestionRequest; feeding its  array
to that serializer fails, so every question parsed to an empty list and was
delegated as unsupported. Verified against a live request:
{"questions":[{"id":"color","question":"Which color do you choose?",
"header":"Choose a color","options":[{"label":"Red"},{"label":"Blue"}]}]}
now logs 'pending question'.

Two things learned by driving a real question through a hidden session:

- the Host re-delivers an unanswered waterfall to the next client generation,
  so a question survives the app being killed and reconnects on its own
- a pending interaction is only visible while its own session is open, so the
  top bar now shows a waiting count and jumps to the session that needs an
  answer

Also adds tools/README.md, including the note that source-patching scripts must
raise on a missing anchor: str.replace silently no-ops, which twice built
unchanged code while reporting success.

## Quiet the transcript, fix older paging and the model catalog call

Four reports from real use, each traced to its cause:

- Noise: a sampled turn held 315 rows of step boundaries, inbox splices and
  tool results against 41 assistant messages. Those event types now render
  nothing, and an assistant message with no text block (28 of 69) is a
  reasoning-only step rather than a reply, so it renders nothing either.
- Background-job results read as user messages: they are user/message events
  with source.kind == plugin (10 of 11 in the sample). Only a human prompt is
  rendered as one now; a plugin notice becomes a subdued note.
- Load older scrolled to the bottom and appeared not to load: the auto-scroll
  keyed on the item count, so prepending a page triggered it. It now follows the
  newest item's key, which prepending does not change.
- session/modelCatalog declares no parameters; sending a request field was
  rejected with gateway/arguments-invalid. Verified against the host: empty args
  returns three providers, a request field returns that exact error.

Also drops the tool card's expand animation for a flat card: the call, its
identifying argument and its output are all visible, with no motion.

## Run the mux off the main thread and hide the last of the noise

The intermittent crash on reconnection: DshClient launched its mux loop on the
scope the caller passed, which is a lifecycleScope, so the WebSocket connect and
its reader loop ran on the main thread. Android forbids socket I/O there, and a
dropped connection surfaced as a SocketException that killed the process. An
OkHttp dispatcher was set earlier, but that only moves the callbacks; the loop
itself has to leave the main thread. Verified with a thread log: the loop now
reports DefaultDispatcher-worker-2.

Reported from use, all verified against real data:
- turn/start now joins turn/end in the hidden set.
- tool results must still produce a row internally: hiding them in the mapper
  left pairToolResults with nothing to fold, so every tool showed 'running'
  forever. They are folded, then dropped.
- ToolCall gained a status, because a null result means 'no output' while the
  absence of a result means 'still running' -- the two were conflated.
- The tool card toggles on tap again, with no animation and no truncation when
  open.
- The session count now comes from one visibleSessions predicate shared with the
  grouping, so subagent children and archived sessions are not counted.
- Show-archived moved into settings (default off), the drawer header lost its
  settings button, and the counter no longer prints a byte size.
- The build stamps a versionCode from the epoch, so two builds are
  distinguishable on a device.

## Document that installing goes over adb

## Audit every session event type and render the ones that were missing

A census of the live protocol (23 types observed, 52 in the harness registry)
turned up events the transcript was dropping: todo lists, presented
deliverables, model selections, compaction boundaries, and non-clean turn ends.
docs/event-coverage.md records what each type does now and why, so the next
change does not have to re-derive it.

Rendering
- todo/write and deliverables/presented become cards; model and session-setting
  events become one-line notes; a turn that was aborted, interrupted, blocked or
  cut off at the token ceiling now says so instead of ending silently.
- Deliverables open a preview sheet. Read is text-first and falls back to the
  Host's byte endpoint, so a PNG figure is drawn inline rather than refused.
- Model picker for provider/model/reasoning effort, plus a /compact entry.
- Markdown gains tables, headings, quotes, rules and links; block splitting and
  emphasis pairing fixed (two paragraphs merged, and `2 * 3` lost its operator).

Fixes found while verifying
- turn/end was classified by a reason kind that does not exist; the decoder test
  now uses a captured payload because the first fixture claimed a completed turn
  carried an error.
- An IOException on a socket thread killed the process; installSocketCrashGuard
  absorbs that class only.
- Tool cards no longer ripple, and the model chip no longer opens the drawer.

Tooling
- tools/run-jvm-tests.sh runs the unit tests without Gradle, using a JUnit stub
  and a reflection runner, so a broken assertion does not wait for CI.

## Zoom a figure full screen and make the transport log optional

The preview sheet fit a figure to the sheet width, which on a phone leaves a
1817x1596 plot unreadable. Tapping the image (or the new Zoom button) opens it
full screen with pinch-to-zoom, drag-to-pan, and double-tap to toggle between fit
and 3x. Scale is clamped to [1, 8] and pan to the scaled bounds, so the figure
cannot be shrunk into empty space or flung out of reach.

The bounds live in pure `clampScale`/`clampOffset` helpers because a multi-touch
gesture cannot be injected over adb (no root, and SELinux blocks writes to the
touch device), so the arithmetic is unit tested while the gesture is confirmed by
hand. One test caught a real defect: `coerceIn(-0.0f, 0.0f)` normalises -0.0f to
+0.0f, which made the clamped offset unequal to the input offset at fit scale.

The drawer's transport log now sits behind a "Show transport log in the session
list" switch in settings, off by default and remembered. It is a debugging aid,
and it was pushing the session list off the drawer whenever it appeared.

tools/run-jvm-tests.sh passes `-Xfriend-paths` when compiling tests, the same
mechanism Gradle uses to let a test reach an internal helper.

## Add new-session buttons to the session drawer

"New" in the drawer header creates a session in the process default directory;
"＋" on a Workspace group creates one in that workspace. The group button is the
only way to choose a directory without a directory picker, since the Host accepts
`workspaceId` or `cwd` but rejects both together, and only the workspace route
makes the new session a member of that group.

The Host returns the id, so the app opens the session immediately and the follow
stream supplies the empty transcript; the list refreshes behind it. A new session
also triggers the catalog load, and the chip and picker fall back to the catalog's
default selection — a session nobody has switched a model in runs the Host
default, which the app had no way to name before.

## Take the harness's own name and icon

The launcher label is now "DSH", the short name the harness's own web manifest
uses, and the icon is its mark: white on the brand blue, drawn from the packaged
favicon.svg.

tools/make-icons.py generates the rasters. There is no SVG rasteriser in this VM
and the Gradle-free build must not acquire one, so the script flattens the path
itself (M/C/Z only, failing loudly on anything else) and supersamples with
Pillow. Two things it gets right that a naive first pass did not: each subpath is
rasterised on its own and unioned, because feeding all four to one polygon() call
paints a bridge between them and loses the tail; and the adaptive foreground
keeps the mark inside the 66dp safe zone, so no launcher mask clips it.

Below API 26 the raster ic_launcher.png is used; from 26 the adaptive icon takes
over.

## Draw the icon black on white, with its interior cut out

The first version unioned the mark's subpaths, which filled the whole silhouette
solid and lost every internal contour. The source is an outline with three
subpaths lying inside it -- an eye and two fin notches -- so it needs even-odd
fill, and the holes are counted by parity rather than by XORing antialiased
pieces (XOR of two half-covered pixels gives a covered pixel, which stippled the
holes instead of cutting them cleanly).

Colour is black on white, and the adaptive icon gains a monochrome layer for the
API 33+ themed-icon path. That layer lives in `mipmap-anydpi-v33` rather than
with the 26+ icon: `monochrome` is an API 33 attribute.

## Keep the keyboard down on open, and give tables a real grid

Two fixes.

Hot start: the message field no longer takes focus when a conversation is
restored, and the activity declares `stateAlwaysHidden`. Opening a session used to
be able to raise the keyboard over the transcript the reader came for. Verified by
rotating with the keyboard up: the activity is recreated and comes back with no
IME.

Tables: the previous proportional layout drew an empty frame. Inside
`horizontalScroll` the width constraint is infinite, and `Row` skips weighing
under infinite constraints, so every column measured zero -- the older fixed-width
version hid this. The grid now measures its viewport with `BoxWithConstraints` and
lays out against a bounded width, and each row uses `IntrinsicSize.Min` so the
vertical rules have a height to fill. Cells are ruled, rows are striped, and the
header gets a heavier rule.

Column shares are water-filled rather than a plain normalisation: with raw shares
one long column took 83% and squeezed the rest to slivers, since the per-cell cap
only bounds the input to the normalisation. The arithmetic is a pure function with
its own test, because a device check cannot say which part of a layout is wrong.

## Lay tables out as equal columns that fit the viewport

The previous version sized columns from content and let the grid grow past the
viewport, expecting the reader to scroll. On a phone that showed an empty frame
until they did.

Equal columns are what was asked for and also the right default on a narrow
screen: a table is read down its columns, so equal widths let the eye find a
column without re-measuring it on each row, and the whole grid fits. Cells wrap
inside their share; a long token overflows its own cell rather than widening the
column, because widening one column is exactly what pushed the grid off-screen.

`equalColumnWidths` returns the widths *and* the grid width, because computing
those separately is how they drifted apart. The padding is inside each column's
share, so the grid is the viewport by construction, and the readability cap can
only ever shrink a column -- a narrow viewport always wins.

This also drops the text measurer and the min-content walk: with equal columns
there is nothing to measure.

## Parse table cells the way markdown does, and confine them to their column

A census of 56 real assistant messages found 13% of their tables parsing into
ragged rows: 6 header cells against 8 body cells, 3 against 5. Two causes, both in
`splitRow`:

  - `\|` is a literal pipe, which is how LaTeX absolute values arrive
    (`\left| x \right|`), and splitting on it cut one cell into two;
  - a pipe inside `code` is literal text, not a separator.

A row with extra cells lays its content out shifted by one column, which reads as
"the columns are not aligned" rather than as a parsing bug, and no amount of
column arithmetic can fix that. Rows are now also padded or trimmed to the
header's column count, which is the authority on how wide the table is.

The renderer had its own alignment bug: the cell's `Text` was unbounded, so a long
cell took its intrinsic width and overflowed the fixed-width column. `weight(1f)`
confines it, which is what makes wrapping happen inside the column.

Checked against the real corpus rather than by eye: a probe that runs the app's own
parser over 40 captured messages reports 60 tables and 0 ragged, down from 8.

## Classify messages by source kind, not by the plugin field

A `user/message` is the envelope the harness uses for everything it injects, not
just for what a person typed. Detection tested `source.kind == "plugin"`, so three
other kinds fell through to the human bubble -- most visibly a relayed subagent
message, whose report appeared as if the user had written it.

Found by auditing every session's records rather than by reading the UI: the
corpus has kinds `agent-message` (relay), `subagent-settled` (notice),
`agent-instructions` (instructions), and `skill-catalog` (catalog) besides
`plugin` and `user`. The rule is now "kind is present and is not `user`".

Relayed agent messages and settle notices become notice cards, titled by kind and
showing which subagent sent them; instructions updates and the skill catalog are
hidden, being injected inputs that repeat on every change. Checked against the
whole corpus: 105 user/messages, 71 human and 34 machinery, none misfiled.

## Anchor the viewport when paging, and move stop into the composer

Loading an older page prepended items and left the list at the oldest line of the
new page, so the reader lost their place on every load. The item at the top of the
viewport is now remembered by key before the page is requested and scrolled back
to afterwards, at the same offset. List indices are not message indices -- the
paging button, the error line, and the streaming bubble each occupy a slot -- so
the leading items are counted when resolving the anchor. Measured on a device: the
anchor row sits at y=614 before and after, a shift of zero pixels.

The stop button leaves the top bar and the composer's single button becomes the
turn's control, as on the web client: stop while a turn runs with an empty box,
send as soon as anything is typed or the turn is over. Typing therefore replaces
stop with send, so a draft cannot be thrown away by a stray tap. Verified in all
three states on a device, and the rule is a pure function with its own test.

## Keep the transcript still while paging, and make the drawer live

Three fixes, all found by testing on a session that was actively streaming.

The transcript followed the newest message unconditionally, and `liveText` changes
on every streamed token, so a page loaded with "Load older" was pulled out from
under the reader within a second. Following is now gated on the reader being at
the bottom (`canScrollForward`), read live inside the effect: a `following` flag was
captured stale by the flow, and re-running the effect when it changed animated the
list back to the newest row the moment the reader scrolled away. Verified by
pinning the viewport and watching it for 30s of streaming: it holds.

The paging anchor never engaged, because the "Load older" button is itself the
first list item: `firstVisibleItemIndex - leadingItems` was -1, so no anchor row
was recorded and the page landed with the reader at its top. It now falls back to
the first message row, and re-resolves until the row is found instead of giving up
on the first attempt -- a live transcript is transiently empty when a follow stream
rebuilds it, which is exactly when the one-shot version did nothing.

Session state was never updated after the initial list fetch, so a session's
running dot and its position in the drawer were stale until a manual refresh. The
`$events` stream already carried `api-session/status`, `activity`, `added`, and
`removed`, and the collector dropped every one of them in an `else -> Unit`.
They are now decoded into `SessionDelta` and applied to the list.

The composer also gains an explicit focus request on tap, so tapping the field
raises the keyboard even when the field already holds focus -- after dismissing
the IME with Back there is no focus change, and the keyboard would not come back.

## Add a jump-to-newest button

A new message no longer drags the viewport down when the reader has scrolled away,
which is right but leaves no way back except scrolling by hand. The button appears
exactly when following stops and returns to the newest message, which also resumes
following.

The "am I at the bottom" test is the last item being laid out and reaching the
viewport, not `canScrollForward`: the bottom content padding counts as scrollable
extent, so `canScrollForward` stays true with the last message on screen and the
button was pinned on permanently.

Opening a conversation now also lands on the newest message. It previously started
at the oldest row of the window, which is why the button showed on a conversation
nobody had scrolled, and meant a freshly opened session did not show what was just
said.

## Make the jump-to-newest control a circular arrow

A labelled pill took a strip of the transcript for a one-gesture affordance; a bare
circle with a down arrow costs a fraction of the view and needs no label.

The reserved bottom padding is also gone. It existed so the pill would not cover
the last message, but a content padding is part of the scrollable extent, so it
showed as a band of blank space under the last row even while the button was
hidden. The button now sits above the content by its own 14dp inset, so nothing is
reserved when it is not shown.

## Treat "near the end" as being at the end

The strict test -- the last row is laid out and reaches the bottom edge -- broke
following the moment a message was appended: the last row moves down by its own
height, which is larger than the tolerance, so following switched itself off
exactly when it was needed. Requiring the last visible row to be within two rows of
the end keeps following alive at the bottom while still stopping it as soon as the
reader scrolls away.

Verified by opening a conversation and watching for 25 seconds while messages kept
arriving: the last row stays pinned inside the viewport and the jump arrow stays
hidden.

## Do not draw a session list before the archive set arrives

Cold start showed every session for a second or two and then dropped to the real
list, with the count disagreeing in between. The cause is two independent calls:
`session/list` returns the sessions, `workspace/follow` returns which of them are
archived, and the list won the race by ~1.6s. Until the archive set arrived it was
empty, so every archived session counted as live.

The drawer now waits for the archive set before drawing rows, and says
"Loading sessions…" while it does. The wait is bounded at three seconds: if that
call is slow or failing, showing every session is better than a drawer that never
finishes loading. Connecting to a different Host also clears the previous
grouping, archive set, and sessions, so the old Host's choices cannot hide rows on
the new one.

## Say "loading" on the empty screen too, instead of a count it will retract

The drawer was gated on the archive set but the main screen was not, so a cold
start flashed "34 sessions" before settling on 7: the same unfiltered count, on the
other surface. It now shows "Loading sessions…" while the archive set is on its way,
and the release condition matches the drawer's -- the stream ending, not a timer.

Measured on a device across a cold start: the screen shows only
"Loading sessions…" and then "7 sessions", with no intermediate number.

## Add a local release build, and rewrite the README for users

`tools/build-release.sh` runs R8 over the debug build's classes so a minified APK
can be installed and exercised before a tag is pushed. It earned its place
immediately: the first minified build crashed on `MainActivity.onCreate` because
R8 removed `kotlinx-coroutines-android`'s `AndroidDispatcherFactory`, which is
reached through `META-INF/services` rather than by reference. Keeping the class was
not enough -- packaging only `*.dex` also left the service files out of the APK.
Gradle merges those keeps from the libraries' AARs; R8 invoked directly does not,
so `app/proguard-rules.pro` now states them. The minified build runs: sessions,
grouping, transcript and composer all verified on a device.

The README now leads with what a user needs -- a `dsh-lan-access` instance, and why
`dsh-mobile` is deliberately not required -- and drops the development narrative.
The R8 notes live in the tools README and the rule file, where the next build
change will read them.

## Publish from the release lane without depending on the debug lane

## Stop hardcoding the debug mode in the manifest

`android:debuggable="true"` was in the source manifest from when the hand-rolled
build was the only build there was. Lint fails the release for it -- correctly,
since it would publish an app carrying debug information -- and that is what the
first v0.1.0 tag hit.

The attribute now comes from the build: `tools/build.sh` injects it into a
generated manifest for the debug variant (which is what Gradle does per variant),
honours `DEBUGGABLE=false`, and `tools/build-release.sh` uses that so the release
APK it packages from the same resources carries no debug flag. The release script
also verifies the packaged manifest and refuses to publish if it is debuggable.

## Let the release lane create the release

The workflow declared repository-wide read-only permissions, so publishing failed
with "Resource not accessible by integration" after the signed APK had already
built and verified. The write permission is scoped to the release job, which is the
only one that needs it.

## Accept the punctuation a Chinese keyboard types, and fix the default port

Two bugs the release smoke test found while configuring an endpoint by hand.

A CJK keyboard emits full-width punctuation, so `192.168.255.5:3080` is stored as
`192。168.255.5：3080` and the app answered "not configured" with the text visibly
present in the box. The parser now folds full-width forms onto ASCII.

A bare host resolved to port 80: prepending `http://` made OkHttp report its
default for the scheme, and the fallback only applied when the port was absent from
the *parsed* URL -- which it never is. The typed address now decides: an explicit
port is honoured, anything else gets the harness's 3080.

## Fold the full-width block, and stop guessing at anything else

The normalization was growing a rule per mark (`。`, `：`, `．`, `｡`, `︓`, dashes,
spaces) to compensate for keyboards, and the next candidate was a special case for
`；` because OkHttp reads a semicolon as part of the host. That is the wrong shape:
the fold exists so an address copied off a phone is not silently given a
punctuation the reader never typed, and anything beyond folding is the reader's typo
to see and fix.

So it is now the full-width ASCII block in one rule, with a test recording the
honest consequence: an address carrying `；` keeps the semicolon in its host and
fails at DNS.

## Fold the full-width block in one rule

The explicit mark list (。 ： ． ｡ ︓ and the dashes) needed an entry per keyboard
for no benefit: the full-width ASCII block is one rule and covers all of them. What
is left is the fold, and a test records the honest consequence of a stray mark --
an address carrying `；` keeps it in the host and fails at DNS, which is the
reader's typo to see.

## Fold the CJK punctuation block too, which is where the dot lives

The fold covered `U+FF01..U+FF5E`, the full-width ASCII block, and missed that `。`
is `U+3002` -- CJK punctuation, a different block. So an address typed on a Chinese
IME kept its ideographic full stops and failed at DNS, which is exactly the case
the fold was added for.

Found by reading the compiled bytecode after a CI syntax error: `javap` showed the
comparison as `65281..65374`, and 12290 (`。`) is nowhere near it. The earlier
device test that appeared to pass had used keyevents, which produce ASCII dots.

Also fixes the stray brace that broke the previous tag's build, and the tests now
cover the CJK block directly.

## Drop the punctuation folding

Compensating for what a keyboard emits is not this app's job: the address is typed
by hand, and a wrong character is a typo the reader can see in the box. The fold
only ever covered one of the two blocks anyway -- `。` is U+3002, CJK punctuation,
not U+FF01..U+FF5E -- which is how it survived a test that looked like it passed.

What stays is the one thing that was never about the input: a bare host resolved to
port 80 because OkHttp reports its scheme default, so the fallback for a missing
port never applied. An address without a port now gets the harness's 3080.

## Handle the archive frame, land the follow at the bottom, show session state

Three fixes, plus the test rule that the first of them earned.

The workspace stream's `archived` frame was dropped: the collector handled
`baseline` and `upsert` only, so a session archived on another client (or the
desktop) stayed in this drawer until a manual refresh -- which is why session
state looked like it never updated. `archived` and `removed` are handled now, and
verified live: archiving a session from the Host side removed its row and moved the
header count within seconds, with no refresh.

Following the newest message scrolled to the *start* of the last row, because
`scrollToItem` aligns an item's top with the viewport's top. On a tall row that
reads as jumping backwards; it now passes the viewport height as the offset so the
row lands at the end.

Session rows carry their state: blue while a turn runs, orange when the session
holds an unanswered approval or question, green once it is done. The middle state
is derived here -- it is the one that blocks an agent, so it is worth interrupting
for -- and the rule is a pure function with tests.

Also: the archive menu no longer offers "Unarchive". It was never wired to
anything that could unarchive (`archiveSession` only archives, and DSH has no
unarchive API), so the label was a promise the app could not keep.

tools/README.md now says to test with a throwaway session. The archive-frame test
archived a session that was in use, and archiving is one-way; that must not happen
again.

## Show the done mark only for a session that finished unread

A green mark on every idle session said nothing, because almost every session is
idle almost all of the time. It now appears only when a turn finished in a session
the reader was not looking at, and it clears when they open that session or when a
new turn starts -- the same "nothing to say about this row" state the web sidebar
leaves a read session in.

The Host reports `running` and nothing about read state, and `updatedAt` turns out
to be identical to `lastPromptAt` for every session (checked across all 81), so
this is the client's own definition: the set is maintained from `api-session/status`
transitions, which is the only signal that says "something finished here". The rule
is a pure function with six tests, including that a session on screen is never
marked unread and that a session with nothing open still is.

## Keep the unread marks across a restart

A turn that finished while the app was closed is still something the reader has not
seen, and the Host tracks no read state, so the set is stored with the rest of the
per-device session view state.

## Make the follow actually observe updates

The follow effect was driven by `snapshotFlow` over `newestKey` and `liveText`, and
emitted exactly once -- at composition -- and never again. Both are plain values
read out of `Conversation`, which is a stable type, so a snapshot observer registers
no dependency on them; reading the `state` delegate from the flow did not observe
updates either. The result was a follow that never followed, which is what the
reader saw.

It is now an effect keyed on the newest key, the live text length and the
at-bottom flag, so a change restarts it by construction rather than relying on
snapshot dependency tracking. It also uses `scrollToItem` instead of
`animateScrollToItem`: the animation was cancelled and restarted by the next token
and so never arrived, which looks the same as not following at all.

Measured on a device against a live turn: the effect fires once per arriving row and
re-anchors to the end each time (`total=79 first=69` then `total=80 first=72`).

## Refresh the session list when the drawer opens

The Host pushes session state only when it changes and never replays it, so a turn
that started or finished while this app was closed or offline leaves the list stale
until something asks again. Verified against the live stream: a session created
while a test client was listening arrived as `api-session/added`, and no event is
sent for a state that did not change -- so the pushes alone cannot be the whole
story.

Refreshing on open makes the drawer right at the moment it matters, and the two
`session/list` calls it produces are visible in the log (one from connecting, one
from opening the drawer).

## Prepare the tree for a public checkout

Three things a stranger would have hit, found by auditing for publication:

- The `session/list` fixture carried a real session's prompts and replies (its
  `turnOutline` holds the conversation text), along with the working directory,
  title and ids. It is scrubbed to neutral content while keeping every key and
  value type, because the shapes are what the fixture exists to pin -- the codec
  test still passes unchanged.
- `tools/make-icons.py` looked for the harness's favicon at one machine's absolute
  path. It now searches the usual install locations and takes `DSH_FAVICON` as an
  override, so regenerating icons works on another machine.
- `tools/__pycache__` had been committed; it is removed and ignored.

Also replaces a real home directory in a test assertion with an example path, and
records the scrub in the fixture's doc comment.

## Replace the vendor's mark with a monogram of our own

The icon had been the harness's own favicon, traced from its `favicon.svg`. That is
the vendor's brand mark, unlicensed for this use, and it made an unofficial client
look official -- the one impression a third-party client must not give. It is gone,
along with `tools/make-icons.py` and its SVG flattening, which existed only to
rasterise that file. Every byte under `mipmap-*` is now drawn here.

The first replacement was a rounded phone outline with a terminal prompt inside,
which was worse than what it replaced: three ideas (frame, notch, glyph) in a space
that fits one, illegible at 48px. The mark is now simply the letters **DSH**, which
survive the size because the letters are the shape. Two runs of the ASCII preview
settled it -- column ink profiles showing three separate clusters at 48px -- where
an image viewer was not available to judge by eye.

`tools/make-icon.py` sizes the letters to a fraction of the canvas width rather than
to a point size, because a nominal size covers a different fraction in every face.
The adaptive foreground is sized so the *text* stops at the 72dp safe-zone boundary;
sizing the layer by its height instead would let a wide monogram run past it, and the
square mask simulation confirmed nothing is clipped. `--export` writes the README's
previews from the same code that writes the icons, so the documented mark cannot
drift from the shipped one.

## Render a delivered Markdown file the way a message is rendered

The preview sheet drew every text deliverable as monospace source, on the
argument that "markdown rendering would hide the source the model wrote". That
argument is sound for a source file and wrong for a document, and the difference
is not cosmetic: a turn whose deliverable is a report was handing the reader
`## Findings` and `| a | b |` where the harness's own message path — and the web
client's document preview, which registers exactly `extensions: ["md",
"markdown"]` — hands them a heading and a table. Same file, same protocol, two
renderers.

The sheet now chooses by extension (`deliverableViewFor`), and a document goes
through `MarkdownBody`, which is the assistant bubble's rendering lifted into a
function so both paths cannot drift apart. Front matter is stripped first
(`deliverableDocumentText`): model-written reports routinely open with a YAML
block, and rendered as Markdown its `---` becomes a horizontal rule and its
fields become a paragraph of keys. Only a block that closes is stripped, because
a document that opens with an unclosed `---` is a document starting with a rule.

### The page cap was invisible, and that is what made it worth fixing

`workspaceFiles/read` does not return a file. It returns a *page*, cut at the
deployment's line and byte caps (5000 lines, 2 MiB by default), and it says so in
`eof`. The app read only `text` and drew it as the document — so a 7000-line
deliverable arrived as 5000 lines with no marker, and the reader had no way to
tell that from a file that ends there. Measured against a live Host: a 7000-line
file answers `lines: 5000, eof: false`.

The decoder (`WorkspaceFileCodec`) and the state now carry `eof` through to the
sheet, which marks a cut page. The `too-large` refusal — a file whose bytes are
one enormous line gets no page at all — gained copy that says so instead of
falling through to the wire's own error text.

Continuing the document would be the better answer, and the web's viewer does
exactly that: it accumulates pages and offers "Load more" while `eof` is false.
This app reads one page per tap. The paging is the next step rather than part of
this change, because it needs a page cursor in the sheet's state and a way to
report *which* page the reader is on; saying "this is not the whole file" is the
part that could not wait, since without it the reader cannot tell a cut page from
a short document at all.

Two smaller things came out of the same pass. The read result was decoded with an
unchecked cast, which would throw on a payload that is not an object; it is a
safe cast now, and a missing `text` key is refused rather than read as an empty
file — "empty file" is the wrong story for a result this client did not
understand. And `docs/web-parity.md`'s claim that the app's Markdown renderer has
"no math" was already stale: `MathBlockView` has been rendering KaTeX since the
formula work landed.

### What is tested where

The rules are pure and unit tested (26 cases across `DeliverableViewTest` and
`WorkspaceFilePageTest`), with the payloads captured from a live Host rather than
written by hand — the 7000-line capture is the one that found the bug. Three of
those tests failed on the first run and every one was real: `.md` used as a
*filename* was being read as an extension, a front-matter scan that did not stop
at a blank line deleted everything up to the document's next `---`, and the test
helper's own cast was hiding what the decoder does with a non-object payload.

The layout is not unit-testable, so `tools/live-harness.sh` gained four checks
that drive the real `DshClient` against a running Host: a Markdown deliverable
reads whole, lays out as a heading plus a table, a source deliverable stays
source, and a 6000-line file comes back marked truncated. All four pass against
the live Host. They run before the harness's turn, so a failure there does not
spend model quota.

## Give the Send button back when a turn ends off-screen

The composer's single button is Stop while a turn runs with an empty box and
Send otherwise, and "a turn is running" is one boolean on the open conversation.
The Host pushes that boolean on `api-session/status`, which is an **emit**: sent
once, on a change, and never replayed. Everything therefore depended on being
connected at the instant the turn ended — and a phone in a pocket, in a lift, or
on a router that blipped is not. The turn finishes, the emit goes nowhere, and
the button stays on Stop over an agent that is idle. Tapping it then calls
`session/cancel` on nothing, which looks like the app ignoring the tap.

The flag was being mirrored twice — onto the session row and onto the open
conversation — and only the row was ever repaired. `resync` re-reads
`session/list` on every new socket generation, so the drawer's running dot went
out correctly while the composer kept offering Stop: the same fact, read from two
places, one of them fixed. The repair is to adopt the list into the conversation
as well (`runningFromList`), because the list is the one source of this fact that
is *read* rather than pushed.

`runningFromList` deliberately does not answer `false` for a session the list
does not mention. Absence is not evidence: a session created a moment ago has a
row the last read may not include yet, and reading that silence as "not running"
would take Stop away mid-turn — replacing a button that sticks with one that
lies. The conversation's own value stands in that case.

Adopting a read introduces its own ordering hazard, and it is the same bug in a
narrower window: a `session/list` response describes the Host as of the moment it
was *served* and lands later than that, so a turn that ends during the round trip
is simply absent from it. Taking that response at face value would put the button
back on Stop. The statuses seen while the read is outstanding are therefore
replayed over the response (`statusesDuringRead`), newest last, so a turn that
starts and ends inside one slow read still ends up correct. The reference client
keeps the same record — `listMutations` in `dsh-api-session-controller`'s client,
replayed over its baseline — which is where the shape came from.

Reproduced and verified against a live Host before the fix was written. The
harness starts a turn, cuts the link for real through its proxy, waits for the
Host to finish the turn alone, and asserts *both* halves: the app was left
offering Stop, and after the reconnect the same flag comes back down. The second
assertion is the fix; the first is what makes it a regression test rather than a
restatement. A live capture of one whole turn backs the unit test — both streams
the app opens, in arrival order, so the offline replay drops the closing emit
exactly as the asleep phone did.

One unrelated check was fixed on the way. The harness waited for the transient
`Pending` echo of an unrecognised slash line, and once the reconnect changed when
that message arrived (in a snapshot rather than as a live event) the echo was
retired inside a single 200 ms poll. It now watches the row's whole life, as the
steer check above it already had to — the same lesson, applied the second time.

## Read a figure's bytes from beside the JSON, under the name the descriptor declares

Image preview broke the day the Host updated, and it took two changes in one
release to empty the sheet. `workspaceFiles/readBytes` had always spelled its byte
window `range`; 0.1.7 spells it `options`, and the gateway checks a call's fields
against the endpoint's descriptor *before* running it — so the read of a figure was
refused with `missing "options"; unexpected "range"` and the file was never opened.
The same release stopped putting the bytes in the JSON at all: the value now
carries `"data": null`, and an `attachments` entry beside the result names the part
of the response body that fills it (`[{path:["data"],codec:"bytes",
part:"bytes-0"}]`, captured from a live 0.1.7 Host). Either change alone was enough
to break the sheet; together they made "no data in result" the kindest thing the
old code could have said.

Both are now read the way the Host sends them. The window is named once, in
`byteReadArgs` next to `commands/execute`'s args, because this bug was a name and
not logic — the same shape of mistake as the missing `submittedAttachments` that
made every slash command fail. And a body whose content type is
`multipart/form-data` is split into its parts, with the `metadata` part decoded as
the very same `RpcResponse` envelope every other call answers with; from there the
only difference is that a call may come back carrying bytes as well as a value.
`RpcAnswer` keeps the two together and `bytesAt(["data"])` is how the byte read asks
for its window. The field is spelled once (`DATA_FIELD`) because two things on the
wire have to agree about it: the JSON placeholder and the attachment path.

Two decisions are worth their words. The split is OkHttp's `MultipartReader` rather
than a scan for the boundary: the boundary is the Host's to choose, the parts are
binary, and the PNG this was tested against holds a CRLF inside its own IHDR chunk —
precisely the sequence a hand-rolled scan mistakes for the start of the next part.
And `attachments` is decoded by hand rather than typed: an entry with an array index
in its path, or a codec this client has never heard of, has to be a skipped entry
rather than a failed call, which is the rule the other hand-written decoders follow.

### The Markdown preview was never broken, which is worth knowing rather than assuming

A phone shows one symptom — "the preview is empty" — but the two deliverables do not
travel the same way. A document goes through `workspaceFiles/read`, whose descriptor
did not change and whose answer is still JSON with `text` in it. A figure is the one
deliverable that takes *two* calls: `read` refuses it as `workspace-file/not-text` —
which is what routes the sheet to the byte endpoint at all — and only the second call
was broken. Measured against the live Host before the fix was written: the markdown
read returned the file, the figure read returned `not-text`, and the byte read
returned the refusal above. So this change touches no markdown code, and the
suspicion that both were down is recorded here as the thing a check settles rather
than an argument.

### What is tested where

The decode is unit tested in `WorkspaceFileBytesTest`, and its payload is the Host's
own answer: `app/src/test/resources/read-bytes-multipart.bin` is a captured
`readBytes` response, body and all, and the bytes inside it are compared against the
figure the test spells out in hex — so a parser that returned the right *length* of
the wrong bytes fails. Alongside it: the window's name, a JSON answer that carries no
bytes (which must not read as an empty file), attachments this client cannot place,
and a part named with and without quotes.

`tools/live-harness.sh` gained the two checks that would have caught this on the day
the Host updated: a figure is refused by the text read, and the figure's bytes come
back byte for byte. The harness already drove a markdown deliverable, a source
deliverable and the page cap — all still passing on the new Host — and that is
exactly why the break was invisible to it: nothing in it had ever asked for a figure.

### What this did not fix

One harness check is still red on the new Host, and it is a second, unrelated break
from the same update: a steer that never appears in the queue dock. `session/control`'s
baseline no longer carries `queues` or `jobs` at all — read from the live Host through
this app's own client, the baseline is `{projections}`, and a session's pending work now
lives in its `inbox` projection as `{next-turn, next-step}` — while
`withControlBaseline` still reads `snapshot["queues"]` and the frame decoder still waits
for `"queue"` and `"jobs"` frames. Usage and context pressure survive because they were
already read out of `projections`; the queue dock and the job list do not. That is its
own change, with its own captures of the `inbox` shape and of a splice, and it is not in
this one.

## Draw the dock from the inbox, and the job roster from its own stream

The second break the same update caused, and the one the image fix's own harness run
surfaced. Two mirrors went empty at once: the queue dock never showed a message again,
and the running-tasks button counted nothing. Both were reading a shape 0.1.7 had
deleted. `session/control`'s baseline used to be three tables -- `queues`, `jobs`, and
`projections` -- with `queue` and `jobs` frames patching the first two; it is now the
projections alone, plus one `projection` frame per changed key. Read from the live Host
through this app's own client, the baseline is `{projections}`, and the queue lives in
each session's own `inbox` projection as `{"next-turn": [...], "next-step": [...]}`.

### The list a message is in is its placement

0.1.5 carried a `placement` field on a queue snapshot (`queued`, `steering`,
`context`). 0.1.7 encodes the same fact structurally: `next-turn` is a message waiting
for a turn of its own, `next-step` is one being folded into the running turn, and
`steer` is the action that moves one from the first list to the second. So the dock's
rows are `next-step` first and `next-turn` after, because that is the order the Host
claims them in -- the row about to disappear is the one at the top.

The item is now the message itself rather than a wrapper: `id`, `content` blocks, and a
`source` whose `rpcId` is the prompt identity this client retires its own echo on. Three
consequences are worth naming. `preview` is gone, so a row's label *is* its text. A
message with no id is dropped rather than drawn, because every action the row offers
names that id. And the shape is decoded in one place: the same `inboxProjection` reads
the follow snapshot's bag, the control baseline's, and a `projection` frame's value, so
the transcript's inbox mirror and the dock cannot disagree about what is pending.

### The roster is a stream of its own, and "no jobs" is not "not a roster"

Jobs moved to `job/list`, scoped to a session and answering with the whole set that
session can see -- its own jobs plus every unowned one -- so a frame replaces the list.
It is opened for the conversation on screen, which is the only roster the UI draws, and
its first frame after a reconnect is already the truth.

One trap is worth recording because a test caught it: `job/follow`, which streams a
single job's output, shares the namespace. A frame of *that* stream decoded as "no
jobs" would empty the button, so a frame that is not a roster returns null and the
state is left alone; only a `rows` frame with an empty list is the removal.

### What is tested where

`PendingQueueTest` replays the Host's own frames (`steer-inbox.json`: one running turn,
one steer folded into it, one message queued behind it, captured over `session/control`)
through the real fold, and covers the shapes the wire did not show: a message with no
id, a row whose blocks this build cannot read, several text blocks in one message, and
the frames that must change nothing. `JobsTest`'s wiring half does the same for
`job-list.json`, including the non-roster frame. `DisconnectedStateTest` already owned
the "a snapshot replaces, it does not merge" rule; its baseline helper now spells the
projection shape, so the rule is still pinned against the wire that carries it.

The harness's dock-versus-Host check reads the Host's inbox the way the app does now,
which is what makes it a check on the mapping rather than on the app agreeing with
itself. It is the check that was red before this change, and the steer it drives -- a
message admitted mid-turn, seen as a steering row, then landed in the transcript -- is
the same journey a reader takes.

### An archived session does not run turns any more

Found while making the harness's last red check pass, and worth writing down
because it is a behaviour, not a bug in either side. 0.1.7 added an
archived-session gate to the controller: an archived session -- or a subagent
descendant of one -- **may not run a model step until it is restored**. A prompt to
one is admitted and the proposed step then ends as `blocked` without a request, so
a client that sends to an archived session and waits for a turn waits forever. The
gate lifts for the whole lineage on `workspace/unarchiveSession`, which the same
release added.

Two consequences here. The harness had been archiving a session and then prompting
it -- the offline-turn check, which is why that check began failing the moment the
run got far enough to reach it -- so it now restores the session before prompting,
and the unarchive itself became a check: the app adopts the Host's archive set
wholesale, so a removal has to reach the drawer the same way an addition does.
`DshClient` gained `unarchiveSession` beside `archiveSession` for it. The app's
own drawer still has no unarchive button, and a reader who opens an archived
session and types will see their message admitted and then nothing happen; that is
a UI guard for another change, recorded here because the Host's rule is what makes
it necessary rather than a mystery.
