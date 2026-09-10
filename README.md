# dsh-native

A minimal native Android client for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness),
talking the harness Remote protocol directly over the LAN.

## Why this exists

The web GUI is a large client: one boot pulls ~5.25 MiB of gzipped JavaScript
(59 plugin modules, a vendor bundle, 40 lazy language chunks). On a phone that
is both a transfer and a parse cost on every cold start, and shrinking it means
either patching DSH's built-in packages or shipping a full GUI in an app — both
of which are hard to keep alive across upgrades.

This client takes the other path: it speaks the *protocol* (which is small) and
renders a fraction of the surface (which is the actual work). Measured reconnect
cost is one `session/follow` snapshot plus deltas, not a page repaint.

## Requirements

- A harness reachable on the LAN, with [`dsh-lan-access`](https://github.com/longisland-icetea/dsh-lan-access)
  enabled **and `noAuth: true`** in its settings. That removes DSH's browser
  session gate (launch token → signed cookie), which is what lets a non-browser
  client use plain `POST /api/...` and `ws://.../api/remote.mux` with no pairing
  and no TLS.
- Plain HTTP on the LAN. The app refuses any non-`http`/`ws` endpoint, so do not
  expose the port beyond a network you trust — with `noAuth` set, anything that
  can reach the port can drive the agent.

## The protocol, as measured against 0.1.5-rc.1

Documented here because it is not published anywhere and everything in
`Protocol.kt` / `DshClient.kt` comes from probing a live host.

### Unary calls — plain HTTP

```
POST /api/<namespace>/<method>
{"type":"client-request","rpcId":"<uuid>","method":"session/list","payload":{"args":{...}}}

→ {"type":"server-response","rpcId":"<uuid>",
   "result":{"ok":true,"value":{...}}}          // or {"ok":false,"error":{code,message}}
```

`args` holds **named** parameters, and the names are not uniform:

| method | args |
|---|---|
| `session/list` | `{"_request":{}}` — the parameter really is `_request` |
| `session/follow` | `{"request":{address,maxMessages,assistantStream}}` |
| `session/page` | `{"request":{address,throughSeq,beforeSeq,maxMessages}}` |
| `session/prompt` | `{"request":{requestId,sessionId,mode,content}}` |
| `session/cancel` | `{"request":{sessionId}}` |

`SessionAddress` is a discriminated union, **not** `{sessionId,cwd}` — the host
rejects the flat form with `gateway/input-invalid`:

```json
{"kind":"session","sessionId":"session-…"}
{"kind":"subagent","parentSessionId":"…","childSessionId":"…","mode":"one-shot"}
```

### Streams — one multiplexed WebSocket

```
ws://<host>:3080/api/remote.mux

→ {"type":"open","streamId":"<uuid>","endpoint":"session/follow","payload":{"args":{…}}}
→ {"type":"cancel","streamId":"<uuid>"}

← {"type":"item","streamId":"…","value":{…}}     // increments
← {"type":"end"|"error","streamId":"…"}
```

`session/follow` opens with `{type:"snapshot",header,cursor,records,hasMore,
projections}` and then streams `{type:"event",event:{…}}` plus process-local
`{type:"assistant-stream",frame:{…}}` chunks. `records` hold
`{type:"event",event:{seq,type,time,data}}`; `data` has no closed schema, so
`extractText` walks the shapes the wire actually uses instead of betting on one
path.

Measured on a live host:

| exchange | size |
|---|---|
| `$events` ready frame | 137 B |
| `session/list` (53 sessions, projections) | 126 KB raw / 44 KB gzip |
| `session/follow` snapshot (26 records, `hasMore:true`) | 49,761 B |
| `session/page` (30 records) | ~48 KB |

## Status

Prototype. Verified: it compiles in CI and produces an installable APK.
**Not yet verified on a device** — the protocol layer is written from live
probes, and the UI has had no runtime exercise.

Implemented: endpoint configuration, session list, follow with live deltas,
older-history paging, prompt, cancel, tool-activity rows, code highlighting,
inline Markdown, reconnect with capped backoff.

Not implemented: image/file attachments, `present` deliverables, approval
prompts, subagent panels, session search, model selection.
