import { copyFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = path.join(projectRoot, 'dist')

await mkdir(outputRoot, { recursive: true })
for (const file of ['orbitv.json', 'pages.json', 'service.js', 'README.md']) {
  await copyFile(path.join(projectRoot, file), path.join(outputRoot, file))
}
