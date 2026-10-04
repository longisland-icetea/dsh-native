# Event coverage

Every `SessionEventMap` member this harness can persist, from the generated
registry at
`@deepseek-ai/dsh-session/lib/types/known-event-types.js`, cross-checked against a
live census of forty followed sessions (83,526 records, 39 event types observed;
the fortieth record type is the log's own `session` header, which is not
delivered to a follower). Event names outside that set come from out-of-repo
plugins and are typed by their `source.plugin`.

Column meanings:
- **render** — what the app does with it today.
- **why** — the reason for that choice, so a later change does not have to guess.

## Rendered

| type | render | why |
|---|---|---|
| `user/message` | bubble when `source.kind == "user"`, otherwise a notice card (or hidden) | the harness talks about its own work; a job result is machinery, not a person |
| `assistant/message` | markdown bubble, with the reply's own file references openable (figures drawn in place) | the reply itself — and, since the harness asks for a delivery as a Markdown link or image, the delivery surface too |
| `tool/call` | tool card with arguments | the one line that makes a tool-heavy turn scannable |
| `tool/result` | folded into its call | a separate row doubles every tool's height |
| `todo/write` | todo card | the plan is the most useful thing to re-read mid-turn |
| `deliverables/presented` | deliverables card, each row opening a preview | the whole point of a turn is what it produced; `present` is now the *secondary* delivery surface — a reply's own `![…](<path>)` / `[…](<path>)` references are the primary one, and both open the same sheet |
| `model/selection` | model chip | answers "which model is this" without opening settings |
| `agent-preset/selected` | one note: `agent preset: <name>` | the preset decides the tools, skills and instructions a session runs with, which is why two sessions behave differently on one Host; nothing else on screen says which one this is |
| `goal/change` | one note: what the goal just did, with its objective truncated | the goal lives outside the conversation and this client has no goal bar (see `web-parity.md`), so this line is the only surface a reader can watch it on |
| `llm/retry` | one note: `retrying (attempt n of m) after CODE: message` | a provider failure is otherwise invisible — the reply stops arriving and nothing says it paused. One row per attempt, not two: `llm/retry-started` is the same fact |
| `turn/end` | note when it carries a reason | a failed turn otherwise ends silently |
| `compaction/*` | compaction boundary note | the transcript has no other marker that history was summarized away |

## `user/message` source kinds

A `user/message` is the envelope for everything the harness injects, not just for
what a person typed. The discriminator is `source.kind`, and only `user` is a
person — an earlier classifier tested for `plugin`, which sent relayed subagent
messages and settle notices to the human bubble.

| kind | form | render |
|---|---|---|
| `user` | — | the human's bubble |
| `plugin` | `notice` | notice card, titled by plugin (`tool-jobs`, `model-selection`, …) |
| `plugin` | `snapshot` | notice card; the runtime context snapshot |
| `agent-message` | `relay` | notice card titled "agent message", with `senderSessionId` |
| `subagent-settled` | `notice` | notice card titled "subagent", `summary` as the body, with sender |
| `agent-instructions` | `instructions` | hidden: injected input, repeats on every change |
| `skill-catalog` | `catalog` | hidden: capability listing, not conversation |

## Hidden on purpose

| type | why |
|---|---|
| `turn/start`, `step/start`, `step/end` | hundreds per turn against tens of messages; bounds are implied by the rows between them |
| `request/header`, `request/context` | host bookkeeping; the token counts duplicate what the chip shows |
| `agent/inbox/spliced` | not a row: it is the durable delta the pending-inbox mirror is folded from, and the dock's rows come from the projection it maintains |
| `session/end-seed` | an empty marker |
| `assistant/attempt` | raw stream chunks; the message it becomes is rendered anyway. Its `usage` is worth revisiting as a token meter |
| `permission/preset`, `sandbox/mode`, `approval/policy` | session configuration, not conversation; belongs in a settings surface |
| `session/title`, `session/title-llm-request` | the title is already in the drawer and the top bar |
| `session-log-deepseek/*` | the transport reporting on itself: the upload acknowledges its own delivery once per **step**, so any row here is a row under every message. Hidden as a namespace, not as the one name caught leaking in a 2026-09 update — the whole package only ever logs this kind of bookkeeping. Pinned by `EventVisibilityTest` |
| `system/message` | the system prompt, addressed to the model. The fallback drew it as a row with the first 400 characters of that prompt hanging off it |
| `developer/message` | the harness telling the model its own tool list changed (`tool-removal: ralph`); the tool surface has a settings-shaped home, not a transcript one |
| `web/deepseek-search-llm-request` | the request body of the harness's own web search; the search itself is already a tool card |
| `workspace/changes` | a `{turn}` marker that a turn touched the workspace; which files changed is in the tool cards and the deliverables card, where the paths are |
| `subagent/catalog`, `subagent/descriptor`, `subagent/model-selection-policy` | the subagent roster's own bookkeeping; the dock draws that roster from its own stream, with labels, tokens and a jump into the child |
| `llm/retry-started` | the second half of a retry: `llm/retry` above it already said an attempt failed and another is coming. Two events, one fact, one row |
| `assistant/attempt` stream | see above |

## Observed and unclassified

None, as of the census above. Every type the harness was seen to write now has a
branch of its own: rendered, hidden, or — for `llm/retry`, `goal/change` and
`agent-preset/selected` — a note. A row census over nine real logs, folding each
one through `toItem` and tallying rows per type, leaves nothing falling through
the fallback: the six logs that carried delivery watermarks held 1477 of them
between them, and now draw no rows at all.

That is a moment, not a guarantee. The fallback stays loud on purpose: an
out-of-repo plugin's event is not conversation either, but silently dropping an
event type this build has never seen is how a whole feature goes missing, and
`EventVisibilityTest` pins that half of the contract. When the next upstream
update lands, the type census will show a new name immediately; the row census is
what says whether a reader sees it.

Never observed in the census, so out of scope until one shows up:
`approval/asked`, `approval/decided`, `feedback/*`, `hook/invoked`,
`hook/result`, `plan/mode`, `schedule/change`, `team/*`, `tool-workflow/*`,
`tool/ptc-dispatch*`.

Approvals and questions reach the client as **Remote Event waterfalls** on
`$events`, not as transcript rows, so their absence here is expected — see the
pending-interaction path instead.

## Tests

`app/src/test/java/.../EventDecodeTest.kt` pins the decoders for this table,
using payloads copied from a live capture. `EventVisibilityTest` pins the two
ends of the fallback: a machinery namespace stays silent, and a type this build
does not recognise still names itself. `SimpleMarkdownTest` does the same for
the rendering of assistant text, and `WorkspaceFilePageTest` +
`DeliverableViewTest` for the page a deliverable read returns and the document it
becomes. `./tools/run-jvm-tests.sh` runs all of them locally and Gradle runs them
in CI.

A decoder test written from an invented payload is how the first `turn/end` bug
survived review: the fixture claimed a completed turn carried
`reason.kind == "error"`. Copy the JSON.

## Re-checking

Both sources are cheap to regenerate:

- vocabulary: the registry file above (regenerated upstream, verified fresh by
  their `doc-sync` job);
- observed shapes: follow a spread of sessions over the real WS protocol, or read
  the logs directly — `session.v*.jsonl.zstd` under
  `~/.dsh/sessions/<project>/<session>/` is a zstd stream of the same records, so
  a type census needs no Host at all.

A type census is not a *row* census, and only the second one is what a reader
sees. After an upstream update the question is not "which types exist" but "which
of them reach the screen", and the answer is a fold: run a captured log through
`toItem` and tally rows per type. That is what turned up the delivery watermark
(39 events, 39 rows, one per step) and, in the same pass, the table of unhandled
types above.
