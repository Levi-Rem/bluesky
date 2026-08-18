const puppeteer = require('puppeteer-core')

;(async () => {
  const browser = await puppeteer.launch({ executablePath: '/usr/local/bin/google-chrome', args: ['--no-sandbox', '--disable-gpu'] })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto('http://127.0.0.1:8090', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-list', { timeout: 15000 })
  await page.waitForFunction('!!window.__situationMap', { timeout: 10000 })
  await new Promise(r => setTimeout(r, 900))

  // 清空 → 建两架近距叠机（AAA 先建=pre-fix 在下层；BBB 后建=原本在上层）
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
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body('AAA111', 34.00, 116.50) })
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body('BBB222', 34.06, 116.56) })
  })
  await new Promise(r => setTimeout(r, 1800))

  const clickRow = cs => page.evaluate((callsign) => {
    const rows = document.querySelectorAll('.aircraft-row')
    for (const r of rows) if (r.textContent.includes(callsign)) { r.click(); return 'clicked ' + callsign }
    return 'row not found ' + callsign
  }, cs)

  const selected = () => page.evaluate(() => document.querySelector('.aircraft-row-wrap.selected')?.querySelector('span')?.textContent ?? '(none)')

  // ① 先选上层的 BBB222（应在上）
  await clickRow('BBB222'); await new Promise(r => setTimeout(r, 500))
  console.log('selected (BBB222 expected):', await selected())
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/top-bbb.png' })

  // ② 再选下层的 AAA111（修复后 AAA 应整个抬到 BBB 之上）
  await clickRow('AAA111'); await new Promise(r => setTimeout(r, 500))
  console.log('selected (AAA111 expected):', await selected())
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/top-aaa.png' })

  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
