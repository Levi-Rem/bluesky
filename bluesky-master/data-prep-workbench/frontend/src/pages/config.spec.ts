import { describe, expect, it } from 'vitest';
import { drawerConfigs } from './config';

describe('导航点编辑配置', () => {
  it('与空域信息主列表保持名称、类型、坐标三字段一致', () => {
    const fields = drawerConfigs['nav-point'].fields;
    expect(fields.map(field => field.label)).toEqual(['名称', '类型', '坐标']);
    const typeField = drawerConfigs['nav-point'].fields.find(field => field.key === 'pointType');
    const sourceTypeField = fields.find(field => field.key === 'sourcePointType');
    expect(typeField).toBeUndefined();
    expect(sourceTypeField?.options).toEqual(['REPORT', 'AIRPORT_I', 'VORDME', 'NDB', 'VOR', 'DUMMY']);
  });

  it('航路编辑仅包含名称、航路与类型', () => {
    const fields = drawerConfigs.airway.fields;
    expect(fields.map(field => field.label)).toEqual(['名称', '航路', '类型']);
    expect(fields.find(field => field.key === 'routeType')?.options).toEqual([
      { value: 'CODED_ROUTE', label: '编码航路' },
      { value: 'STAR', label: 'STAR' },
      { value: 'SID', label: 'SID' }
    ]);
  });

  it('物理扇区允许选择边界组成方式并与主表字段一致', () => {
    const fields = drawerConfigs['physical-sector'].fields;
    expect(fields.map(field => field.label)).toEqual(['名称', '类型', '组成方式', '组成', '上限', '下限']);
    expect(fields.find(field => field.key === 'sectorType')?.options).toEqual([
      { value: 'SECTOR', label: '扇区' },
      { value: 'FIR', label: 'FIR' }
    ]);
  });

  it('气象数据编辑与主表保持五个业务字段一致', () => {
    const fields = drawerConfigs.weather.fields;
    expect(fields.map(field => field.label)).toEqual(['名称', '类型', '区域', '下限', '上限']);
    expect(fields.find(field => field.key === 'weatherType')?.options).toEqual([
      { value: 'WIND_SHEAR', label: '风切变' },
      { value: 'MICROBURST', label: '下击暴流' },
      { value: 'JET_STREAM', label: '急流' },
      { value: 'TURBULENCE', label: '湍流' },
      { value: 'ADVECTION_FOG', label: '平流雾' },
      { value: 'RADIATION_FOG', label: '辐射雾' },
      { value: 'THUNDERSTORM', label: '雷雨' }
    ]);
  });
});
