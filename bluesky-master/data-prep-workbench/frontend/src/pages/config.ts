/** 每个实体页的列与行映射配置。 */

export interface EntityRecord {
  id: string;
  code: string;
  name: string;
  revision: number;
  status?: string;
  sourceType?: string;
  [key: string]: unknown;
}

export interface PageConfig {
  /** 后端实体路由名（/api/{entity}）；weather/radar 为聚合端点 */
  entity: string;
  columns: string[];
  /** 行 → 展示单元格（不含操作列） */
  cells: (row: Record<string, unknown>) => string[];
}

const dash = (value: unknown): string =>
  value === null || value === undefined || value === '' ? '—' : String(value);

export const pages: Record<string, PageConfig> = {
  navigation: {
    entity: 'nav-point',
    columns: ['标识', '名称', '类型', '纬度', '经度', '频率', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.name),
      dash(row.pointType),
      dash(row.latitude),
      dash(row.longitude),
      row.frequencyMhz ? `${row.frequencyMhz} MHz` : '—'
    ]
  },
  airport: {
    entity: 'airport',
    columns: ['标识', '名称', 'ICAO', '标高(米)', '跑道', '状态', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.name),
      dash(row.icao),
      dash(row.elevationM),
      Array.isArray(row.runways) ? String(row.runways.length) : '—',
      row.status === 'ENABLED' ? '启用' : '停用'
    ]
  },
  airspace: {
    entity: 'airspace',
    columns: ['标识', '名称', '类型', '下限', '上限', '状态', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.name),
      dash(row.airspaceType),
      row.lowerValue ? `${dash(row.lowerReference)} ${row.lowerValue}` : 'SFC',
      row.upperValue ? `${dash(row.upperReference)} ${row.upperValue}` : 'UNL',
      row.status === 'ENABLED' ? '启用' : '停用'
    ]
  },
  airway: {
    entity: 'airway',
    columns: ['航路', '起点', '终点', '方向', '最低高度', '最高高度', '操作'],
    cells: row => {
      const segments = Array.isArray(row.segments) ? row.segments : [];
      const first = segments[0] as Record<string, unknown> | undefined;
      const last = segments[segments.length - 1] as Record<string, unknown> | undefined;
      return [
        dash(row.code),
        dash(first?.startPointCode),
        dash(last?.endPointCode),
        row.airwayDirection === 'TWO_WAY' ? '双向' : '单向',
        dash(row.lowerValue),
        dash(row.upperValue)
      ];
    }
  },
  weather: {
    entity: 'weather',
    columns: ['标识', '数据类型', '关联区域', '有效时间', '状态', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.dataType),
      dash(row.relatedArea),
      dash(row.effectiveFrom),
      dash(row.status)
    ]
  },
  performance: {
    entity: 'performance',
    columns: ['机型', '制造商', '性能来源', '最大高度', '最大马赫数', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.manufacturer),
      row.performanceSource === 'OPENAP'
        ? 'OpenAP'
        : row.performanceSource === 'BADA'
          ? 'BADA'
          : '人工维护',
      row.maximumAltitudeFt ? `FL${Math.round(Number(row.maximumAltitudeFt) / 100)}` : '—',
      dash(row.maximumMach)
    ]
  },
  radar: {
    entity: 'radar',
    columns: ['标识', '名称', '数据类型', 'SAC/SIC', '网络端点', '状态', '操作'],
    cells: row => {
      const sac = row.sac === null || row.sac === undefined ? null : String(row.sac).padStart(3, '0');
      const sic = row.sic === null || row.sic === undefined ? null : String(row.sic).padStart(3, '0');
      return [
        dash(row.code),
        dash(row.name),
        dash(row.dataType),
        sac && sic ? `${sac}/${sic}` : '—',
        dash(row.networkEndpoint),
        row.status === 'ENABLED' ? '启用' : '停用'
      ];
    }
  }
};

export const pageTitles: Record<string, string> = {
  navigation: '导航数据',
  airport: '机场',
  airspace: '空域数据',
  airway: '航路信息',
  weather: '气象数据',
  performance: '机型性能',
  radar: '雷达与通道'
};
