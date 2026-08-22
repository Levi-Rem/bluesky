function dmsPart(value: number, degreeWidth: number, positive: string, negative: string): string {
  const hemisphere = value < 0 ? negative : positive;
  const totalSeconds = Math.round(Math.abs(value) * 3600);
  const degrees = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return `${String(degrees).padStart(degreeWidth, '0')}${String(minutes).padStart(2, '0')}`
    + `${String(seconds).padStart(2, '0')}${hemisphere}`;
}

export function decimalCoordinateToDms(latitude: number, longitude: number): string {
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90
      || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    throw new Error('经纬度超出范围');
  }
  return `${dmsPart(latitude, 2, 'N', 'S')}${dmsPart(longitude, 3, 'E', 'W')}`;
}

export interface ParsedDmsCoordinate {
  latitude: number;
  longitude: number;
}

/** 解析纬度在前、经度在后的紧凑 DMS 坐标，例如 042018N1135931E。 */
export function parseDmsCoordinate(value: string): ParsedDmsCoordinate | null {
  const match = value.trim().toUpperCase()
    .match(/^(\d{2})(\d{2})(\d{2})([NS])(\d{3})(\d{2})(\d{2})([EW])$/);
  if (!match) return null;

  const latitudeMinutes = Number(match[2]);
  const latitudeSeconds = Number(match[3]);
  const longitudeMinutes = Number(match[6]);
  const longitudeSeconds = Number(match[7]);
  if (latitudeMinutes > 59 || latitudeSeconds > 59
      || longitudeMinutes > 59 || longitudeSeconds > 59) {
    return null;
  }

  const latitudeUnsigned = Number(match[1]) + latitudeMinutes / 60 + latitudeSeconds / 3600;
  const longitudeUnsigned = Number(match[5]) + longitudeMinutes / 60 + longitudeSeconds / 3600;
  if (latitudeUnsigned > 90 || longitudeUnsigned > 180) return null;

  return {
    latitude: match[4] === 'S' ? -latitudeUnsigned : latitudeUnsigned,
    longitude: match[8] === 'W' ? -longitudeUnsigned : longitudeUnsigned
  };
}

/** GeoJSON Polygon/MultiPolygon 的首个外环转为 DMS 空格点串。 */
export function weatherAreaToDms(value: unknown): string {
  if (typeof value !== 'string' || value.trim() === '') return '';
  try {
    const geometry = JSON.parse(value) as { type?: string; coordinates?: unknown };
    let ring: unknown = geometry.coordinates;
    if (geometry.type === 'MultiPolygon') ring = (ring as unknown[][])?.[0];
    ring = (ring as unknown[][])?.[0];
    if (!Array.isArray(ring)) return value;
    const points = ring as unknown[][];
    const visible = points.length > 1
      && JSON.stringify(points[0]) === JSON.stringify(points[points.length - 1])
      ? points.slice(0, -1)
      : points;
    return visible.map(point => decimalCoordinateToDms(Number(point[1]), Number(point[0]))).join(' ');
  } catch {
    return value;
  }
}
