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
  await new Promise(r => setTimeout(r, 1000))

  const ac = await page.evaluate(async () => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    let list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    if (!list.length) {
      await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          callsign: 'CCA3582', aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
          origin: 'ZSSS', destination: 'ZBAA', appearanceOffsetMinutes: '0000',
          latitude: 31.14, longitude: 121.8, headingDegrees: 90, altitudeFeet: 9000, speedKnots: 250,
          route: ['CEN', 'CON', 'GYA', 'ZBAA']
        })
      })
      await new Promise(r => setTimeout(r, 1200))
      list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    }
    return list[0]
  })
  await new Promise(r => setTimeout(r, 800))

  const probe = await page.evaluate(merc => {
    const m = window.__situationMap
    const sym = m.getPixelFromCoordinate(merc)
    // 标牌默认右上 45°、120px
    const label = [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)]
    // 在标牌中心、符号中心分别做 OL 命中检测
    const hits = {}
    for (const [name, px] of [['label', label], ['symbol', sym.map(Math.round)]]) {
      const found = []
      m.forEachFeatureAtPixel(px, f => { found.push(String(f.getId())); return undefined })
      hits[name] = found
    }
    return { label, sym: sym.map(Math.round), hits }
  }, toMerc([ac.longitude, ac.latitude]))
  console.log('hit-detection:', JSON.stringify(probe, null, 2))

  // 实际点击标牌中心，看选中态
  await page.click('body', { offset: { x: 0, y: 0 } }).catch(() => {})
  const selBefore = await page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.textContent ?? null)
  await page.mouse.click(probe.label[0], probe.label[1])
  await new Promise(r => setTimeout(r, 600))
  const selAfterLabel = await page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.textContent ?? null)
  // 点击符号
  await page.mouse.click(probe.sym[0], probe.sym[1])
  await new Promise(r => setTimeout(r, 600))
  const selAfterSym = await page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.textContent ?? null)
  console.log(JSON.stringify({
    before: selBefore, afterLabelClick: selAfterLabel, afterSymbolClick: selAfterSym
  }, null, 2))
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
