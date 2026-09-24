#!/usr/bin/env node
// Record one real turn from a live Host as a test fixture.
//
//   node tools/capture-turn.mjs http://127.0.0.1:3080 > app/src/test/resources/turn-running.json
//
// The app's composer decides between Stop and Send from a single boolean, and
// that boolean arrives on `$events` as `api-session/status` -- an *emit*, which
// the Host sends once on a change and never replays. A fixture that holds only
// the conversation cannot express that, so this captures both streams the app
// opens (the `session/follow` frames and the forwarded `$events` emits) in
// arrival order, with the timing of each. `ComposerRunningTest` replays it and
// asks the two questions that matter: does the ordinary path end on Send, and
// can the list repair a client that missed the emit?
//
// It talks to a real Host and spends a few thousand tokens of that Host's model
// quota. The session it creates is archived on the way out.
//
// No dependency: Node's own WebSocket and fetch are both in the runtime this
// repo already builds with, so the tool runs from a checkout with nothing
// installed.
const origin = (process.argv[2] ?? process.env.DSH_ORIGIN ?? 'http://127.0.0.1:3080').replace(/\/$/, '')
const prompt = process.argv[3] ?? 'Run bash: sleep 12 — then reply with the single word PINEAPPLE.'
const wsUrl = origin.replace(/^http/, 'ws') + '/api/remote.mux'
const t0 = Date.now()
const at = () => Date.now() - t0
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function rpc(method, args) {
  const rpcId = crypto.randomUUID()
  const res = await fetch(`${origin}/api/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId, method, payload: { args } }),
  })
  const json = await res.json()
  if (!json.result?.ok) throw new Error(`${method}: ${JSON.stringify(json.result).slice(0, 400)}`)
  return json.result.value
}

const created = await rpc('session/create', { request: {} })
const sessionId = created.sessionId
const frames = []

const ws = new WebSocket(wsUrl)
await new Promise((resolve, reject) => {
  // Node's own WebSocket is the browser one, not `ws`: event listeners, not
  // `on(...)`. Keeping to it is what makes this run from a bare checkout.
  ws.addEventListener('open', resolve, { once: true })
  ws.addEventListener('error', (event) => reject(new Error(event.message ?? 'socket error')), { once: true })
})
ws.send(JSON.stringify({ type: 'open', streamId: 'ev', endpoint: '$events', payload: { args: {} } }))
ws.send(JSON.stringify({
  type: 'open',
  streamId: 'fo',
  endpoint: 'session/follow',
  payload: { args: { request: { address: { kind: 'session', sessionId }, maxMessages: 50, assistantStream: true } } },
}))
ws.addEventListener('message', (event) => {
  let msg
  try { msg = JSON.parse(String(event.data)) } catch { return }
  if (msg.type !== 'item') return
  const value = msg.value
  if (msg.streamId === 'ev' && value.type === 'emit') {
    frames.push({ at: at(), stream: 'events', kind: 'emit', event: value.event, args: value.args })
  } else if (msg.streamId === 'fo') {
    frames.push({ at: at(), stream: 'follow', kind: 'frame', value })
  }
})

await sleep(1200)
const requestId = crypto.randomUUID()
await rpc('session/prompt', { request: { requestId, sessionId, mode: 'queue', content: [{ type: 'text', text: prompt }] } })
console.error(`prompt sent at +${at()}ms`)

// Long enough for a tool call plus the reply, and for the turn to close.
await sleep(45_000)
ws.close()
// `workspace/archiveSession`, not `session/archive`: the session API has no
// archive, and a fixture tool that leaves throwaway sessions in the drawer is a
// fixture tool nobody runs twice.
try { await rpc('workspace/archiveSession', { request: { sessionId } }) } catch (error) { console.error(`archive: ${error.message}`) }

// Streaming chunks are most of the bytes and none of this question: keep the
// durable rows that decide the flag, the snapshot, and every session-state emit.
const keep = frames.filter((frame) => {
  if (frame.stream === 'events') return true
  const value = frame.value
  if (value?.type === 'snapshot') return true
  return value?.type === 'event' && ['turn/start', 'turn/end', 'assistant/message', 'user/message'].includes(value.event.type)
})

const statuses = keep.filter((f) => f.event === 'api-session/status')
console.error(`captured ${frames.length} frames, kept ${keep.length}: ${statuses.length} status emits`)
for (const f of statuses) console.error(`  +${f.at}ms api-session/status ${JSON.stringify(f.args)}`)
if (statuses.length < 2) {
  console.error('!! fewer than two status emits: the fixture cannot show the turn ending')
  process.exitCode = 1
}

process.stdout.write(JSON.stringify({
  note: 'One real turn against a live Host: a tool call, then the reply. Both streams the app opens, in arrival order, so the interleaving of api-session/status with the durable frames can be replayed offline. Captured by tools/capture-turn.mjs.',
  sessionId,
  prompt,
  requestId,
  frames: keep,
}, null, 1))
