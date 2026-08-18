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
  await new Promise(r => setTimeout(r, 900))

  // 清空后建两架近距飞机（标牌重叠）
  await page.evaluate(async () => {
    const list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    for (const a of list) await fetch('/api/v1/aircraft/' + a.id, { method: 'DELETE' }).catch(() => {})
    const body = (cs, lat, lon) => JSON.stringify({
      callsign: cs, aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
      origin: 'ZSSS', destination: 'ZBAA', appearanceOffsetMinutes: '0000',
      latitude: lat, longitude: lon, headingDegrees: 90, altitudeFeet: 9000, speedKnots: 250,
      route: ['CEN', 'CON', 'GYA', 'ZBAA']
    })
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body('AAA111', 34.0, 116.5) })
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body('BBB222', 34.1, 116.53) })
  })
  await new Promise(r => setTimeout(r, 1800))

  const posA = await page.evaluate(merc => {
    const m = window.__situationMap
    const sym = m.getPixelFromCoordinate(merc).map(Math.round)
    return { sym, label: [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)] }
  }, toMerc([116.5, 34.0]))
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/overlap-transparent.png' })

  // 回归：点击标牌 → 必有一架被选中（重叠时选任一架均算机制正常）
  await page.mouse.click(posA.label[0], posA.label[1])
  await new Promise(r => setTimeout(r, 500))
  const sel = await page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.querySelector('span')?.textContent ?? '(none)')
  const ok = sel.startsWith('AAA111') || sel.startsWith('BBB222')
  console.log('click label -> selected:', sel)
  console.log('selection intact:', ok ? 'PASS' : 'FAIL')

  await browser.close()
  process.exit(ok ? 0 : 2)
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
