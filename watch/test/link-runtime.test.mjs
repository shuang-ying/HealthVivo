// Guards on the *shape* of the watch link code.
//
// The watch app runs on a polyfilled BlueOS runtime, not on Node and not on a browser. A missing
// global is invisible until the exact line that uses it executes, and when that line sits in the
// start-up path the symptom is a watch that silently never advertises while the phone waits in a
// connect timeout. That is exactly what happened: `globalThis` was read as a default parameter
// inside the server constructor, which threw before the GATT server was ever created.
//
// These tests pin the constraint down mechanically so it cannot come back by accident.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { harness, settle, send, client, KIND, STATE } from './link-client.mjs'

const linkRoot = new URL('../src/lib/link/', import.meta.url)

/** Remove comments so a construct named in prose does not fail the check. */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
}

async function linkSources() {
  const names = (await readdir(linkRoot)).filter(name => /\.(mjs|js)$/.test(name)).sort()
  assert.ok(names.length >= 8, 'expected the whole link module set to be present')
  const sources = new Map()
  for (const name of names) {
    sources.set(name, await readFile(new URL(name, linkRoot), 'utf8'))
  }
  return sources
}

test('the watch link code avoids globals and syntax the BlueOS runtime may not provide', async () => {
  // Each entry is present in neither the surviving watch modules nor the constructs proven to work
  // by the fact that the app already reaches its connection-record stage on real hardware.
  const forbidden = [
    ['globalThis', /\bglobalThis\b/],
    ['BigInt', /\bBigInt\b/],
    ['DataView.setBigUint64', /setBigUint64/],
    ['DataView.getBigUint64', /getBigUint64/],
    ['setInterval', /\bsetInterval\b/],
    ['clearInterval', /\bclearInterval\b/],
    ['ArrayBuffer.isView', /ArrayBuffer\s*\.\s*isView/],
    ['ArrayBuffer.prototype.slice', /\.buffer\s*\.\s*slice\s*\(/],
    ['optional chaining', /\?\.[A-Za-z_$[]/],
    ['nullish coalescing', /\?\?/],
    ['flatMap', /\.flatMap\s*\(/],
    ['Object.entries', /Object\s*\.\s*entries\s*\(/],
    ['Object.values', /Object\s*\.\s*values\s*\(/],
    ['Array.from', /Array\s*\.\s*from\s*\(/],
    ['structuredClone', /\bstructuredClone\b/],
    ['queueMicrotask', /\bqueueMicrotask\b/],
    ['replaceAll', /\.replaceAll\s*\(/],
    ['padStart', /\.padStart\s*\(/],
    // Annex B legacy globals: not part of core ECMAScript, so not guaranteed on this engine.
    ['unescape', /\bunescape\s*\(/],
    ['escape', /(^|[^A-Za-z0-9_$.])escape\s*\(/]
  ]
  const sources = await linkSources()
  const problems = []
  for (const [name, source] of sources) {
    const code = stripComments(source)
    for (const [label, pattern] of forbidden) {
      if (pattern.test(code)) problems.push(name + ' uses ' + label)
    }
  }
  assert.deepEqual(problems, [])
})

test('no module in the link layer reads an undeclared global as a default parameter', async () => {
  const sources = await linkSources()
  for (const [name, source] of sources) {
    const code = stripComments(source)
    // A default parameter is evaluated by the caller's frame; if it names something the engine does
    // not define, the whole factory throws instead of the one operation that needed it.
    assert.doesNotMatch(code, /=\s*(globalThis|self|window)\b/, name + ' defaults a parameter to a global object')
  }
})

test('the server builds and connects with no injected timers at all', async () => {
  // The harness normally injects a virtual clock. Here nothing is injected, so this exercises the
  // bare `setTimeout`/`clearTimeout` fallback that the real watch will use.
  const h = harness({ realTimers: true })
  await h.start()
  try {
    assert.equal(h.server.snapshot().serverReady, true, 'the server must register without an injected timers object')
    assert.equal(h.advertised(), 1)
    const phone = client(h.identity.epoch, Buffer.alloc(32, 9).toString('base64'))
    send(h, 1, KIND.ENROLL, phone.enrollment)
    const ready = await settle(h, STATE.READY)
    assert.equal(phone.verifyReceipt(ready, STATE.READY, 1), true)
  } finally { h.server.close() }
})

test('the link module set is loaded from one runtime entry point', async () => {
  // app.ux must own the server, so nothing else may create one. `server.js` is the only file that
  // imports the native BLE module, and only runtime.js imports server.js.
  const sources = await linkSources()
  const nativeImporters = [...sources].filter(([, source]) => /from '@blueos\.bluetooth\.ble'/.test(source)).map(([name]) => name)
  assert.deepEqual(nativeImporters, ['server.js'])
  const app = await readFile(new URL('../src/app.ux', import.meta.url), 'utf8')
  assert.match(app, /getLinkRuntime/)
  assert.match(app, /onCreate\(\)\s*\{\s*link\.start\(\)\s*\}/)
  assert.match(app, /onDestroy\(\)\s*\{\s*link\.close\(\)\s*\}/)
})
