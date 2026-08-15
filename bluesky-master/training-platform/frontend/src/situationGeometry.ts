import type { Aircraft } from './types'

export function hasTrackPosition(
  item: Pick<Aircraft, 'latitude' | 'longitude'>
): item is { latitude: number; longitude: number } {
  return item.longitude != null && item.latitude != null
    && Number.isFinite(item.longitude) && Number.isFinite(item.latitude)
}

export function formatHeading(headingDegrees: number): string {
  const rounded = Math.round(headingDegrees) % 360
  return (rounded === 0 ? 360 : rounded).toString().padStart(3, '0')
}
