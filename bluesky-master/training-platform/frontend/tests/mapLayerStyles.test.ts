import { describe, expect, it } from 'vitest'
import { displayLabel, hexWithAlpha } from '../src/mapLayerStyles'

describe('runtime map layer styles', () => {
  it('uses code first and falls back to name', () => {
    expect(displayLabel({ code: 'A593', name: '航线 A593' })).toBe('A593')
    expect(displayLabel({ code: ' ', name: '上海进近01' })).toBe('上海进近01')
    expect(displayLabel({ code: null, name: null })).toBe('')
  })

  it('uses a fixed twenty-percent alpha for configured fill colors', () => {
    expect(hexWithAlpha('#7B4DB3')).toBe('rgba(123, 77, 179, 0.2)')
    expect(hexWithAlpha('#d9822b')).toBe('rgba(217, 130, 43, 0.2)')
  })
})
