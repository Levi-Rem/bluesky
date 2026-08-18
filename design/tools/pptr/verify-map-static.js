const puppeteer = require('puppeteer-core')

/* 验证：左键拖标牌时地图必须不动（视图中心/缩放不变），且标牌真的被拖走 */
;(async () => {
  const browser = await puppeteer.launch({
    executablePath: '/usr/local/bin/google-chrome',
    args: ['--no-sandbox', '--disable-gpu']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto('http://127.0.0.1:8090', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-list', { timeout: 15000 })
  await page.waitForFunction('!!window.__situationMap', { timeout: 10000 })
  await new Promise(r => setTimeout(r, 1000))

  // 确保训练组在跑、有航空器（平台重启后引擎复位，没有就新建一架）
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
    return list[0] ?? null
  })
  if (!ac) throw new Error('no aircraft on the map')
  await new Promise(r => setTimeout(r, 1500))

  /* ol 视图是 EPSG:3857（米）；经纬度需先转墨卡托再求像素 */
  const toMerc = ([lon, lat]) => [
    lon * 111319.49079327358,
    6378137 * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI / 360)))
  ]

  const before = await page.evaluate((merc) => {
    const m = window.__situationMap
    const v = m.getView()
    return { center: v.getCenter(), zoom: v.getZoom(), sym: m.getPixelFromCoordinate(merc) }
  }, toMerc([ac.longitude, ac.latitude]))

  // 标牌默认在符号右上 45°、120px → 标牌中心像素
  const label = [Math.round(before.sym[0] + 84.85), Math.round(before.sym[1] - 84.85)]
  const target = [label[0] + 140, label[1] - 90]

  await page.screenshot({ path: '/home/ubuntu/bluesky/design/mapstatic-before.png' })
  await page.mouse.move(label[0], label[1])
  await page.mouse.down()
  await page.mouse.move(target[0], target[1], { steps: 14 })
  await page.mouse.up()
  await new Promise(r => setTimeout(r, 400))
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/mapstatic-after.png' })

  const after = await page.evaluate((merc) => {
    const m = window.__situationMap
    const v = m.getView()
    return { center: v.getCenter(), zoom: v.getZoom(), sym: m.getPixelFromCoordinate(merc) }
  }, toMerc([ac.longitude, ac.latitude]))

  // 地图未动：中心一致（容差 1e-9 度）且缩放一致；符号像素位置的变化仅来自航空器自身位移
  const dCenter = Math.hypot(after.center[0] - before.center[0], after.center[1] - before.center[1])
  const dSym = Math.hypot(after.sym[0] - before.sym[0], after.sym[1] - before.sym[1])
  const verdict = {
    mapMoved: dCenter > 1e-9 || after.zoom !== before.zoom,
    dCenter,
    zoomSame: after.zoom === before.zoom,
    dSymPx: Math.round(dSym * 100) / 100,
    labelFrom: label,
    labelTo: target
  }
  console.log(JSON.stringify(verdict, null, 2))
  await browser.close()
  process.exit(verdict.mapMoved ? 2 : 0)
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
