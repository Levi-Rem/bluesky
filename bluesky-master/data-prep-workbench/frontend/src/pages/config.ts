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

/* ---- 编辑抽屉配置 ---- */

export interface FieldConfig {
  key: string;
  label: string;
  type: 'text' | 'number' | 'select' | 'textarea';
  required?: boolean;
  options?: string[];
  hint?: string;
  /** 打包子表格式：runways/segments/points/boundSites */
  pack?: 'runways' | 'segments' | 'points' | 'boundSites';
}

export interface DrawerConfig {
  entity: string;
  title: string;
  fields: FieldConfig[];
}

/** 行 → 实际后端实体（聚合页按 category/kind 路由）。 */
export function entityOfRow(page: string, row: Record<string, unknown>): string | null {
  if (page === 'weather') {
    if (row.category === 'WIND_FIELD') {
      return 'wind-field';
    }
    return null; // 机场气象/重要天气区域为二期只读
  }
  if (page === 'radar') {
    if (row.kind === 'SITE') {
      return 'radar-site';
    }
    if (row.kind === 'CHANNEL') {
      return 'asterix-channel';
    }
    return null;
  }
  return pages[page]?.entity ?? null;
}

/** 新建按钮可用的实体（聚合页新建风场/雷达站）。 */
export function createEntityOf(page: string): string | null {
  if (page === 'weather') {
    return 'wind-field';
  }
  if (page === 'radar') {
    return 'radar-site';
  }
  return pages[page]?.entity ?? null;
}

const navTypes = ['VOR', 'DME', 'NDB', 'TACAN', 'WAYPOINT', 'FIX'];
const airspaceTypes = ['FIR', 'TMA', 'CTR', 'CTA', 'RESTRICTED', 'DANGER', 'PROHIBITED'];
const windTypes = ['GLOBAL_CONSTANT', 'TWO_DIMENSIONAL', 'THREE_DIMENSIONAL'];
const performanceSources = ['OPENAP', 'BADA', 'LEGACY', 'MANUAL'];
const channelCategories = ['CAT021', 'CAT048', 'CAT062'];

export const drawerConfigs: Record<string, DrawerConfig> = {
  'nav-point': {
    entity: 'nav-point',
    title: '导航点',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'pointType', label: '类型', type: 'select', required: true, options: navTypes },
      { key: 'longitude', label: '经度', type: 'number', required: true },
      { key: 'latitude', label: '纬度', type: 'number', required: true },
      { key: 'elevationM', label: '海拔(米)', type: 'number' },
      { key: 'frequencyMhz', label: '频率(MHz)', type: 'number' },
      { key: 'description', label: '描述', type: 'textarea' }
    ]
  },
  airport: {
    entity: 'airport',
    title: '机场',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'icao', label: 'ICAO', type: 'text' },
      { key: 'iata', label: 'IATA', type: 'text' },
      { key: 'country', label: '国家', type: 'text' },
      { key: 'airportGrade', label: '等级', type: 'text' },
      { key: 'longitude', label: '经度', type: 'number', required: true },
      { key: 'latitude', label: '纬度', type: 'number', required: true },
      { key: 'elevationM', label: '标高(米)', type: 'number' },
      {
        key: 'runways',
        label: '跑道',
        type: 'textarea',
        pack: 'runways',
        hint: '每条跑道：跑道号:长度:宽度:真方位:道面，多条以 ; 分隔'
      }
    ]
  },
  airspace: {
    entity: 'airspace',
    title: '空域',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'airspaceType', label: '类型', type: 'select', required: true, options: airspaceTypes },
      {
        key: 'boundary',
        label: '边界 GeoJSON',
        type: 'textarea',
        required: true,
        hint: 'Polygon 的 GeoJSON 文本'
      },
      { key: 'lowerValue', label: '下限值', type: 'number' },
      { key: 'lowerReference', label: '下限基准', type: 'text' },
      { key: 'upperValue', label: '上限值', type: 'number' },
      { key: 'upperReference', label: '上限基准', type: 'text' }
    ]
  },
  airway: {
    entity: 'airway',
    title: '航路',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      {
        key: 'airwayDirection',
        label: '方向',
        type: 'select',
        required: true,
        options: ['ONE_WAY', 'TWO_WAY']
      },
      { key: 'lowerValue', label: '下限值', type: 'number' },
      { key: 'lowerReference', label: '下限基准', type: 'text' },
      { key: 'upperValue', label: '上限值', type: 'number' },
      { key: 'upperReference', label: '上限基准', type: 'text' },
      {
        key: 'segments',
        label: '航段',
        type: 'textarea',
        pack: 'segments',
        hint: '每段：起点编码-终点编码，多段以 ; 分隔'
      }
    ]
  },
  'wind-field': {
    entity: 'wind-field',
    title: '风场',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'windFieldType', label: '类型', type: 'select', required: true, options: windTypes },
      { key: 'windDirectionDeg', label: '风向(度)', type: 'number' },
      { key: 'windSpeedMs', label: '风速(米/秒)', type: 'number' },
      { key: 'boundary', label: '区域边界 GeoJSON', type: 'textarea' },
      { key: 'effectiveFrom', label: '生效自', type: 'text', hint: 'yyyy-MM-ddTHH:mm:ss' },
      { key: 'effectiveTo', label: '生效至', type: 'text' },
      {
        key: 'points',
        label: '风场点',
        type: 'textarea',
        pack: 'points',
        hint: '每点：经度:纬度:高度:风向:风速，多点以 ; 分隔'
      }
    ]
  },
  performance: {
    entity: 'performance',
    title: '机型性能',
    fields: [
      { key: 'code', label: '机型编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'manufacturer', label: '制造商', type: 'text' },
      { key: 'modelName', label: '型号', type: 'text' },
      {
        key: 'performanceSource',
        label: '性能来源',
        type: 'select',
        required: true,
        options: performanceSources
      },
      { key: 'engineType', label: '发动机类型', type: 'text' },
      {
        key: 'wakeTurbulenceCategory',
        label: '尾流等级',
        type: 'select',
        options: ['L', 'M', 'H', 'J']
      },
      { key: 'maximumTakeoffWeightKg', label: '最大起飞重量(千克)', type: 'number' },
      { key: 'maximumAltitudeFt', label: '最大高度(英尺)', type: 'number' },
      { key: 'maximumMach', label: '最大马赫', type: 'number' },
      { key: 'defaultBankAngleDeg', label: '默认坡度(度)', type: 'number' }
    ]
  },
  'radar-site': {
    entity: 'radar-site',
    title: '逻辑雷达站',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'sac', label: 'SAC(0-255)', type: 'number' },
      { key: 'sic', label: 'SIC(0-255)', type: 'number' },
      { key: 'longitude', label: '经度', type: 'number', required: true },
      { key: 'latitude', label: '纬度', type: 'number', required: true },
      { key: 'altitudeM', label: '天线海拔(米)', type: 'number' },
      { key: 'maximumRangeNm', label: '最大作用距离(海里)', type: 'number' }
    ]
  },
  'asterix-channel': {
    entity: 'asterix-channel',
    title: 'ASTERIX 通道',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      {
        key: 'category',
        label: '类别',
        type: 'select',
        required: true,
        options: channelCategories
      },
      { key: 'edition', label: '版本', type: 'text' },
      { key: 'periodMs', label: '发送周期(毫秒)', type: 'number' },
      {
        key: 'transmissionMode',
        label: '传输方式',
        type: 'select',
        options: ['UNICAST', 'MULTICAST']
      },
      { key: 'destinationIp', label: '目标IP', type: 'text' },
      { key: 'destinationPort', label: '目标端口', type: 'number' },
      { key: 'ttl', label: 'TTL', type: 'number' },
      { key: 'maximumDatagramBytes', label: '最大报文(字节)', type: 'number' },
      { key: 'channelEnabled', label: '通道启用', type: 'select', options: ['true', 'false'] },
      {
        key: 'boundSites',
        label: '绑定雷达站',
        type: 'textarea',
        pack: 'boundSites',
        hint: '雷达站编码，多个以 ; 分隔（CAT048 必填）'
      }
    ]
  }
};
