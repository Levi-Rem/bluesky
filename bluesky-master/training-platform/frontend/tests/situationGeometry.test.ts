import { describe, expect, it } from 'vitest'
import { formatHeading, hasTrackPosition } from '../src/situationGeometry'

describe('situation geometry', () => {
  it('skips null and non-finite coordinates', () => {
    expect(hasTrackPosition({ latitude: null, longitude: 121 })).toBe(false)
    expect(hasTrackPosition({ latitude: 31, longitude: Number.NaN })).toBe(false)
    expect(hasTrackPosition({ latitude: 31, longitude: 121 })).toBe(true)
  })

  it('displays north as 360 while keeping other headings three characters wide', () => {
    expect(formatHeading(0)).toBe('360')
    expect(formatHeading(359.6)).toBe('360')
    expect(formatHeading(90)).toBe('090')
  })
})
