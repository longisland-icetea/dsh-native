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
| `assistant/message` | markdown bubble | the reply itself |
| `tool/call` | tool card with arguments | the one line that makes a tool-heavy turn scannable |
| `tool/result` | folded into its call | a separate row doubles every tool's height |
| `todo/write` | todo card | the plan is the most useful thing to re-read mid-turn |
| `deliverables/presented` | deliverables card, each row opening a preview | the whole point of a turn is what it produced |
| `model/selection` | model chip | answers "which model is this" without opening settings |
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
| `assistant/attempt` stream | see above |

## Observed, but with no branch of its own

Emitted by the harness, seen in the census, and unhandled by `toItem`: each one
becomes an activity row naming its own type. That is the fallback working as
designed — it exists so an event this build does not know stays *visible* instead
of being silently skipped, and it is how the delivery watermark was caught — but
it is not a decision anyone made per type, and for `system/message` the row drags
400 characters of system prompt into the conversation with it.

| type | rows today | what the row says |
|---|---|---|
| `system/message` | one per session, more on a re-seeded one | its type name, then 400 characters of the system prompt |
| `web/deepseek-search-llm-request` | one per web search | its type name |
| `llm/retry`, `llm/retry-started` | one per retry attempt | its type name; one sampled session held 28 of each |
| `workspace/changes` | one per turn that wrote files | its type name |
| `subagent/catalog`, `subagent/descriptor`, `subagent/model-selection-policy` | one or two per session | its type name |
| `developer/message` | rare | its type name |
| `goal/change`, `agent-preset/selected` | rare | its type name |

Whether each becomes a note, a card, or nothing is still open — hiding the list
wholesale is exactly what `EventVisibilityTest` pins *against*, because an
unclassified type is also how a newly shipped feature gets noticed at all.

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
