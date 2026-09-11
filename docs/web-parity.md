# What the web client has that this app does not

An audit of the desktop web client's surface, compared against this app. Written
after a question asked while the app was closed never reached it, which turned out
to be one instance of a broader question: which parts of the harness does this
client actually cover?

Method: the web client ships as 76 `dsh-client-*` packages under
`@deepseek-ai/dsh`. Their `lib/types/**/*.d.ts` are readable and their
`lib/client.js` bundles keep identifier names, `//#region` source markers and
locale dictionaries, so the UI inventory below is quoted from the client itself
rather than guessed. Every claim about this app was checked against its source;
where an entry says "not rendered" it means no code path produces it.

This is a gap list, not a plan. Nothing here is scheduled.

## Aligned

Checked, and not gaps:

- Session list grouped by workspace, newest first, archived set from the Host,
  unread and running marks.
- Transcript: user and assistant text, hand-written Markdown (headings, lists,
  quotes, fenced code with highlighting, tables), tool call and result rows,
  todo panel, delivered-files card with preview, notices for machine-produced
  `user/message` traffic, turn outcomes.
- Live session state over `api-session/status` / `activity` / `added` / `removed`,
  and the archive frame.
- Model and reasoning-effort picker, `/compact`, cancel a running turn.
- Approvals: allow once / reject, with the asker's reason.
- Questions: the full one-question-at-a-time pager, per-question skip, custom
  text, plan-review classification, validation, and the exact answer payload.
- Deliverable preview, image zoom, jump-to-newest, Load older, transport log,
  new-session button, reconnect and resubscribe.
- Token usage, context pressure with its breakdown, the pending-message queue
  with steer/edit/remove, and the `/` command menu built from `commands/list`.

## High impact

Things a phone user hits, in the order they are likely to matter.

### 1. Reasoning is thrown away

The web renders each reasoning block as a collapsible "Thinking" disclosure with
a live tail line while streaming (`ReasoningRow`, `message.think`,
`data-variant="think"`). This app's block parser keeps only `type == "text"`, so
the model's reasoning is not hidden — it never enters the transcript. On a phone
this is the largest amount of content the client discards.

The web also renders a `system/message` as a disclosure ("System prompt" /
"System prompt update") that expands to the exact model-facing text. The app
parses those events for the session title and nothing else, so a system-prompt
update is invisible in the conversation.

### 2. Token usage and context pressure

**Done.** A context ring sits beside the model chip, fed by `contextPressure` and
opening a breakdown of conversation / tool definitions / system prompt with the
session's totals. Each turn's cost appears under the turn it belongs to, from
`assistant/message.usage`, and opens the per-turn dialog (cached input, cache
write, output, reasoning share, cache-hit percentage). Nothing is polled: the
`session/control` stream carries the projections, and the session list seeds them.

### 3. The message queue

**Done.** A queue dock above the composer lists what is waiting, from the Host's
own `session/control` snapshots, with per-row **steer**, **edit** and **remove**
over `session/updateQueue`. Editing reuses the composer rather than growing an
input inside the row, and a steered message appears as a `STEERING` row until the
Host retires it into the log — the acknowledgement that it arrived. Sending while
the agent is busy steers by default; a long press on send offers "queue for next
turn".

### 4. Slash commands

**Done.** Typing `/` at the start of the composer opens a menu built from the
Host's `commands/list` — six built-ins in this deployment, and any the deployment
adds. A command that declares no input (`compact`, `export`) runs on pick; one
that declares a hint (`permission`, `plan`, `goal`, `feedback`) inserts its name
and waits for its argument, with the hint shown beside it.

### 5. No attachments: no images and no files

The web has a full attachment path: paperclip, drag-and-drop, paste, per-file
upload progress with retry, image limits and error copy, an attachment rail, and
`fileUploads/upload` on the Host. The app has none of it — no way to send a
screenshot or a file to the agent. For a phone this is the most conspicuous
absence: a phone camera roll is the natural source of input, and
`session/prompt` already accepts `{type:'image'|'file'}` content parts.

### 6. Workspace and session management

The web offers rename session (`session/rename`), fork session at the last
completed turn (`session/fork`), rename and delete a workspace
(`workspace/rename`, `workspace/delete`), add a workspace through a directory
picker, and drag-reorder sessions and workspaces. The app can create and archive
a session and switch between existing groups, and does not call `session/rename`,
`session/fork`, `session/search` or any `workspace/*` method except
`archiveSession` and `follow`.

### 7. Session search

`session/search` merges title/workspace matches with ranked content matches, with
snippets and a truncation notice. With 80+ sessions in this deployment the drawer
is a scroll; search is the feature that makes the list usable again.

## Medium

- **`llm/retry` status.** The web shows "Waiting to retry model request",
  "Retrying model request ({retry}/{maximum}) · {seconds}s" and a failure reason.
  The app does not handle `llm/retry` at all, so a retrying request looks like a
  stall.
- **Plan review presentation.** The app infers a plan review from a heuristic
  (one question, has `detail`, at most two options, not multi-select). The web
  requires `intent.kind === 'plan-review'`, the approve label from `intent.approve`,
  and renders a dedicated card: **Approve / Refuse / Chat about it**, where the
  plan is Markdown and "Chat about it" cancels and returns to the composer. The
  app's `QuestionItem` does not model `intent` at all, so it can both miss a real
  plan review and claim one that is not.
- **Question card chrome.** The web can collapse/expand the card and dismiss the
  whole request ("Dismiss all questions", which rejects the waterfall). The app
  has neither; the card can only be answered or skipped question by question. The
  web also renders `question.detail` as Markdown and strips a trailing
  "(Recommended)" suffix for display only, marking it with a badge. The app
  renders `detail` as plain text truncated at 600 characters.
- **Settings.** The web's Settings has General / Models / Plugins / Agent presets,
  over a Host-driven schema: language (English/中文), appearance and font size,
  the busy-Enter preference, provider and model editing with credentials, plugin
  configuration, and agent-preset management. The app's Settings is a connection
  dialog only. Two of these are phone-relevant: **language**, because the app's
  UI is English while the harness ships a Chinese locale, and **font size**.
- **Jobs, schedule, subagents, workflow.** The web has a jobs popover
  (`job_list`-fed, with live/running counts and elapsed time), a reminder catalog,
  a subagent tree with per-row tokens and duration that navigates into child
  sessions, a workflow-run panel, and a goal bar with pause/resume/edit/clear.
  The app renders a background job as a plain tool card and has none of the rest.
- **Access mode.** The web switches the permission preset per session
  (`/permission`, with a risk-confirmation dialog for Full access) and sets the
  default for new sessions. The app shows `permission/preset` as a note but cannot
  change it.
- **Transcript display mode.** The web can render completed turns "Normal" or
  "Compact" (`settings.transcript`). The app has one rendering; its per-row
  collapse is the coarse equivalent.
- **Transcript affordances.** Per-message copy, "branch into a new conversation"
  from the last completed turn, jump-to-turn on a turn rail, turn process
  disclosure ("{n} tool calls · {n} messages"), and a Normal/Compact display mode.
  The app has load-older, jump-to-newest and collapse, and none of these.
- **Tool card bodies.** The web renders per-tool cards from structured metadata
  that the Host persists **in the session log**: `tool/result.meta` carries
  `{diffs}` for `write`/`edit` (`oldText`/`newText` hunks), `{path, lines, lang}`
  for `read`, `{files: [{path, matches}]}` for `grep`, `{paths}` for `glob`,
  `{sources, answer}` for `web_search`, and an exit code for `bash`. The app
  never reads `meta`, so diffs and structured results are discarded and every tool
  is a label plus output text. The data is already in the events the app stores —
  this is a rendering gap, not a protocol one.
- **`assistant/attempt` is unhandled.** The web records a committed-no-message
  attempt; the app renders nothing for it, so a step that produced only tool
  calls can look like it did nothing.

## Tradeoffs, not gaps

Worth stating so they are not mistaken for omissions:

- **Steering.** The app now sends `mode: "steer"` for every prompt. The web
  decides per gesture from a user preference (`BusyEnterBehavior`, default
  `queue`, with Cmd/Ctrl-Enter using the other). One default is the right shape
  for a phone; a preference is the right shape for a desktop.
- **Right sidebar and layout.** Files tab, document preview tabs, split panes,
  floating panes, drag-resizable columns, Open-In-App. These are desktop
  affordances; the app's full-screen conversation with a drawer is the mobile
  equivalent, and its own file preview covers the common case.
- **Markdown.** The app's renderer is a deliberate hand-written subset (no
  footnotes, no math, no task-list checkboxes). Rendered LaTeX on a phone is of
  limited value; footnotes and checkboxes are cheap to add if they are missed.
- **Settings schema.** The web's settings UI is generated from a Host schema, so
  third-party namespaces appear without client changes. The app has no settings
  renderer, which is why every settings surface is a separate feature here.
- **Fixed row kinds, and a silent fallback.** The web transcript is assembled from
  ~14 keyed node kinds plus a keyed per-tool card registry, so a tool or content
  block it does not know still gets a generic card. This app has a closed set of
  row types and skips an unrecognised content block without a trace — which is
  how the reasoning blocks went missing without anyone noticing. A generic
  "something arrived that this build does not render" row would have made the gap
  visible the first time it happened.

## Remaining, in the order worth doing

Reasoning blocks are now the largest single loss: the block parser keeps only
`type == "text"`, so the model's thinking never reaches the transcript, and
`system/message` is parsed for the session title alone. After that: attachments
(no way to send a screenshot), `tool/result.meta` (diffs and structured results
are in the events already), then session and workspace management (rename, fork,
search, delete).
