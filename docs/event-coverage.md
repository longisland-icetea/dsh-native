# Event coverage

Every `SessionEventMap` member this harness can persist, from the generated
registry at
`@deepseek-ai/dsh-session/lib/types/known-event-types.js`, cross-checked against a
live census of five followed sessions (564 records, 23 types observed). Event
names outside that set come from out-of-repo plugins and are typed by their
`source.plugin`.

Column meanings:
- **render** — what the app does with it today.
- **why** — the reason for that choice, so a later change does not have to guess.

## Rendered

| type | render | why |
|---|---|---|
| `user/message` | bubble, or notice card when `source.kind == "plugin"` | the harness talks about its own work; a job result is machinery, not a person |
| `assistant/message` | markdown bubble | the reply itself |
| `tool/call` | tool card with arguments | the one line that makes a tool-heavy turn scannable |
| `tool/result` | folded into its call | a separate row doubles every tool's height |
| `todo/write` | todo card | the plan is the most useful thing to re-read mid-turn |
| `deliverables/presented` | deliverables card | the whole point of a turn is what it produced |
| `model/selection` | model chip | answers "which model is this" without opening settings |
| `turn/end` | note when it carries a reason | a failed turn otherwise ends silently |
| `compaction/*` | compaction boundary note | the transcript has no other marker that history was summarized away |

## Hidden on purpose

| type | why |
|---|---|
| `turn/start`, `step/start`, `step/end` | hundreds per turn against tens of messages; bounds are implied by the rows between them |
| `request/header`, `request/context` | host bookkeeping; the token counts duplicate what the chip shows |
| `agent/inbox/spliced` | subagent plumbing |
| `session/end-seed` | an empty marker |
| `assistant/attempt` | raw stream chunks; the message it becomes is rendered anyway. Its `usage` is worth revisiting as a token meter |
| `permission/preset`, `sandbox/mode`, `approval/policy` | session configuration, not conversation; belongs in a settings surface |
| `session/title`, `session/title-llm-request` | the title is already in the drawer and the top bar |
| `session-log-deepseek/*` | unrelated delivery subsystem |
| `assistant/attempt` stream | see above |

## Not emitted by this harness, or out of scope

`agent-preset/selected`, `approval/asked`, `approval/decided`,
`command/run`, `command/done`, `feedback/*`, `goal/change`, `hook/invoked`,
`hook/result`, `llm/retry`, `llm/retry-started`, `plan/mode`,
`schedule/change`, `subagent/*`, `team/*`, `tool-workflow/*`,
`tool/ptc-dispatch*`, `web/deepseek-search-llm-request`,
`compaction/prune`.

Approvals and questions reach the client as **Remote Event waterfalls** on
`$events`, not as transcript rows, so their absence here is expected — see the
pending-interaction path instead.

## Tests

`app/src/test/java/.../EventDecodeTest.kt` pins the decoders for this table,
using payloads copied from a live capture. `SimpleMarkdownTest` does the same for
the rendering of assistant text. `./tools/run-jvm-tests.sh` runs both locally and
Gradle runs them in CI.

A decoder test written from an invented payload is how the first `turn/end` bug
survived review: the fixture claimed a completed turn carried
`reason.kind == "error"`. Copy the JSON.

## Re-checking

Both sources are cheap to regenerate:

- vocabulary: the registry file above (regenerated upstream, verified fresh by
  their `doc-sync` job);
- observed shapes: `/tmp/census/live.mjs` follows a spread of sessions over the
  real WS protocol and writes type counts plus one raw sample per shape.
