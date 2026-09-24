# The connection is not permanent

Every piece of Host state this app shows is either **read** (a unary call at a
moment we choose) or **streamed** (a delta that arrives when the Host feels like
it). A stream is not a guarantee: it ends, it reconnects, and the Host keeps
changing while it is gone. Bugs of the form "the screen was stale" or "a row
would not go away" have all been the same mistake — treating a stream as if it
were a permanent connection, or treating a snapshot as if it were a delta.

This file is the rulebook. It exists because the same bug was fixed four separate
times, once per stream, before the pattern was recognised.

## The five mirrors

| What the app shows | Where it comes from | What a *snapshot* is | What a *delta* is |
|---|---|---|---|
| Session list | `session/list` (read) + `api-session/*` (streamed) | the whole list, replaces | a patch to one row |
| Workspace + archive set | `workspace/follow` | the whole set, replaces | upsert / removed / archived |
| Queues, usage, jobs | `session/control` | `baseline`, replaces the queues | one session's queue, one projection |
| Conversation | `session/follow` | the newest window, merged by seq | one durable event |
| Pending Host calls | `$events` | the Host replays what is unanswered | one waterfall, one cancel |

## The rules

1. **A snapshot replaces. A delta patches.** Applying a snapshot with `+` leaves
   rows that no longer exist — that is how a claimed steer stayed in the dock
   forever, and how a session that had just been used stayed hidden behind a
   `blank` flag nobody re-sent.
2. **A delta cannot create.** It carries no title, no workspace, no `blank`. An
   id the state does not have means the state is missing rows, and the only
   repair is to re-read (`rereadList`).
3. **Every stream is opened with the same policy** (`asHostStream`): resubscribe
   on a clean end as well as on a failure, and log both. `retryWhen` alone does
   not see a clean end, and the workspace stream died silently that way.
4. **A new socket generation re-reads everything** (`resync`), because the Host
   does not replay what a stream missed: `api-session/status` is an emit, and a
   turn that started while the socket was down exists only in the list.
5. **A read has to repair the copy the reader is looking at, not only the
   drawer.** One fact can be held twice, and then fixing one copy is not fixing
   the bug. `running` is held on the session row *and* on the open conversation,
   and `api-session/status` is the only thing that ever pushes it down: a turn
   that ends while the socket is down left the drawer's dot correct (the list is
   re-read) and the composer's button stuck on **Stop** (nothing re-read it). So
   `refreshSessions` adopts the list into the open conversation through
   `runningFromList` — which never invents a `false` for a session the list says
   nothing about, because a session created moments ago has a row the read may
   not include yet and guessing there would take Stop away mid-turn.
6. **A read is older than the moment it lands, so deltas that arrived while it
   was out outrank it.** This is rule 1 with the arrow reversed: a response
   describes the Host as of when it was served, so a turn that ended during the
   round trip is simply absent from it. Adopting it wholesale re-creates rule 5's
   bug in a narrower window. The statuses seen during the read are replayed over
   the response (`statusesDuringRead`, the reference client's `listMutations`) —
   and the two rules compose: the *newest* status wins, so a turn that starts and
   ends inside one slow read still ends up correct.
7. **Do not claim more than the evidence supports.** The per-message rows say
   "waiting", "not delivered" or "sent — the Host no longer lists it" depending
   on what the frames actually show: a splice that carried `outcome: "canceled"`
   is proof; an inbox that no longer lists a message is proof only while the
   snapshot window still reaches back past the moment it was admitted.
8. **Anything the Host replays must be de-duplicated.** Reconnecting re-delivers
   unanswered waterfalls, so the same question arrives again; the app re-opens
   `$events` on every reconnect, foreground and Refresh, so it must replace by
   `eventId` rather than append. What does *not* come back in the replay is
   settled elsewhere and is dropped.

## What pins each rule

| Rule | Pinned by |
|---|---|
| 1 | `DisconnectedStateTest.a_baseline_clears_a_queue_row_the_host_no_longer_has`, `the_fold_memory_survives_from_one_frame_to_the_next` |
| 2 | `SteerDisplayTest.a_session_used_elsewhere_stops_being_blank`, the re-read branch in `applySessionDelta` |
| 3 | `tools/live-harness.sh`: "a reconnect re-reads every mirror"; `MuxStreamTest` for the transport itself |
| 4 | `tools/live-harness.sh`: "a turn it was not listening for is visible afterwards" |
| 5 | `ComposerRunningTest` (a captured turn, both streams, replayed with the closing emit dropped); `tools/live-harness.sh`: "a turn that ended off-screen gives the Send button back" |
| 6 | `ComposerRunningTest.a_status_that_arrived_during_the_read_outranks_the_response` |
| 7 | `SteerDisplayTest`: the window cases (`covers the admission` / `moved past` / `carries the logged message`) |
| 8 | `DisconnectedStateTest.a_replayed_waterfall_does_not_become_a_second_card`, `a_card_the_host_does_not_hand_back_is_dropped` |

`tools/live-harness.sh` runs the app's real client and state machine against a
live Host and reads the same state the UI draws from, so every rule above is
checked on real frames rather than on the shape we imagine them to have.
