import type { MapLayerCategory } from './types'

export const RUNTIME_LAYER_ORDER: ReadonlyArray<{ category: MapLayerCategory; zIndex: number }> = [
  { category: 'PHYSICAL_SECTOR', zIndex: 10 },
  { category: 'WEATHER', zIndex: 20 },
  { category: 'AIRWAY', zIndex: 30 },
  { category: 'WAYPOINT', zIndex: 40 }
]

export function aircraftIdFromFeature(value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null
  return String(value)
}
