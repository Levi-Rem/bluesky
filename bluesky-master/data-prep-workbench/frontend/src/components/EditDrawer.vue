<template>
  <div class="drawer-mask" @click.self="emit('close')">
    <aside class="drawer">
      <header class="drawer-header">
        <strong>{{ config.title }} · {{ mode === 'view' ? '查看' : recordId ? '编辑' : '新建' }}</strong>
        <button class="drawer-close" @click="emit('close')">×</button>
      </header>

      <div class="drawer-body">
        <p v-if="warning" class="drawer-warning">{{ warning }}</p>

        <div v-for="field in config.fields" :key="field.key" class="field">
          <label>
            {{ field.label }}<template v-if="field.required">*</template>
          </label>

          <select
            v-if="field.type === 'select'"
            v-model="form[field.key]"
            :disabled="readOnly"
          >
            <option v-for="option in field.options" :key="option" :value="option">
              {{ option }}
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
import { ApiError, create, get, list, remove, update } from '../api/client';
import type { DrawerConfig, FieldConfig } from '../pages/config';

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
const errorText = ref('');
const readOnly = computed(() => props.mode === 'view');

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

/** 子表数组 → 打包文本。 */
async function pack(field: FieldConfig, record: Record<string, unknown>): Promise<string> {
  const rows = Array.isArray(record[field.key]) ? (record[field.key] as Record<string, unknown>[]) : [];
  if (field.pack === 'runways') {
    return rows
      .map(r => [r.designation, r.lengthM, r.widthM, r.trueHeadingDeg, r.surface].map(text).join(':'))
      .join(';');
  }
  if (field.pack === 'segments') {
    return rows.map(r => `${text(r.startPointCode)}-${text(r.endPointCode)}`).join(';');
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
      const sites = await list<Record<string, unknown>>('radar-site', 0, 500);
      for (const id of ids) {
        const site = sites.items.find(item => item.id === id);
        if (site) {
          codes.push(String(site.code));
        }
      }
    }
    return codes.join(';');
  }
  return '';
}

function parseNumber(value: string): number | null {
  if (value.trim() === '') {
    return null;
  }
  return Number(value);
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
    const navPage = await list<Record<string, unknown>>('nav-point', 0, 500);
    for (const item of navPage.items) {
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
    const sites = await list<Record<string, unknown>>('radar-site', 0, 500);
    for (const item of sites.items) {
      siteIndex.set(String(item.code), String(item.id));
    }
    return value
      .split(';')
      .map(code => code.trim())
      .filter(Boolean)
      .map(code => siteIndex.get(code))
      .filter((id): id is string => Boolean(id));
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
    for (const field of props.config.fields) {
      if (field.pack) {
        form.value[field.key] = await pack(field, record);
      } else {
        form.value[field.key] = text(record[field.key]);
      }
    }
  } else {
    for (const field of props.config.fields) {
      form.value[field.key] = field.type === 'select' ? (field.options?.[0] ?? '') : '';
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
