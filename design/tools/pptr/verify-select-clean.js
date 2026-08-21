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

  // 清理：pause → 删除现有航空器 → 只留一架新的
  await page.evaluate(async () => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/pause', { method: 'POST' }).catch(() => {})
    const list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    for (const a of list) await fetch('/api/v1/aircraft/' + a.id, { method: 'DELETE' }).catch(() => {})
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    return list.map(a => a.callsign)
  })
  await new Promise(r => setTimeout(r, 500))

  const callsign = 'SOLO' + Math.floor(Math.random() * 900 + 100)
  const ac = await page.evaluate(async (callsign) => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }).catch(() => {})
    return (await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        callsign, aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
        origin: 'ZSSS', destination: 'ZBAA', appearanceOffsetMinutes: '0000',
        latitude: 34.0, longitude: 116.5, headingDegrees: 90, altitudeFeet: 9000, speedKnots: 250,
        route: ['CEN', 'CON', 'GYA', 'ZBAA']
      })
    })).json())
  }, callsign)
  await new Promise(r => setTimeout(r, 1500))

  const pos = await page.evaluate(merc => {
    const m = window.__situationMap
    const sym = m.getPixelFromCoordinate(merc).map(Math.round)
    return { sym, label: [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)] }
  }, toMerc([116.5, 34.0]))

  const selected = () => page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.querySelector('span')?.textContent ?? '(none)')
  const R = []
  R.push(['baseline(clean)', await selected()])
  // 依次点：标牌中心 / 左缘 / 右缘 / 上缘 / 下缘 / 符号
  const pts = {
    center: [0, 0],
    leftEdge: [-72, 0], rightEdge: [72, 0],
    topEdge: [0, -25], bottomEdge: [0, 25],
    symbol: [pos.sym[0] - pos.label[0], pos.sym[1] - pos.label[1]]
  }
  for (const [name, [dx, dy]] of Object.entries(pts)) {
    await page.mouse.click(pos.label[0] + dx, pos.label[1] + dy)
    await new Promise(r => setTimeout(r, 450))
    R.push([`click ${name}`, await selected()])
  }
  for (const [k, v] of R) console.log(k.padEnd(26), '->', v)
  const pass = R.slice(1).every(([, v]) => v.startsWith(callsign))
  console.log(pass ? 'CLEAN SINGLE: ALL PASS' : 'CLEAN SINGLE: FAIL')
  await browser.close()
  process.exit(pass ? 0 : 2)
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
