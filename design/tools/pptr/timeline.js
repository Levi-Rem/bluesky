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
  await new Promise(r => setTimeout(r, 800))
  await page.evaluate(() => {
    window.__samples = []
    const form = document.querySelector('.aircraft-form') // null before open
    const orig = window.requestAnimationFrame
  })
  // monkey-patch: record size evolution right after click
  await page.evaluate(() => {
    window.__samples = []
    const btn = document.querySelector('button[title="创建航空器"]')
    btn.addEventListener('click', () => {
      const t0 = performance.now()
      const sample = () => {
        const f = document.querySelector('.aircraft-form')
        window.__samples.push({
          t: Math.round(performance.now() - t0),
          exists: !!f,
          w: f ? f.offsetWidth : null,
          h: f ? f.offsetHeight : null,
          left: f ? f.style.left : null
        })
      }
      ;[0, 10, 20, 40, 80, 160, 320, 640].forEach(ms => setTimeout(sample, ms))
    })
  })
  await page.click('button[title="创建航空器"]')
  await new Promise(r => setTimeout(r, 900))
  const samples = await page.evaluate(() => window.__samples)
  console.log(JSON.stringify(samples, null, 1))
  const grid = await page.evaluate(() => {
    const g = document.querySelector('.form-grid')
    return g ? { cols: getComputedStyle(g).gridTemplateColumns, rows: getComputedStyle(g).gridTemplateRows } : null
  })
  console.log('grid:', JSON.stringify(grid))
  await browser.close()
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) })
