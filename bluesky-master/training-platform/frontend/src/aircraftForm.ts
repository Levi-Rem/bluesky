export function validateAircraftForm(
  callsign: string, appearanceOffset: string, transponderCode = ''
): string {
  if (!/^[A-Za-z0-9]{2,7}$/.test(callsign.trim())) {
    return '呼号必须是 2 至 7 位英文字母或数字'
  }
  if (!/^\d{4}$/.test(appearanceOffset)) {
    return '出现时间必须是四位数字，例如 0010'
  }
  if (transponderCode && (!/^[0-7]{4}$/.test(transponderCode) || transponderCode === '0000')) {
    return '二次代码必须是四位八进制数且不能为 0000'
  }
  return ''
}

export function validateAircraftPosition(
  latitude: number | string, longitude: number | string, initialWaypoint: string
): string {
  if (initialWaypoint.trim()) return ''
  const hasLatitude = latitude !== '' && latitude !== null && latitude !== undefined
  const hasLongitude = longitude !== '' && longitude !== null && longitude !== undefined
  if (hasLatitude !== hasLongitude) return '纬度和经度必须同时填写'
  if (!hasLatitude) return '必须填写经纬度或初始航路点'
  return ''
}

export interface AircraftFormValues {
  callsign: string
  aircraftType: string
  wakeCategory: string
  transponderCode: string
  origin: string
  destination: string
  appearanceOffset: string
  latitude: number | string
  longitude: number | string
  initialWaypoint: string
  headingDegrees: number
  altitudeFeet: number
  speedKnots: number
  route: string
}

export function buildAircraftPayload(form: AircraftFormValues): Record<string, unknown> {
  const normalizedDestination = form.destination.trim().toUpperCase()
  const initialWaypoint = form.initialWaypoint.trim().toUpperCase()
  return {
    callsign: form.callsign,
    aircraftType: form.aircraftType,
    wakeCategory: form.wakeCategory,
    transponderCode: form.transponderCode,
    origin: form.origin,
    destination: form.destination,
    appearanceOffsetMinutes: form.appearanceOffset,
    latitude: initialWaypoint ? null : optionalNumber(form.latitude),
    longitude: initialWaypoint ? null : optionalNumber(form.longitude),
    initialWaypoint: initialWaypoint || null,
    headingDegrees: form.headingDegrees,
    altitudeFeet: form.altitudeFeet,
    speedKnots: form.speedKnots,
    route: form.route.trim()
      ? form.route.trim().toUpperCase().split(/\s+/)
      : [normalizedDestination]
  }
}

function optionalNumber(value: number | string): number | null {
  if (value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
