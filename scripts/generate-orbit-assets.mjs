import sharp from 'sharp'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(repo, 'mobile', 'packages', 'mobile', 'assets')

const markSvg = readFileSync(`${repo}/resources/logo/orbit-mark.svg`, 'utf8')
const fullIconSvg = readFileSync(`${repo}/resources/icon.svg`, 'utf8')

const backgroundSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <defs>
    <linearGradient id="paper" x1="180" y1="140" x2="850" y2="900" gradientUnits="userSpaceOnUse">
      <stop stop-color="#fffaf0"/>
      <stop offset="1" stop-color="#eee5d4"/>
    </linearGradient>
  </defs>
  <rect width="1024" height="1024" fill="url(#paper)"/>
</svg>`

async function main() {
  await sharp(Buffer.from(fullIconSvg)).resize(1024, 1024).png().toFile(`${out}/icon-only.png`)
  await sharp(Buffer.from(backgroundSvg)).png().toFile(`${out}/icon-background.png`)

  const mark = await sharp(Buffer.from(markSvg), { density: 300 })
    .resize(700, 700)
    .png()
    .toBuffer()

  await sharp({
    create: { width: 1024, height: 1024, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite([{ input: mark, left: 162, top: 162 }])
    .png()
    .toFile(`${out}/icon-foreground.png`)

  const splashMark = await sharp(Buffer.from(markSvg), { density: 300 })
    .resize(720, 720)
    .png()
    .toBuffer()

  const splashBg = await sharp(Buffer.from(backgroundSvg)).resize(2732, 2732).png().toBuffer()

  await sharp(splashBg)
    .composite([{ input: splashMark, left: Math.round((2732 - 720) / 2), top: Math.round((2732 - 720) / 2) }])
    .png()
    .toFile(`${out}/splash.png`)

  console.log('orbit icon assets written to', out)
}

main()
