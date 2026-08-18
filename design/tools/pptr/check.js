const puppeteer = require('puppeteer-core')

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: '/usr/local/bin/google-chrome',
    args: ['--no-sandbox', '--disable-gpu']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1300, height: 800 })
  await page.goto('http://127.0.0.1:8090', { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.aircraft-row, .aircraft-list', { timeout: 15000 })
  await new Promise(r => setTimeout(r, 800))
  await page.click('button[title="创建航空器"]')
  await new Promise(r => setTimeout(r, 600))
  const info = await page.evaluate(() => {
    const f = document.querySelector('.aircraft-form')
    if (!f) return { open: false }
    const cs = getComputedStyle(f)
    const r = f.getBoundingClientRect()
    return {
      open: true,
      position: cs.position,
      width: cs.width,
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      viewport: { w: window.innerWidth, h: window.innerHeight },
      centered: Math.abs(r.x - (window.innerWidth - r.width) / 2) < 2
    }
  })
  console.log(JSON.stringify(info, null, 2))
  await page.screenshot({ path: '/home/ubuntu/bluesky/design/live-check4.png' })
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
