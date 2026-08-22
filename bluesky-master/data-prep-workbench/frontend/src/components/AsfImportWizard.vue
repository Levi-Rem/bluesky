<template>
  <div class="asf-mask" @click.self="emit('close')">
    <aside class="asf-dialog">
      <header><strong>导入运行系统 ASF</strong><button @click="emit('close')">×</button></header>
      <div class="asf-body">
        <p class="hint">可导入特征点与航路、FDP 体积定义或飞机性能。各数据集分别原子替换，其他数据不变。</p>
        <label>特征点文件（CHARACTERISTIC_POINTS.ASF）</label>
        <input type="file" accept=".asf,.ASF" @change="points = selected($event)" />
        <label>航路文件（ROUTES.ASF）</label>
        <input type="file" accept=".asf,.ASF" @change="routes = selected($event)" />
        <label>物理扇区文件（FDP_VOLUMES_DEFINITION.ASF）</label>
        <input type="file" accept=".asf,.ASF" @change="fdpVolumes = selected($event)" />
        <label>飞机性能文件（AIRCRAFT_PERFORMANCES.ASF）</label>
        <input type="file" accept=".asf,.ASF" @change="aircraftPerformances = selected($event)" />
        <label class="confirm"><input v-model="confirmed" type="checkbox" /> 我已确认替换所选数据集</label>
        <p v-if="errorText" class="error">{{ errorText }}</p>
        <template v-if="result">
          <p class="summary">
            已导入 {{ result.navigationPointCount }} 个航路点、{{ result.airwayCount }} 条航路：
            编码航路 {{ result.codedRouteCount }}、SID {{ result.sidCount }}、STAR {{ result.starCount }}；
            共 {{ result.airwaySegmentCount }} 个航段。
          </p>
          <details v-if="result.duplicateDefinitionCount">
            <summary>{{ result.duplicateDefinitionCount }} 条重复/冲突定义（均保留首条）</summary>
            <div class="conflicts"><div v-for="item in result.duplicateDefinitions" :key="item">{{ item }}</div></div>
          </details>
        </template>
        <p v-if="sectorResult" class="summary">
          已从 {{ sectorResult.sourceSectorCount }} 个扇区、{{ sectorResult.sourceFirCount }} 个 FIR
          生成 {{ sectorResult.regionCount }} 个独立区域，共 {{ sectorResult.boundaryPointCount }} 个边界点。
        </p>
        <template v-if="performanceResult">
          <p class="summary">
            已导入 {{ performanceResult.aircraftTypeCount }} 个机型、
            {{ performanceResult.performanceRowCount }} 条高度层性能记录。
          </p>
          <details v-if="performanceResult.warnings.length">
            <summary>{{ performanceResult.warnings.length }} 条数据对齐提示</summary>
            <div class="conflicts"><div v-for="item in performanceResult.warnings" :key="item">{{ item }}</div></div>
          </details>
        </template>
      </div>
      <footer><button @click="emit('close')">关闭</button><button class="primary" :disabled="!ready || busy" @click="submit">{{ busy ? '导入中…' : '开始导入' }}</button></footer>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ApiError,
  replaceAirspaceFromAsf,
  replaceAircraftPerformancesFromAsf,
  replacePhysicalSectorsFromAsf,
  type AircraftPerformanceImportResult,
  type AsfImportResult,
  type PhysicalSectorImportResult
} from '../api/client';

const emit = defineEmits<{ close: []; imported: []; toast: [message: string, tone?: 'ok' | 'error'] }>();
const points = ref<File | null>(null);
const routes = ref<File | null>(null);
const fdpVolumes = ref<File | null>(null);
const aircraftPerformances = ref<File | null>(null);
const confirmed = ref(false);
const busy = ref(false);
const errorText = ref('');
const result = ref<AsfImportResult | null>(null);
const sectorResult = ref<PhysicalSectorImportResult | null>(null);
const performanceResult = ref<AircraftPerformanceImportResult | null>(null);
const ready = computed(() => confirmed.value
  && (Boolean(points.value && routes.value) || Boolean(fdpVolumes.value) || Boolean(aircraftPerformances.value))
  && Boolean(points.value) === Boolean(routes.value));
const selected = (event: Event) => (event.target as HTMLInputElement).files?.[0] ?? null;

async function submit() {
  if (!ready.value) return;
  busy.value = true;
  errorText.value = '';
  try {
    if (points.value && routes.value) {
      result.value = await replaceAirspaceFromAsf(points.value, routes.value);
    }
    if (fdpVolumes.value) {
      sectorResult.value = await replacePhysicalSectorsFromAsf(fdpVolumes.value);
    }
    if (aircraftPerformances.value) {
      performanceResult.value = await replaceAircraftPerformancesFromAsf(aircraftPerformances.value);
    }
    emit('imported');
    const messages: string[] = [];
    if (result.value) messages.push(`${result.value.navigationPointCount} 个点，${result.value.airwayCount} 条航路`);
    if (sectorResult.value) messages.push(`${sectorResult.value.regionCount} 个物理扇区区域`);
    if (performanceResult.value) messages.push(`${performanceResult.value.performanceRowCount} 条机型高度层性能`);
    emit('toast', `ASF 导入完成：${messages.join('；')}`);
  } catch (error) {
    errorText.value = error instanceof ApiError ? error.message : 'ASF 导入失败';
    emit('toast', errorText.value, 'error');
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.asf-mask{position:absolute;inset:0;z-index:85;background:rgba(23,33,43,.28)}
.asf-dialog{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);width:470px;max-width:92vw;background:var(--panel);border:1px solid var(--border);border-radius:8px;box-shadow:var(--shadow)}
header,footer{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid var(--border)} footer{justify-content:flex-end;gap:6px;border-top:1px solid var(--border);border-bottom:0}
button{min-height:28px;padding:3px 9px;color:var(--text);background:var(--panel);border:1px solid var(--border);border-radius:5px}.primary{color:#fff;background:var(--primary);border-color:var(--primary)}button:disabled{opacity:.5}
.asf-body{padding:12px}.hint{margin:0 0 10px;color:var(--muted);font-size:11px;line-height:1.5}label{display:block;margin:8px 0 3px;color:var(--muted);font-size:11px}input[type=file]{width:100%;padding:5px;background:var(--soft);border:1px solid var(--border);border-radius:5px;font-size:11px}.confirm{color:var(--text)}
.error{color:var(--danger);font-size:12px}.summary{color:var(--primary);font-size:12px}.conflicts{max-height:120px;overflow:auto;margin-top:5px;padding:5px;background:var(--soft);font-size:11px}details{font-size:11px}
</style>
