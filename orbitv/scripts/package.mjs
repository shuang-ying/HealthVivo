import { createWriteStream } from 'node:fs'
import { mkdir, readFile } from 'node:fs/promises'
import { finished } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import archiver from 'archiver'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const runtimeRoot = path.join(projectRoot, 'dist')
const releaseRoot = path.join(projectRoot, 'release')
const manifest = JSON.parse(await readFile(path.join(projectRoot, 'orbitv.json'), 'utf8'))
const safeAppId = manifest.appId.replace(/[^A-Za-z0-9._-]/g, '-')
const packagePath = path.join(releaseRoot, `${safeAppId}-${manifest.version}.ovpkg`)

await mkdir(releaseRoot, { recursive: true })
const output = createWriteStream(packagePath)
const archive = archiver('zip', { zlib: { level: 9 } })
archive.on('warning', (error) => {
  if (error.code !== 'ENOENT') throw error
})
archive.on('error', (error) => output.destroy(error))
archive.pipe(output)
archive.directory(runtimeRoot, false)
await archive.finalize()
await finished(output)

console.log(`已生成 ${packagePath}`)
