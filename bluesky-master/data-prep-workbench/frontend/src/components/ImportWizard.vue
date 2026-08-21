<template>
  <div class="wizard-mask" @click.self="emit('close')">
    <aside class="wizard">
      <header class="wizard-header">
        <strong>导入 Excel</strong>
        <button class="wizard-close" @click="emit('close')">×</button>
      </header>

      <div class="wizard-body">
        <div class="field">
          <label>数据类型</label>
          <select v-model="entity">
            <option v-for="option in entityOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </div>

        <div class="field">
          <label>Excel 文件（.xlsx）</label>
          <input type="file" accept=".xlsx" @change="onFile" />
          <p class="field-hint">
            请先
            <a :href="templateHref" @click.prevent="downloadTemplate">下载模板</a>
            按列填写后上传
          </p>
        </div>

        <template v-if="result">
          <p class="wizard-summary" :class="{ failed: result.failedRows > 0 }">
            共 {{ result.totalRows }} 行：成功 {{ result.successRows }}，失败 {{ result.failedRows }}
          </p>

          <div v-if="errors.length > 0" class="error-list">
            <div v-for="row in errors" :key="row.id" class="error-row">
              第 {{ row.rowNumber }} 行 · {{ row.errorMessage }}
            </div>
          </div>
        </template>

        <p v-if="errorText" class="wizard-error">{{ errorText }}</p>
      </div>

      <footer class="wizard-footer">
        <button class="ghost-button" @click="emit('close')">关闭</button>
        <button class="primary-button" :disabled="!file || busy" @click="submit">
          {{ busy ? '导入中…' : '开始导入' }}
        </button>
      </footer>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ApiError,
  importErrors,
  importExcel,
  templateUrl,
  type ImportErrorRow,
  type ImportResult
} from '../api/client';
import { drawerConfigs } from '../pages/config';

const props = defineProps<{ defaultEntity: string | null }>();
const emit = defineEmits<{
  close: [];
  imported: [];
  toast: [message: string, tone?: 'ok' | 'error'];
}>();

const entityOptions = Object.values(drawerConfigs).map(config => ({
  value: config.entity,
  label: config.title
}));

const entity = ref(props.defaultEntity && drawerConfigs[props.defaultEntity]
  ? props.defaultEntity
  : 'nav-point');
const file = ref<File | null>(null);
const busy = ref(false);
const result = ref<ImportResult | null>(null);
const errors = ref<ImportErrorRow[]>([]);
const errorText = ref('');

const templateHref = computed(() => templateUrl(entity.value));

function onFile(event: Event) {
  const input = event.target as HTMLInputElement;
  file.value = input.files?.[0] ?? null;
  result.value = null;
  errors.value = [];
}

function downloadTemplate() {
  window.open(templateHref.value, '_blank');
}

async function submit() {
  if (!file.value) {
    return;
  }
  busy.value = true;
  errorText.value = '';
  result.value = null;
  errors.value = [];
  try {
    const summary = await importExcel(entity.value, file.value);
    result.value = summary;
    if (summary.failedRows > 0) {
      errors.value = await importErrors(summary.batchId);
    }
    emit('toast', `导入完成：成功 ${summary.successRows} 行，失败 ${summary.failedRows} 行`,
      summary.failedRows > 0 ? 'error' : 'ok');
    if (summary.successRows > 0) {
      emit('imported');
    }
  } catch (ex) {
    errorText.value = ex instanceof ApiError ? ex.message : '导入失败';
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.wizard-mask {
  position: absolute;
  inset: 0;
  z-index: 80;
  background: rgba(23, 33, 43, 0.28);
}

.wizard {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 420px;
  max-width: 92vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--shadow);
}

.wizard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}

.wizard-close {
  width: 28px;
  height: 28px;
  color: var(--text);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 5px;
}

.wizard-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.field {
  margin-bottom: 10px;
}

.field label {
  display: block;
  margin-bottom: 3px;
  color: var(--muted);
  font-size: 11px;
}

.field select,
.field input[type='file'] {
  width: 100%;
  min-height: 28px;
  padding: 4px 6px;
  color: var(--text);
  background: var(--soft);
  border: 1px solid var(--border);
  border-radius: 5px;
  font-size: 12px;
}

.field-hint {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 11px;
}

.field-hint a {
  color: var(--primary);
}

.wizard-summary {
  margin: 6px 0;
  color: var(--primary);
  font-size: 12px;
}

.wizard-summary.failed {
  color: var(--danger);
}

.error-list {
  max-height: 180px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: 5px;
}

.error-row {
  padding: 4px 8px;
  color: var(--danger);
  border-bottom: 1px solid var(--border);
  font-size: 11px;
}

.wizard-error {
  padding: 6px 8px;
  color: var(--danger);
  background: #fbecee;
  border: 1px solid #e5c2c6;
  border-radius: 5px;
  font-size: 12px;
}

.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  padding: 10px 12px;
  border-top: 1px solid var(--border);
}

.primary-button {
  min-height: 29px;
  padding: 4px 10px;
  color: white;
  background: var(--primary);
  border: 1px solid var(--primary);
  border-radius: 5px;
}

.primary-button:disabled {
  opacity: 0.5;
}

.ghost-button {
  min-height: 29px;
  padding: 4px 10px;
  color: var(--text);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 5px;
}
</style>
