<template>
  <div class="drawer-mask" @click.self="emit('close')">
    <aside class="drawer" :class="{ wide: config.wide }">
      <header class="drawer-header">
        <strong>{{ drawerTitle }}</strong>
        <button class="drawer-close" @click="emit('close')">×</button>
      </header>

      <div class="drawer-body">
        <p v-if="warning" class="drawer-warning">{{ warning }}</p>

        <section v-for="group in fieldGroups" :key="group.title" class="field-section">
          <h3 v-if="group.title">{{ group.title }}</h3>
          <div class="fields-grid" :class="{ compact: config.wide }">
            <div v-for="field in group.fields" :key="field.key" class="field">
              <label>
                {{ field.label }}<template v-if="field.required">*</template>
              </label>

              <select
                v-if="field.type === 'select'"
                v-model="form[field.key]"
                :disabled="readOnly"
              >
                <option v-if="!field.required" value="">—</option>
                <option
                  v-for="option in field.options"
                  :key="optionValue(option)"
                  :value="optionValue(option)"
                >
                  {{ optionLabel(option) }}
                </option>
              </select>

              <textarea
                v-else-if="field.type === 'textarea'"
                v-model="form[field.key]"
                :disabled="readOnly"
                :placeholder="field.hint"
                rows="3"
              ></textarea>

              <input
                v-else
                v-model="form[field.key]"
                :disabled="readOnly"
                :type="field.type === 'number' ? 'number' : 'text'"
                :step="field.type === 'number' ? 'any' : undefined"
              />

              <p v-if="field.hint && field.type !== 'textarea'" class="field-hint">{{ field.hint }}</p>
            </div>
          </div>
        </section>

        <section v-if="config.entity === 'performance'" class="field-section">
          <h3>响应参数</h3>
          <table class="response-table">
            <thead><tr><th>响应类型</th><th>参数1</th><th>参数2</th><th>参数3</th></tr></thead>
            <tbody>
              <tr v-for="row in responseRows" :key="row.label">
                <td>{{ row.label }}</td>
                <td v-for="key in row.keys" :key="key">
                  <input v-model="form[key]" :disabled="readOnly" type="number" />
                </td>
              </tr>
            </tbody>
          </table>
          <p class="field-hint">修改通用性能或响应参数后，将同步到该机型的全部高度层。</p>
        </section>

        <p v-if="errorText" class="drawer-error">{{ errorText }}</p>
      </div>

      <footer class="drawer-footer" v-if="!readOnly">
        <button class="danger-button" v-if="mode === 'edit'" @click="doDelete">删除</button>
        <span class="spacer"></span>
        <button class="ghost-button" @click="emit('close')">取消</button>
        <button class="primary-button" @click="save">保存</button>
      </footer>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ApiError, create, get, listAll, remove, update } from '../api/client';
import type { DrawerConfig, FieldConfig } from '../pages/config';
import { weatherAreaToDms } from '../utils/coordinates';

const props = defineProps<{
  config: DrawerConfig;
  mode: 'view' | 'edit' | 'create';
  recordId?: string;
  /** 编辑已有记录的 revision（乐观锁） */
  revision?: number;
  /** BLUESKY 来源等只读提示 */
  warning?: string;
}>();

const emit = defineEmits<{ close: []; saved: []; toast: [message: string, tone?: 'ok' | 'error'] }>();

const form = ref<Record<string, string>>({});
const loadedRecord = ref<Record<string, unknown> | null>(null);
const errorText = ref('');
const readOnly = computed(() => props.mode === 'view');
const responseRows = [
  { label: '转弯', keys: ['turnResponse1', 'turnResponse2', 'turnResponse3'] },
  { label: '加速', keys: ['accelerationResponse1', 'accelerationResponse2', 'accelerationResponse3'] },
  { label: '减速', keys: ['decelerationResponse1', 'decelerationResponse2', 'decelerationResponse3'] },
  { label: '爬升', keys: ['climbResponse1', 'climbResponse2', 'climbResponse3'] },
  { label: '下降', keys: ['descentResponse1', 'descentResponse2', 'descentResponse3'] }
];
const responseKeys = responseRows.flatMap(row => row.keys);
const fieldGroups = computed(() => {
  const groups: Array<{ title: string; fields: FieldConfig[] }> = [];
  for (const field of props.config.fields) {
    const title = field.section ?? '';
    let group = groups.find(item => item.title === title);
    if (!group) {
      group = { title, fields: [] };
      groups.push(group);
    }
    group.fields.push(field);
  }
  return groups;
});
const drawerTitle = computed(() => {
  const action = props.mode === 'view' ? '查看' : props.recordId ? '编辑' : '新建';
  if (props.config.entity === 'performance' && loadedRecord.value) {
    return `${text(loadedRecord.value.code)} · ${text(loadedRecord.value.altitudeLayer)} · ${action}`;
  }
  return `${props.config.title} · ${action}`;
});

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

function optionValue(option: string | { value: string; label: string }): string {
  return typeof option === 'string' ? option : option.value;
}

function optionLabel(option: string | { value: string; label: string }): string {
  return typeof option === 'string' ? option : option.label;
}

/** 子表数组 → 打包文本。 */
async function pack(field: FieldConfig, record: Record<string, unknown>): Promise<string> {
  const rows = Array.isArray(record[field.key]) ? (record[field.key] as Record<string, unknown>[]) : [];
  if (field.pack === 'weatherArea') {
    return weatherAreaToDms(record[field.key]);
  }
  if (field.pack === 'runways') {
    return rows
      .map(r => [r.designation, r.lengthM, r.widthM, r.trueHeadingDeg, r.surface].map(text).join(':'))
      .join(';');
  }
  if (field.pack === 'segments') {
    return rows.map(r => `${text(r.startPointCode)}-${text(r.endPointCode)}`).join(';');
  }
  if (field.pack === 'routePath') {
    if (rows.length === 0) return '';
    return [rows[0].startPointCode, ...rows.map(row => row.endPointCode)].map(text).join(' ');
  }
  if (field.pack === 'points') {
    return rows
      .map(r =>
        [r.longitude, r.latitude, r.altitudeM, r.windDirectionDeg, r.windSpeedMs].map(text).join(':')
      )
      .join(';');
  }
  if (field.pack === 'boundSites') {
    const ids = Array.isArray(record.boundSiteIds) ? (record.boundSiteIds as string[]) : [];
    const codes: string[] = [];
    if (ids.length > 0) {
      const sites = await listAll<Record<string, unknown>>('radar-site');
      for (const id of ids) {
        const site = sites.find(item => item.id === id);
        if (site) {
          codes.push(String(site.code));
        }
      }
    }
    return codes.join(';');
  }
  if (field.pack === 'physicalSectorPoints') {
    const useNavPoints = record.compositionMode === 'NAV_POINT';
    return rows.map(row => text(useNavPoints ? row.pointName : row.coordinateText)).join(' ');
  }
  return '';
}

function parseNumber(value: string): number | null {
  if (value.trim() === '') {
    return null;
  }
  return Number(value);
}

function parseNavigationCoordinate(value: string): { latitude: number; longitude: number } {
  const dms = value.trim().match(/^(\d{2})(\d{2})(\d{2})([NS])(\d{3})(\d{2})(\d{2})([EW])$/i);
  if (dms) {
    const latitude = Number(dms[1]) + Number(dms[2]) / 60 + Number(dms[3]) / 3600;
    const longitude = Number(dms[5]) + Number(dms[6]) / 60 + Number(dms[7]) / 3600;
    if (Number(dms[2]) > 59 || Number(dms[3]) > 59 || Number(dms[6]) > 59 || Number(dms[7]) > 59) {
      throw new ApiError(400, '坐标中的分、秒必须小于 60');
    }
    return {
      latitude: dms[4].toUpperCase() === 'S' ? -latitude : latitude,
      longitude: dms[8].toUpperCase() === 'W' ? -longitude : longitude
    };
  }
  const decimal = value.trim().match(/^(-?\d+(?:\.\d+)?)\s*[,，]\s*(-?\d+(?:\.\d+)?)$/);
  if (decimal) {
    const latitude = Number(decimal[1]);
    const longitude = Number(decimal[2]);
    if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
      return { latitude, longitude };
    }
  }
  throw new ApiError(400, '坐标格式错误，请输入 DMS 坐标或“纬度,经度”');
}

function standardPointType(sourceType: unknown): string {
  const mappings: Record<string, string> = {
    REPORT: 'FIX',
    AIRPORT_I: 'AIRPORT',
    VORDME: 'VOR_DME',
    NDB: 'NDB',
    VOR: 'VOR',
    DUMMY: 'OTHER'
  };
  return mappings[String(sourceType)] ?? 'OTHER';
}

/** 打包文本 → 后端子表结构。 */
async function unpack(field: FieldConfig, raw: unknown): Promise<unknown> {
  const value = text(raw).trim();
  if (field.pack === 'runways') {
    return value
      .split(';')
      .filter(Boolean)
      .map(item => {
        const [designation, lengthM, widthM, trueHeadingDeg, surface] = item.split(':');
        return {
          designation: designation?.trim() ?? '',
          lengthM: parseNumber(lengthM ?? ''),
          widthM: parseNumber(widthM ?? ''),
          trueHeadingDeg: parseNumber(trueHeadingDeg ?? ''),
          surface: surface?.trim() || null
        };
      });
  }
  if (field.pack === 'segments') {
    const navIndex = new Map<string, string>();
    const navPoints = await listAll<Record<string, unknown>>('nav-point');
    for (const item of navPoints) {
      navIndex.set(String(item.code), String(item.id));
    }
    return value
      .split(';')
      .filter(Boolean)
      .map(item => {
        const [start, end] = item.split('-');
        return {
          startPointId: navIndex.get(start?.trim() ?? ''),
          endPointId: navIndex.get(end?.trim() ?? '')
        };
      })
      .filter(seg => seg.startPointId && seg.endPointId);
  }
  if (field.pack === 'routePath') {
    const navIndex = new Map<string, string>();
    const navPoints = await listAll<Record<string, unknown>>('nav-point');
    for (const item of navPoints) {
      navIndex.set(String(item.code), String(item.id));
    }
    const codes = value.split(/\s*(?:→|>|,|，|;)\s*|\s+/).map(code => code.trim()).filter(Boolean);
    if (codes.length < 2) {
      throw new ApiError(400, '航路至少需要两个航路点');
    }
    const missing = codes.filter(code => !navIndex.has(code));
    if (missing.length > 0) {
      throw new ApiError(400, `航路点不存在：${[...new Set(missing)].join('、')}`);
    }
    return codes.slice(0, -1).map((code, index) => ({
      startPointId: navIndex.get(code),
      endPointId: navIndex.get(codes[index + 1])
    })).filter(segment => segment.startPointId && segment.endPointId);
  }
  if (field.pack === 'points') {
    return value
      .split(';')
      .filter(Boolean)
      .map(item => {
        const [longitude, latitude, altitudeM, windDirectionDeg, windSpeedMs] = item.split(':');
        return {
          longitude: parseNumber(longitude ?? ''),
          latitude: parseNumber(latitude ?? ''),
          altitudeM: parseNumber(altitudeM ?? ''),
          windDirectionDeg: parseNumber(windDirectionDeg ?? ''),
          windSpeedMs: parseNumber(windSpeedMs ?? '')
        };
      })
      .filter(point => point.longitude !== null && point.latitude !== null);
  }
  if (field.pack === 'boundSites') {
    const siteIndex = new Map<string, string>();
    const sites = await listAll<Record<string, unknown>>('radar-site');
    for (const item of sites) {
      siteIndex.set(String(item.code), String(item.id));
    }
    return value
      .split(';')
      .map(code => code.trim())
      .filter(Boolean)
      .map(code => siteIndex.get(code))
      .filter((id): id is string => Boolean(id));
  }
  if (field.pack === 'physicalSectorPoints') {
    const tokens = value.split(/\s+/).map(item => item.trim()).filter(Boolean);
    if (tokens.length < 3) {
      throw new ApiError(400, '物理扇区组成至少需要三个边界点');
    }
    if (form.value.compositionMode === 'NAV_POINT') {
      const points = await listAll<Record<string, unknown>>('nav-point');
      const pointIndex = new Map<string, Record<string, unknown>>();
      for (const point of points) {
        pointIndex.set(String(point.name), point);
        pointIndex.set(String(point.code), point);
      }
      const missing = tokens.filter(name => !pointIndex.has(name));
      if (missing.length > 0) {
        throw new ApiError(400, `空域信息点不存在：${[...new Set(missing)].join('、')}`);
      }
      return tokens.map(name => ({ navPointId: String(pointIndex.get(name)?.id ?? '') }));
    }
    return tokens.map(coordinateText => ({ coordinateText }));
  }
  return raw;
}

async function buildBody(): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = {};
  for (const field of props.config.fields) {
    const raw = form.value[field.key];
    if (field.pack === 'boundSites') {
      body.boundSiteIds = await unpack(field, raw);
      continue;
    }
    if (field.pack) {
      body[field.key] = await unpack(field, raw);
      continue;
    }
    if (field.type === 'number') {
      body[field.key] = parseNumber(text(raw));
    } else {
      body[field.key] = text(raw) === '' ? null : text(raw);
    }
  }
  if (props.mode === 'edit') {
    body.revision = props.revision ?? 0;
  }
  if (props.config.entity === 'performance') {
    for (const key of responseKeys) body[key] = parseNumber(text(form.value[key]));
  }
  if (props.config.entity === 'airway') {
    body.name = body.code;
    body.airwayDirection = body.routeType === 'CODED_ROUTE' ? 'TWO_WAY' : 'ONE_WAY';
  }
  if (props.config.entity === 'nav-point') {
    const prior = loadedRecord.value ?? {};
    const coordinate = parseNavigationCoordinate(String(body.coordinateText ?? ''));
    body.code = prior.code ?? body.name;
    body.pointType = standardPointType(body.sourcePointType);
    body.longitude = coordinate.longitude;
    body.latitude = coordinate.latitude;
    for (const key of [
      'elevationM', 'frequencyMhz', 'magneticVariationDeg', 'description', 'relevantFlag',
      'applicableAirports', 'pilotFlag', 'dtiFlag', 'tfmFlag', 'status', 'sourceReference'
    ]) {
      if (prior[key] !== undefined) body[key] = prior[key];
    }
  }
  return body;
}

async function save() {
  errorText.value = '';
  try {
    const body = await buildBody();
    if (props.mode === 'create') {
      await create(props.config.entity, body);
    } else if (props.recordId) {
      await update(props.config.entity, props.recordId, body);
    }
    emit('toast', `${props.config.title}已保存`);
    emit('saved');
    emit('close');
  } catch (ex) {
    errorText.value = ex instanceof ApiError ? ex.message : '保存失败';
  }
}

async function doDelete() {
  if (!props.recordId) {
    return;
  }
  if (!window.confirm(`确认删除该${props.config.title}？`)) {
    return;
  }
  errorText.value = '';
  try {
    await remove(props.config.entity, props.recordId, props.revision ?? 0);
    emit('toast', `${props.config.title}已删除`);
    emit('saved');
    emit('close');
  } catch (ex) {
    errorText.value = ex instanceof ApiError ? ex.message : '删除失败';
  }
}

onMounted(async () => {
  if (props.recordId) {
    const record = await get<Record<string, unknown>>(props.config.entity, props.recordId);
    loadedRecord.value = record;
    for (const field of props.config.fields) {
      if (field.pack) {
        form.value[field.key] = await pack(field, record);
      } else if (props.config.entity === 'nav-point' && field.key === 'sourcePointType') {
        form.value[field.key] = text(record.sourcePointType ?? record.pointType);
      } else if (props.config.entity === 'nav-point' && field.key === 'coordinateText') {
        form.value[field.key] = text(record.coordinateText)
          || `${text(record.latitude)}, ${text(record.longitude)}`;
      } else {
        form.value[field.key] = text(record[field.key]);
      }
    }
    if (props.config.entity === 'performance') {
      for (const key of responseKeys) form.value[key] = text(record[key]);
    }
  } else {
    for (const field of props.config.fields) {
      form.value[field.key] = field.type === 'select' && field.options?.[0]
        ? optionValue(field.options[0])
        : '';
    }
    if (props.config.entity === 'performance') {
      for (const key of responseKeys) form.value[key] = '';
    }
  }
});
</script>

<style scoped>
.drawer-mask {
  position: absolute;
  inset: 0;
  z-index: 70;
  background: rgba(23, 33, 43, 0.28);
}

.drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 360px;
  max-width: 92vw;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow);
}

.drawer.wide {
  width: 720px;
  max-width: 96vw;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.drawer-close {
  width: 28px;
  height: 28px;
  color: var(--text);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 5px;
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.field {
  margin-bottom: 8px;
}

.field-section {
  margin-bottom: 10px;
}

.field-section h3 {
  margin: 0 0 7px;
  padding-bottom: 4px;
  color: var(--text);
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.fields-grid.compact {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 10px;
}

.response-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}

.response-table th,
.response-table td {
  padding: 3px 5px;
  text-align: left;
  border: 1px solid var(--border);
}

.response-table input {
  width: 100%;
  min-height: 26px;
  padding: 3px 5px;
  color: var(--text);
  background: var(--soft);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 11px;
}

.field label {
  display: block;
  margin-bottom: 3px;
  color: var(--muted);
  font-size: 11px;
}

.field input,
.field select,
.field textarea {
  width: 100%;
  min-height: 28px;
  padding: 4px 6px;
  color: var(--text);
  background: var(--soft);
  border: 1px solid var(--border);
  border-radius: 5px;
  font-size: 12px;
}

.field textarea {
  min-height: 52px;
  resize: vertical;
}

.field input:disabled,
.field select:disabled,
.field textarea:disabled {
  opacity: 0.65;
}

.field-hint {
  margin: 2px 0 0;
  color: var(--muted);
  font-size: 11px;
}

.drawer-warning {
  padding: 6px 8px;
  color: var(--danger);
  background: #fbecee;
  border: 1px solid #e5c2c6;
  border-radius: 5px;
  font-size: 12px;
}

.drawer-error {
  padding: 6px 8px;
  color: var(--danger);
  background: #fbecee;
  border: 1px solid #e5c2c6;
  border-radius: 5px;
  font-size: 12px;
  white-space: pre-wrap;
}

.drawer-footer {
  display: flex;
  gap: 6px;
  padding: 10px 12px;
  border-top: 1px solid var(--border);
}

.spacer {
  flex: 1;
}

.primary-button {
  min-height: 29px;
  padding: 4px 10px;
  color: white;
  background: var(--primary);
  border: 1px solid var(--primary);
  border-radius: 5px;
}

.ghost-button {
  min-height: 29px;
  padding: 4px 10px;
  color: var(--text);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 5px;
}

.danger-button {
  min-height: 29px;
  padding: 4px 10px;
  color: var(--danger);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 5px;
}
</style>
