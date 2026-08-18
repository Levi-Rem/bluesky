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

  const callsign = 'FIX' + Math.floor(Math.random() * 900 + 100)
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
    const label = [Math.round(sym[0] + 84.85), Math.round(sym[1] - 84.85)]
    return { sym, label }
  }, toMerc([116.5, 34.0]))

  const selectedRow = () => page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.querySelector('span')?.textContent ?? '(none)')
  const R = []
  R.push(['baseline', await selectedRow()])

  // ① 死区：标牌右半边（旧版此处 OL 命中为空）
  await page.mouse.click(pos.label[0] + 55, pos.label[1])
  await new Promise(r => setTimeout(r, 600))
  R.push(['click right-half dead zone', await selectedRow()])

  // ② 标牌上部空白
  await page.mouse.click(pos.label[0], pos.label[1] - 20)
  await new Promise(r => setTimeout(r, 600))
  R.push(['click label top padding', await selectedRow()])

  // ③ 手抖场景：按下带 5px 抖动再抬起（> OL click 容差，singleclick 不触发）
  await page.mouse.move(pos.label[0] - 40, pos.label[1] + 8)
  await page.mouse.down()
  await page.mouse.move(pos.label[0] - 35, pos.label[1] + 13), { steps: 3 }
  await page.mouse.up()
  await new Promise(r => setTimeout(r, 600))
  R.push(['jitter press (no click)', await selectedRow()])

  // ④ 符号点击
  await page.mouse.click(pos.sym[0], pos.sym[1])
  await new Promise(r => setTimeout(r, 600))
  R.push(['click symbol', await selectedRow()])

  for (const [k, v] of R) console.log(k.padEnd(28), '->', v)
  const pass = R.slice(1).every(([, v]) => v.startsWith(callsign))
  console.log(pass ? 'ALL PASS' : 'FAIL')
  await browser.close()
  process.exit(pass ? 0 : 2)
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
