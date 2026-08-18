const puppeteer = require('puppeteer-core')

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: '/usr/local/bin/google-chrome',
    args: ['--no-sandbox', '--disable-gpu']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto('http://127.0.0.1:8090', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-list', { timeout: 15000 })
  await new Promise(r => setTimeout(r, 1200))
  const res = await page.evaluate(async () => {
    const list = await (await fetch('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft')).json()
    const m = window.__situationMap
    const v = m ? m.getView() : null
    return {
      count: (list || []).length,
      first: list && list[0] ? { cs: list[0].callsign, lon: list[0].longitude, lat: list[0].latitude } : null,
      center: v ? v.getCenter() : null,
      zoom: v ? v.getZoom() : null,
      pixelOfShanghai: m ? m.getPixelFromCoordinate([121.8, 31.14]) : null
    }
  })
  console.log(JSON.stringify(res, null, 2))
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
