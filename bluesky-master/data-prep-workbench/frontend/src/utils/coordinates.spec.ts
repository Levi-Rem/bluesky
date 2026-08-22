import { describe, expect, it } from 'vitest';
import { decimalCoordinateToDms, parseDmsCoordinate, weatherAreaToDms } from './coordinates';

describe('气象区域坐标格式', () => {
  it('将十进制度转换为纬度在前的 DMS 坐标', () => {
    expect(decimalCoordinateToDms(4.338333, 113.991944)).toBe('042018N1135931E');
  });

  it('将紧凑 DMS 坐标解析为十进制度', () => {
    const east = parseDmsCoordinate('042018N1135931E');
    expect(east?.latitude).toBeCloseTo(4.338333333333334);
    expect(east?.longitude).toBeCloseTo(113.99194444444444);

    const west = parseDmsCoordinate('765950N1405147W');
    expect(west?.latitude).toBeCloseTo(76.99722222222222);
    expect(west?.longitude).toBeCloseTo(-140.86305555555555);
  });

  it('拒绝格式错误或超出范围的 DMS 坐标', () => {
    expect(parseDmsCoordinate('04.338333,113.991944')).toBeNull();
    expect(parseDmsCoordinate('046018N1135931E')).toBeNull();
    expect(parseDmsCoordinate('910000N1135931E')).toBeNull();
  });

  it('将 GeoJSON 外环转换为空格分隔的 DMS 点串并去除闭合重复点', () => {
    const area = '{"type":"Polygon","coordinates":[[[121.7,31.35],[121.95,31.38],[122,31.15],[121.7,31.35]]]}';
    expect(weatherAreaToDms(area)).toBe(
      '312100N1214200E 312248N1215700E 310900N1220000E'
    );
  });
});
