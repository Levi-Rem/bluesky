// P18：显示域纯函数（详细设计 2.2 §8.6/§8.7）
// 测距真/磁方位、三单位格式化、历史点裁剪、速度矢量投影、距离环构建。

export interface LatLon {
  lat: number
  lon: number
}

export const NM_PER_DEG_LAT = 60

/** 两点大圆距离（NM，局部球面近似） */
export function distanceNm(from: LatLon, to: LatLon): number {
  const midLat = (from.lat + to.lat) / 2
  const dLat = (to.lat - from.lat) * NM_PER_DEG_LAT
  const dLon = (to.lon - from.lon) * NM_PER_DEG_LAT * Math.cos((midLat * Math.PI) / 180)
  return Math.sqrt(dLat * dLat + dLon * dLon)
}

/** 从 from 指向 to 的真方位（0–360） */
export function bearingTrue(from: LatLon, to: LatLon): number {
  const midLat = (from.lat + to.lat) / 2
  const dLat = to.lat - from.lat
  const dLon = (to.lon - from.lon) * Math.cos((midLat * Math.PI) / 180)
  const deg = (Math.atan2(dLon, dLat) * 180) / Math.PI
  return ((deg % 360) + 360) % 360
}

/** 磁方位 = 真方位 − 磁差（东差为正），回绕 0–360 */
export function bearingMagnetic(trueBearing: number, magneticVariationDeg: number): number {
  return wrap360(trueBearing - magneticVariationDeg)
}

export function wrap360(heading: number): number {
  const wrapped = ((heading % 360) + 360) % 360
  return wrapped === 360 ? 0 : wrapped
}

// ---------------------------------------------------------------- 单位显示（§4.3）

export type UnitMode = 'MET' | 'IMP' | 'MIX'

export function formatAltitude(feet: number, mode: UnitMode): string {
  if (mode === 'IMP' || mode === 'MIX') return `${Math.round(feet)}ft`
  return `${Math.round(feet * 0.3048)}m` // MET
}

export function formatSpeed(kt: number, mode: UnitMode): string {
  if (mode === 'MET') return `${Math.round(kt * 1.852)}km/h`
  return `${Math.round(kt)}kt`
}

export function formatDistance(nm: number, mode: UnitMode): string {
  if (mode === 'MET') return `${(nm * 1.852).toFixed(1)}km`
  return `${nm.toFixed(1)}NM`
}

export function formatVerticalSpeed(fpm: number): string {
  // 三种单位模式的升降率都是 m/s 或 ft/min（§4.3：MET 用 m/s，IMP/MIX 用 ft/min）
  return `${Math.round(fpm)}ft/min`
}

// ---------------------------------------------------------------- 历史点与矢量（§8.6）

export interface TrackPoint extends LatLon {
  atSeconds: number
}

/** 只保留最近 maxPoints 个历史点，按时间升序 */
export function pruneTrackHistory(history: TrackPoint[], maxPoints: number): TrackPoint[] {
  if (history.length <= maxPoints) return [...history]
  return history.slice(history.length - maxPoints)
}

/** 速度矢量：以速度和秒数沿真航向投影终点 */
export function projectVelocityVector(
  position: LatLon,
  trueHeadingDeg: number,
  groundSpeedKt: number,
  seconds: number
): LatLon {
  const distanceNm = (groundSpeedKt * seconds) / 3600
  const rad = (trueHeadingDeg * Math.PI) / 180
  const dLat = (distanceNm * Math.cos(rad)) / NM_PER_DEG_LAT
  const midLat = position.lat + dLat / 2
  const dLon =
    (distanceNm * Math.sin(rad)) / NM_PER_DEG_LAT / Math.max(1e-6, Math.cos((midLat * Math.PI) / 180))
  return { lat: position.lat + dLat, lon: position.lon + dLon }
}

// ---------------------------------------------------------------- 距离环（§8.6）

export function buildRangeRings(
  center: LatLon,
  spacingNm: number,
  ringCount: number
): Array<{ radiusNm: number; label: string }> {
  if (spacingNm <= 0) throw new Error('距离环间距必须大于 0')
  const rings: Array<{ radiusNm: number; label: string }> = []
  for (let i = 1; i <= ringCount; i++) {
    rings.push({ radiusNm: i * spacingNm, label: `${(i * spacingNm).toFixed(0)}NM` })
  }
  return rings
}

export function validateRingSpacing(spacingNm: number): boolean {
  return spacingNm > 0 && spacingNm <= 100
}
