const puppeteer = require('puppeteer-core')

const BASE = 'http://127.0.0.1:8090'

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: '/usr/local/bin/google-chrome',
    args: ['--no-sandbox', '--disable-gpu']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-list', { timeout: 15000 })
  await new Promise(r => setTimeout(r, 800))

  // 开始训练 + 创建航空器（上海附近，视图中心 116.5,34 → 屏幕位置可推算）
  await page.evaluate(async () => {
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' })
    await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        callsign: 'CCA3582', aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
        origin: 'ZSSS', destination: 'ZBAA', appearanceOffsetMinutes: '0000',
        latitude: 31.14, longitude: 121.8, headingDegrees: 90, altitudeFeet: 9000, speedKnots: 250,
        route: ['CEN', 'CON', 'GYA', 'ZBAA']
      })
    })
  })
  await new Promise(r => setTimeout(r, 2600))
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/verify-before-drag.png' })

  // 推算标牌位置：视图中心(116.5,34)@zoom5 → 航空器 ≈ (771,478)；标牌 45°右上 120px → ≈ (856,393)
  const aircraftPix = [771, 478]
  const labelPix = [Math.round(aircraftPix[0] + 84.85), Math.round(aircraftPix[1] - 84.85)]
  // 拖到更远的右上（拉长标杆线）
  const target = [labelPix[0] + 130, labelPix[1] - 70]
  await page.mouse.move(labelPix[0], labelPix[1])
  await page.mouse.down()
  await page.mouse.move(target[0], target[1], { steps: 12 })
  await page.mouse.up()
  await new Promise(r => setTimeout(r, 500))
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/verify-after-drag.png' })

  const canvasInfo = await page.evaluate(() => {
    const c = document.querySelector('canvas')
    return { w: c.width, h: c.height, rect: c.getBoundingClientRect().toJSON() }
  })
  console.log('label started at', labelPix, '-> dragged to', target)
  console.log('canvas', JSON.stringify(canvasInfo))
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
