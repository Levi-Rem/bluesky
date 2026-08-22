/** 每个实体页的列与行映射配置。 */

import { weatherAreaToDms } from '../utils/coordinates';

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
  /** 允许完整换行显示的单元格索引。 */
  wrapColumns?: number[];
  /** 单行省略显示的长文本单元格索引。 */
  truncateColumns?: number[];
}

const dash = (value: unknown): string =>
  value === null || value === undefined || value === '' ? '—' : String(value);

const weatherTypeLabels: Record<string, string> = {
  WIND_SHEAR: '风切变',
  MICROBURST: '下击暴流',
  JET_STREAM: '急流',
  TURBULENCE: '湍流',
  ADVECTION_FOG: '平流雾',
  RADIATION_FOG: '辐射雾',
  THUNDERSTORM: '雷雨'
};

export const pages: Record<string, PageConfig> = {
  navigation: {
    entity: 'nav-point',
    columns: ['名称', '类型', '坐标', '操作'],
    cells: row => [
      dash(row.name),
      dash(row.sourcePointType ?? row.pointType),
      dash(row.coordinateText ?? `${row.latitude}, ${row.longitude}`)
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
    columns: ['名称', '航路', '类型', '操作'],
    wrapColumns: [1],
    cells: row => {
      const segments = Array.isArray(row.segments) ? row.segments : [];
      const first = segments[0] as Record<string, unknown> | undefined;
      const route = first
        ? [first.startPointCode, ...segments.map(item => (item as Record<string, unknown>).endPointCode)]
            .map(dash).join(' ')
        : '—';
      const routeTypes: Record<string, string> = {
        CODED_ROUTE: '编码航路',
        SID: 'SID',
        STAR: 'STAR'
      };
      return [
        dash(row.code),
        route,
        routeTypes[String(row.routeType)] ?? '编码航路'
      ];
    }
  },
  physicalSector: {
    entity: 'physical-sector',
    columns: ['名称', '类型', '组成', '上限', '下限', '操作'],
    truncateColumns: [2],
    cells: row => {
      const points = Array.isArray(row.points) ? row.points as Record<string, unknown>[] : [];
      const useNavPoints = row.compositionMode === 'NAV_POINT';
      return [
        dash(row.name),
        row.sectorType === 'FIR' ? 'FIR' : '扇区',
        points.map(point => dash(useNavPoints ? point.pointName : point.coordinateText)).join(' '),
        dash(row.upperLimit),
        dash(row.lowerLimit)
      ];
    }
  },
  weather: {
    entity: 'weather',
    columns: ['名称', '类型', '区域', '下限', '上限', '操作'],
    truncateColumns: [2],
    cells: row => [
      dash(row.name),
      weatherTypeLabels[String(row.weatherType)] ?? dash(row.weatherType),
      dash(weatherAreaToDms(row.area)),
      dash(row.lowerLimit),
      dash(row.upperLimit)
    ]
  },
  performance: {
    entity: 'performance',
    columns: ['机型', 'ICAO尾流', 'RECAT尾流', '高度层', '巡航速度', '爬升率', '下降率', '操作'],
    cells: row => [
      dash(row.code),
      dash(row.icaoWakeCategory),
      dash(row.reacatWakeCategory),
      dash(row.altitudeLayer),
      dash(row.cruiseSpeed),
      dash(row.climbRateFtMin),
      dash(row.descentRateFtMin)
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
  navigation: '空域信息',
  airport: '机场',
  airspace: '空域数据',
  airway: '航路',
  physicalSector: '物理扇区',
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
  options?: Array<string | { value: string; label: string }>;
  hint?: string;
  section?: string;
  /** 打包子表格式：runways/segments/points/boundSites */
  pack?: 'runways' | 'segments' | 'routePath' | 'points' | 'boundSites' | 'physicalSectorPoints' | 'weatherArea';
}

export interface DrawerConfig {
  entity: string;
  title: string;
  fields: FieldConfig[];
  wide?: boolean;
}

/** 行 → 实际后端实体（聚合页按 category/kind 路由）。 */
export function entityOfRow(page: string, row: Record<string, unknown>): string | null {
  if (page === 'weather') {
    return 'weather';
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
    return 'weather';
  }
  if (page === 'radar') {
    return 'radar-site';
  }
  return pages[page]?.entity ?? null;
}

const navTypes = ['REPORT', 'AIRPORT_I', 'VORDME', 'NDB', 'VOR', 'DUMMY'];
const airspaceTypes = ['FIR', 'TMA', 'CTR', 'CTA', 'RESTRICTED', 'DANGER', 'PROHIBITED'];
const channelCategories = ['CAT021', 'CAT048', 'CAT062'];

export const drawerConfigs: Record<string, DrawerConfig> = {
  'nav-point': {
    entity: 'nav-point',
    title: '空域信息',
    fields: [
      { key: 'name', label: '名称', type: 'text', required: true },
      { key: 'sourcePointType', label: '类型', type: 'select', required: true, options: navTypes },
      {
        key: 'coordinateText',
        label: '坐标',
        type: 'text',
        required: true,
        hint: 'DMS 格式，例如 400430N1163524E；也可输入纬度,经度'
      }
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
      { key: 'code', label: '名称', type: 'text', required: true },
      {
        key: 'segments',
        label: '航路',
        type: 'textarea',
        required: true,
        pack: 'routePath',
        hint: '按顺序输入航路点，使用空格分隔，例如：BUNTA P97 LENKO IKELA'
      },
      {
        key: 'routeType',
        label: '类型',
        type: 'select',
        required: true,
        options: [
          { value: 'CODED_ROUTE', label: '编码航路' },
          { value: 'STAR', label: 'STAR' },
          { value: 'SID', label: 'SID' }
        ]
      }
    ]
  },
  'physical-sector': {
    entity: 'physical-sector',
    title: '物理扇区',
    fields: [
      { key: 'name', label: '名称', type: 'text', required: true },
      {
        key: 'sectorType',
        label: '类型',
        type: 'select',
        required: true,
        options: [
          { value: 'SECTOR', label: '扇区' },
          { value: 'FIR', label: 'FIR' }
        ]
      },
      {
        key: 'compositionMode',
        label: '组成方式',
        type: 'select',
        required: true,
        options: [
          { value: 'NAV_POINT', label: '空域信息点' },
          { value: 'COORDINATE', label: '经纬度' }
        ]
      },
      {
        key: 'points',
        label: '组成',
        type: 'textarea',
        required: true,
        pack: 'physicalSectorPoints',
        hint: '空域信息点使用名称、经纬度使用 DMS 坐标，均以空格分隔；首尾由系统自动闭合'
      },
      { key: 'upperLimit', label: '上限', type: 'text', required: true, hint: '例如 S0920' },
      { key: 'lowerLimit', label: '下限', type: 'text', required: true, hint: '例如 S0000 或 S0450' }
    ]
  },
  weather: {
    entity: 'weather',
    title: '气象数据',
    fields: [
      { key: 'name', label: '名称', type: 'text', required: true },
      {
        key: 'weatherType',
        label: '类型',
        type: 'select',
        required: true,
        options: [
          { value: 'WIND_SHEAR', label: '风切变' },
          { value: 'MICROBURST', label: '下击暴流' },
          { value: 'JET_STREAM', label: '急流' },
          { value: 'TURBULENCE', label: '湍流' },
          { value: 'ADVECTION_FOG', label: '平流雾' },
          { value: 'RADIATION_FOG', label: '辐射雾' },
          { value: 'THUNDERSTORM', label: '雷雨' }
        ]
      },
      {
        key: 'area',
        label: '区域',
        type: 'textarea',
        required: true,
        pack: 'weatherArea',
        hint: 'DMS 经纬度，各点以空格分隔，例如：042018N1135931E 042500N1140000E 041800N1141000E'
      },
      { key: 'lowerLimit', label: '下限', type: 'text', required: true, hint: 'S 高度编码，例如 S0100' },
      { key: 'upperLimit', label: '上限', type: 'text', required: true, hint: 'S 高度编码，例如 S3000' }
    ]
  },
  'wind-field': {
    entity: 'wind-field',
    title: '风场',
    fields: [
      { key: 'code', label: '编码', type: 'text', required: true },
      { key: 'name', label: '名称', type: 'text', required: true },
      {
        key: 'windFieldType',
        label: '类型',
        type: 'select',
        required: true,
        options: ['GLOBAL_CONSTANT', 'TWO_DIMENSIONAL', 'THREE_DIMENSIONAL']
      },
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
    wide: true,
    fields: [
      { key: 'code', label: '机型编码', type: 'text', required: true, section: '机型基本信息' },
      { key: 'name', label: '名称', type: 'text', required: true, section: '机型基本信息' },
      { key: 'manufacturer', label: '制造商', type: 'text', section: '机型基本信息' },
      { key: 'modelName', label: '型号', type: 'text', section: '机型基本信息' },
      { key: 'engineType', label: '发动机类型', type: 'text', section: '机型基本信息' },
      { key: 'icaoWakeCategory', label: 'ICAO尾流类别', type: 'select', options: ['L', 'M', 'H', 'J'], section: '机型基本信息' },
      { key: 'reacatWakeCategory', label: 'RECAT尾流类别', type: 'select', options: ['B', 'C', 'H', 'J', 'L', 'M'], section: '机型基本信息' },
      { key: 'maximumTakeoffWeightKg', label: '最大起飞重量(千克)', type: 'number', section: '机型基本信息' },
      { key: 'performanceCategory', label: '性能类别', type: 'select', options: ['H', 'L'], section: '机型基本信息' },

      { key: 'altitudeLayer', label: '高度层', type: 'text', required: true, section: '当前高度层性能', hint: '例如 F20、F100、F450' },
      { key: 'cruiseSpeed', label: '巡航速度', type: 'text', section: '当前高度层性能' },
      { key: 'stallSpeed', label: '失速速度', type: 'text', section: '当前高度层性能' },
      { key: 'climbSpeed', label: '爬升速度', type: 'text', section: '当前高度层性能' },
      { key: 'descentSpeed', label: '下降速度', type: 'text', section: '当前高度层性能' },
      { key: 'climbRateFtMin', label: '爬升率(ft/min)', type: 'number', section: '当前高度层性能' },
      { key: 'descentRateFtMin', label: '下降率(ft/min)', type: 'number', section: '当前高度层性能' },
      { key: 'accelerationKtsMin', label: '加速度(kts/min)', type: 'number', section: '当前高度层性能' },
      { key: 'decelerationKtsMin', label: '减速度(kts/min)', type: 'number', section: '当前高度层性能' },

      { key: 'holdingSpeedLow', label: '低高度等待速度', type: 'text', section: '通用性能' },
      { key: 'holdingSpeedMiddle', label: '中高度等待速度', type: 'text', section: '通用性能' },
      { key: 'holdingSpeedHigh', label: '高高度等待速度', type: 'text', section: '通用性能' },
      { key: 'takeoffSpeed', label: '起飞速度', type: 'text', section: '通用性能' },
      { key: 'takeoffDurationS', label: '起飞时间(秒)', type: 'number', section: '通用性能' },
      { key: 'takeoffAltitudeFt', label: '起飞高度(英尺)', type: 'number', section: '通用性能' },
      { key: 'takeoffDistanceNm', label: '起飞距离(海里)', type: 'number', section: '通用性能' },
      { key: 'landingSpeed', label: '着陆速度', type: 'text', section: '通用性能' },
      { key: 'radarCrossSection', label: '雷达反射截面积', type: 'number', section: '通用性能' },
      { key: 'maximumSpeed', label: '最大速度', type: 'text', section: '通用性能' },
      { key: 'maximumAltitudeLayer', label: '最大高度', type: 'text', section: '通用性能' },
      { key: 'maximumTurn', label: '最大转弯参数', type: 'number', section: '通用性能' },
      { key: 'standardTurn', label: '标准转弯参数', type: 'number', section: '通用性能' },
      { key: 'machCapable', label: '支持马赫数', type: 'select', options: [{ value: 'true', label: '是' }, { value: 'false', label: '否' }], section: '通用性能' },
      { key: 'jetAircraft', label: '喷气机', type: 'select', options: [{ value: 'true', label: '是' }, { value: 'false', label: '否' }], section: '通用性能' }
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
