const puppeteer = require('puppeteer-core')

const toMerc = ([lon, lat]) => [
  lon * 111319.49079327358,
  6378137 * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI / 360)))
]

;(async () => {
  const browser = await puppeteer.launch({ executablePath: '/usr/local/bin/google-chrome', args: ['--no-sandbox', '--disable-gpu'] })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto('http://127.0.0.1:8090', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-list', { timeout: 15000 })
  await page.waitForFunction('!!window.__situationMap', { timeout: 10000 })
  await new Promise(r => setTimeout(r, 800))

  const ac = await page.evaluate(async () => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    const list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    return list[0]
  })
  await new Promise(r => setTimeout(r, 800))

  const grid = await page.evaluate(merc => {
    const m = window.__situationMap
    const sym = m.getPixelFromCoordinate(merc).map(Math.round)
    const label = [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)]
    // 在标牌盒子内布一张命中网格（±60px 横向、±24px 纵向）
    const out = []
    for (let dy = -24; dy <= 24; dy += 8) {
      const row = []
      for (let dx = -64; dx <= 64; dx += 16) {
        const px = [label[0] + dx, label[1] + dy]
        const hits = []
        m.forEachFeatureAtPixel(px, f => { hits.push(String(f.getId()).split(':')[0]); return undefined })
        row.push(hits.length ? (hits.includes('label') ? 'L' : hits.join(',')) : '·')
      }
      out.push(row.join(' '))
    }
    return { label, grid: out.join('\n') }
  }, toMerc([ac.longitude, ac.latitude]))
  console.log('label box @', grid.label)
  console.log(grid.grid.replace(/·/g, '·'))
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
