import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const outputRoot = resolve(projectRoot, 'public/assets')

await rm(resolve(projectRoot, 'public/vendor/tesseract'), { recursive: true, force: true })
await mkdir(outputRoot, { recursive: true })
const workerSource = await readFile(
  resolve(projectRoot, 'node_modules/tesseract.js/dist/worker.min.js'),
  'utf8',
)
await writeFile(
  resolve(outputRoot, 'ocr.worker-7.0.0.js'),
  workerSource.replaceAll('https://cdn.jsdelivr.net/', './ocr-local-only/'),
)
for (const name of [
  'tesseract-core.wasm.js',
  'tesseract-core-simd.wasm.js',
  'tesseract-core-lstm.wasm.js',
  'tesseract-core-simd-lstm.wasm.js',
  'tesseract-core-relaxedsimd.wasm.js',
  'tesseract-core-relaxedsimd-lstm.wasm.js',
]) {
  await cp(resolve(projectRoot, 'node_modules/tesseract.js-core', name), resolve(outputRoot, name))
}

const tesseractLicense = await readFile(resolve(projectRoot, 'node_modules/tesseract.js/LICENSE.md'), 'utf8')
const coreLicense = await readFile(resolve(projectRoot, 'node_modules/tesseract.js-core/LICENSE'), 'utf8')
await writeFile(resolve(outputRoot, 'ocr.third-party-licenses.txt'), [
  'tesseract.js 7.0.0 - Apache-2.0',
  tesseractLicense,
  'tesseract.js-core 7.0.0 - Apache-2.0',
  coreLicense,
  '@tesseract.js-data/eng 1.0.0 - MIT',
  'Source: https://github.com/naptha/tessdata',
  'idb-keyval 6.3.0 - Apache-2.0',
  'Source: https://github.com/jakearchibald/idb-keyval',
].join('\n\n'))
