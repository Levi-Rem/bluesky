import type { ReferenceItem } from './types'

type Search = (
  kind: 'airports' | 'waypoints' | 'aircraft-types', query: string
) => Promise<ReferenceItem[]>

export async function validateAircraftReferences(
  aircraftType: string,
  origin: string,
  destination: string,
  routeText: string,
  search: Search
): Promise<string> {
  const type = aircraftType.trim().toUpperCase()
  const departure = origin.trim().toUpperCase()
  const arrival = destination.trim().toUpperCase()
  if (!await exactMatch('aircraft-types', type, search)) return `未知机型: ${type}`
  if (!await exactMatch('airports', departure, search)) return `未知起飞机场: ${departure}`
  if (!await exactMatch('airports', arrival, search)) return `未知落地机场: ${arrival}`

  const route = routeText.trim()
    ? routeText.trim().toUpperCase().split(/\s+/)
    : [arrival]
  for (const point of [...new Set(route)]) {
    const [waypoint, airport] = await Promise.all([
      exactMatch('waypoints', point, search),
      exactMatch('airports', point, search)
    ])
    if (!waypoint && !airport) return `未知航路点或机场: ${point}`
  }
  return ''
}

export async function validateInitialWaypointReference(
  initialWaypoint: string, search: Search
): Promise<string> {
  const code = initialWaypoint.trim().toUpperCase()
  if (!code) return ''
  const [waypoint, airport] = await Promise.all([
    exactMatch('waypoints', code, search), exactMatch('airports', code, search)
  ])
  return waypoint || airport ? '' : `未知初始航路点: ${code}`
}

async function exactMatch(
  kind: 'airports' | 'waypoints' | 'aircraft-types',
  code: string,
  search: Search
) {
  if (!code) return false
  const matches = await search(kind, code)
  return matches.some(item => item.code.toUpperCase() === code)
}
