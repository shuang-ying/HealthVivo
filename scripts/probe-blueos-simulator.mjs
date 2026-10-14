// Run the official native simulator against an isolated, synthetic API probe.
// The local HTTP collector is diagnostics only, not a GT2 Bluetooth gateway.
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { WebSocketServer } = require('D:/DevTools/BlueOSStudio/resources/app/extensions/blueos-debugger/node_modules/ws')
const studio = 'D:/DevTools/BlueOSStudio/resources/app/extensions/blueos-previewer/simulator/win'
const workspace = 'D:/AgentProject/HealthVivoDirectProbe'
const root = new URL('../artifacts/direct-link-probe/', import.meta.url)
fs.mkdirSync(root, { recursive: true })
if (!fs.existsSync(path.join(workspace, 'health-vivo-direct-probe.txt'))) throw new Error('Unmarked probe workspace')
const report = { nativeBlueOsSimulator: true, syntheticOnly: true, gt2FirmwareVerified: false,
  netsimInteroperabilityVerified: false, watchBluetoothTransferVerified: false,
  websocketConnected: false, messages: [] }
const collector = http.createServer(async (request, response) => {
  if (request.method !== 'POST' || request.url !== '/result') { response.writeHead(404); response.end(); return }
  let body = ''
  for await (const chunk of request) { body += chunk; if (body.length > 8192) { response.writeHead(413); response.end(); return } }
  try { report.nativeApiResult = JSON.parse(body); response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"ok":true}') }
  catch { response.writeHead(400); response.end() }
})
await new Promise(resolve => collector.listen(28561, '127.0.0.1', resolve))
const websocket = new WebSocketServer({ port: 28560, host: '127.0.0.1' })
websocket.on('connection', (socket, request) => {
  report.websocketConnected = true
  report.websocketPath = request.url
  socket.on('message', data => {
    if (report.messages.length < 12) report.messages.push({ length: data.length, preview: data.length < 1000 ? data.toString().slice(0,300) : 'large simulator message omitted' })
    try {
      const message = JSON.parse(data.toString())
      if (message.event === 'client') {
        // Match Studio's preview bridge: startup parameters are command-line arguments;
        // the preview pane enables native rendering with the `on` message.
        socket.send(JSON.stringify({ event: 'previewToSimulator', payload: { method: 'on' } }))
      }
    } catch {}
  })
})
const args = ['-port', '28560', '-device', 'watch-square', '-package', 'com.lrh.healthvivo.watch.directprobe',
  '-app', path.join(workspace, 'build'), '-compilePath', 'emptyPath', '-compileQuery', 'emptyQuery',
  '-defaultAppDataPath', path.join(workspace, 'simulator-data'), '-width', '432', '-height', '514',
  '-appUpdateMethod', '0', '-type', 'app', '-intrpkPackage', '', '-moduleName', '']
const process = spawn(path.join(studio, 'watch.exe'), args, { cwd: studio, windowsHide: true })
let stdout = '', stderr = '', exited = false
process.stdout.on('data', chunk => { if (stdout.length < 200000) stdout += chunk })
process.stderr.on('data', chunk => { if (stderr.length < 30000) stderr += chunk })
process.on('error', error => { report.launchError = error.message; exited = true })
process.on('exit', (code, signal) => { report.exitCode = code; report.exitSignal = signal; exited = true })
try {
  const deadline = Date.now() + 25000
  while (Date.now() < deadline && !exited && !report.nativeApiResult) await new Promise(resolve => setTimeout(resolve, 200))
  report.nativeApiResultReceived = !!report.nativeApiResult
  report.stdoutProbeLines = stdout.split(/\r?\n/).filter(line => /DIRECT_PROBE|COLLECTOR|Exception|failed|cannot|error|packageName|createGattServer/i.test(line)).slice(-30)
} finally {
  if (!exited) process.kill()
  for (const client of websocket.clients) client.terminate()
  await new Promise(resolve => websocket.close(resolve))
  await new Promise(resolve => collector.close(resolve))
  fs.writeFileSync(new URL('blueos-native.stdout.txt', root), stdout)
  fs.writeFileSync(new URL('blueos-native.stderr.txt', root), stderr)
  fs.writeFileSync(new URL('blueos-native-result.json', root), JSON.stringify(report, null, 2))
}
console.log(JSON.stringify(report, null, 2))
