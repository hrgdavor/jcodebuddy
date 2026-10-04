#!/usr/bin/env bun
/**
 * Screenshots the built review page with headless Chrome and *measures* it, so "visually verified"
 * is a check rather than a claim (jsx6's own technique — docs/stack/agent-rules.md: render, then
 * decode the PNG and measure).
 *
 * Usage:
 *   bun run src_build/screenshot.js [--page <html>] [--out <png>] [--width 1280] [--height 1400]
 *
 * Exits 1 when the page is blank (no pixel differs from the background), which is the failure a
 * screenshot alone would hide.
 */
import { existsSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { inflateSync } from 'node:zlib'

const here = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(here, '..')
const repoRoot = resolve(packageRoot, '../..')

const args = process.argv.slice(2)
const valueOf = (flag, fallback) => {
  const index = args.indexOf(flag)
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback
}
const page = resolve(valueOf('--page', join(packageRoot, 'build', 'index.html')))
const out = resolve(valueOf('--out', join(repoRoot, '.tmp', 'merge-review', 'review.png')))
const width = Number(valueOf('--width', '1280'))
const height = Number(valueOf('--height', '1500'))

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  join(process.env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean)

const chrome = CHROME_CANDIDATES.find((candidate) => existsSync(candidate))
if (!chrome) {
  console.error(`[review] no Chrome found; set CHROME_PATH. Looked in:\n  ${CHROME_CANDIDATES.join('\n  ')}`)
  process.exit(2)
}
if (!existsSync(page)) {
  console.error(`[review] no page at ${page} — run: bun run src_build/build.js`)
  process.exit(2)
}

mkdirSync(dirname(out), { recursive: true })
const profile = join(repoRoot, '.tmp', 'merge-review', 'chrome-profile')
rmSync(profile, { recursive: true, force: true })
mkdirSync(profile, { recursive: true })

const run = Bun.spawnSync(
  [
    chrome,
    '--headless=new',
    '--no-sandbox',
    '--disable-crash-reporter',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    `--window-size=${width},${height}`,
    `--user-data-dir=${profile}`,
    `--screenshot=${out}`,
    pathToFileURL(page).href,
  ],
  { stdio: ['ignore', 'ignore', 'pipe'] },
)
const stderr = run.stderr ? new TextDecoder().decode(run.stderr) : ''
if (run.exitCode !== 0 && !existsSync(out)) {
  console.error(`[review] chrome exited ${run.exitCode}\n${stderr}`)
  process.exit(run.exitCode ?? 1)
}

const measurement = measure(out)
console.log(`[review] page:   ${page}`)
console.log(`[review] shot:   ${out}`)
console.log(
  `[review] pixels: ${measurement.width}x${measurement.height}, ` +
    `content ${measurement.contentPixels} px (${(measurement.contentFraction * 100).toFixed(2)}%), ` +
    `bounds x ${measurement.left}..${measurement.right}, y ${measurement.top}..${measurement.bottom}`,
)
if (measurement.contentFraction < 0.0005) {
  console.error('[review] the screenshot is blank — the page rendered nothing')
  process.exit(1)
}

/**
 * A minimal PNG reader for what Chrome writes: 8-bit RGB/RGBA, non-interlaced. Returns the sharing
 * of pixels that differ from the top-left pixel, and their bounding box.
 */
function measure(file) {
  const png = readFileSync(file)
  let offset = 8
  let width = 0
  let height = 0
  let channels = 4
  const idat = []
  while (offset < png.length) {
    const length = png.readUInt32BE(offset)
    const type = png.toString('ascii', offset + 4, offset + 8)
    const data = png.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      const bitDepth = data[8]
      const colorType = data[9]
      const interlace = data[12]
      if (bitDepth !== 8 || interlace !== 0 || (colorType !== 2 && colorType !== 6)) {
        throw new Error(`unsupported PNG: depth ${bitDepth}, colour type ${colorType}, interlace ${interlace}`)
      }
      channels = colorType === 6 ? 4 : 3
    } else if (type === 'IDAT') {
      idat.push(data)
    } else if (type === 'IEND') {
      break
    }
    offset += 12 + length
  }
  const raw = inflateSync(Buffer.concat(idat))
  const stride = width * channels
  const pixels = Buffer.alloc(height * stride)
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]
    const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride)
    const out = pixels.subarray(y * stride, (y + 1) * stride)
    const prior = y > 0 ? pixels.subarray((y - 1) * stride, y * stride) : null
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? out[x - channels] : 0
      const b = prior ? prior[x] : 0
      const c = prior && x >= channels ? prior[x - channels] : 0
      let value = line[x]
      if (filter === 1) value += a
      else if (filter === 2) value += b
      else if (filter === 3) value += (a + b) >> 1
      else if (filter === 4) {
        const p = a + b - c
        const pa = Math.abs(p - a)
        const pb = Math.abs(p - b)
        const pc = Math.abs(p - c)
        value += pa <= pb && pa <= pc ? a : pb <= pc ? b : c
      }
      out[x] = value & 0xff
    }
  }
  const background = [pixels[0], pixels[1], pixels[2]]
  let contentPixels = 0
  let left = width
  let right = -1
  let top = height
  let bottom = -1
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * stride + x * channels
      const differs =
        Math.abs(pixels[i] - background[0]) > 6 ||
        Math.abs(pixels[i + 1] - background[1]) > 6 ||
        Math.abs(pixels[i + 2] - background[2]) > 6
      if (differs) {
        contentPixels++
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
      }
    }
  }
  return { width, height, contentPixels, contentFraction: contentPixels / (width * height), left, right, top, bottom }
}
