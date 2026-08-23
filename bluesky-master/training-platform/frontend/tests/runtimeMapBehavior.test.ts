import { describe, expect, it } from 'vitest'
import { aircraftIdFromFeature, RUNTIME_LAYER_ORDER } from '../src/runtimeMapBehavior'

describe('runtime map interaction boundaries', () => {
  it('keeps static layers below aircraft in the required order', () => {
    expect(RUNTIME_LAYER_ORDER).toEqual([
      { category: 'PHYSICAL_SECTOR', zIndex: 10 },
      { category: 'WEATHER', zIndex: 20 },
      { category: 'AIRWAY', zIndex: 30 },
      { category: 'WAYPOINT', zIndex: 40 }
    ])
    expect(Math.max(...RUNTIME_LAYER_ORDER.map(item => item.zIndex))).toBeLessThan(100)
  })

  it('selects only features carrying an aircraft id', () => {
    expect(aircraftIdFromFeature('aircraft-1')).toBe('aircraft-1')
    expect(aircraftIdFromFeature(undefined)).toBeNull()
    expect(aircraftIdFromFeature(null)).toBeNull()
    expect(aircraftIdFromFeature('')).toBeNull()
  })
})
