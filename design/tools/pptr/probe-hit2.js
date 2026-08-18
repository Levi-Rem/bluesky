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

  // 新建独一呼号的航空器（在视图中心附近，确保可见）
  const callsign = 'TST' + Math.floor(Math.random() * 900 + 100)
  const ac = await page.evaluate(async (callsign) => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    const created = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        callsign, aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
        origin: 'ZSSS', destination: 'ZBAA', appearanceOffsetMinutes: '0000',
        latitude: 34.0, longitude: 116.5, headingDegrees: 90, altitudeFeet: 9000, speedKnots: 250,
        route: ['CEN', 'CON', 'GYA', 'ZBAA']
      })
    })).json()
    return created
  }, callsign)
  await new Promise(r => setTimeout(r, 1500))

  const info = await page.evaluate((merc) => {
    const m = window.__situationMap
    const sym = m.getPixelFromCoordinate(merc).map(Math.round)
    const label = [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)]
    const hits = []
    m.forEachFeatureAtPixel(label, f => { hits.push(String(f.getId())); return undefined })
    return { sym, label, hits }
  }, toMerc([116.5, 34.0]))
  console.log(callsign, 'sym:', info.sym, 'label:', info.label, 'hits@label:', info.hits)

  const selectedRow = () => page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.querySelector('span')?.textContent ?? '(none)')

  // 1) 点空白处（左下角远离航空器）记录基线选中
  await page.mouse.click(200, 600)
  await new Promise(r => setTimeout(r, 700))
  console.log('baseline selected:', await selectedRow())

  // 2) 点击标牌中心
  await page.mouse.click(info.label[0], info.label[1])
  await new Promise(r => setTimeout(r, 700))
  console.log('after LABEL click selected:', await selectedRow())

  // 3) 点击符号
  await page.mouse.click(info.sym[0], info.sym[1])
  await new Promise(r => setTimeout(r, 700))
  console.log('after SYMBOL click selected:', await selectedRow())

  await page.screenshot({ path: '/home/ubuntu/bluesky/design/probe-hit-final.png' })
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
