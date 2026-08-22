<template>
  <div class="table-container">
    <table>
      <thead>
        <tr>
          <th v-for="column in config.columns" :key="column">{{ column }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(row, index) in rows"
          :key="String(row.id)"
          :class="{ selected: index === selectedIndex }"
          @click="selectedIndex = index"
        >
          <td
            v-for="(cell, cellIndex) in config.cells(row)"
            :key="cellIndex"
            :class="{
              'record-code': cellIndex === 0,
              'wrap-cell': config.wrapColumns?.includes(cellIndex),
              'truncate-cell': config.truncateColumns?.includes(cellIndex)
            }"
            :title="config.truncateColumns?.includes(cellIndex) ? cell : undefined"
          >
            {{ cell }}
          </td>
          <td>
            <button class="row-action" @click.stop="emit('edit', row)">编辑</button>
            <button class="row-action danger" @click.stop="emit('delete', row)">删除</button>
          </td>
        </tr>
        <tr v-if="rows.length === 0 && !loading">
          <td :colspan="config.columns.length">暂无数据</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div class="pager">
    <span>
      第 {{ rangeText }} 条，共 {{ total }} 条
      <template v-if="error"> · {{ error }}</template>
    </span>
    <div>
      <button :disabled="page === 0" @click="turn(page - 1)">‹</button>
      <button class="current">{{ page + 1 }}</button>
      <button :disabled="!hasNext" @click="turn(page + 1)">›</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { list, ApiError } from '../api/client';
import type { PageConfig } from '../pages/config';

const props = defineProps<{ config: PageConfig }>();
const emit = defineEmits<{
  edit: [row: Record<string, unknown>];
  delete: [row: Record<string, unknown>];
}>();

const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const page = ref(0);
const loading = ref(false);
const error = ref('');
const selectedIndex = ref(0);
const size = 20;
let requestSequence = 0;

const hasNext = computed(() => (page.value + 1) * size < total.value);
const rangeText = computed(() => {
  if (total.value === 0) {
    return '0–0';
  }
  const from = page.value * size + 1;
  const to = Math.min(from + rows.value.length - 1, total.value);
  return `${from}–${to}`;
});

async function load() {
  const requestId = ++requestSequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await list<Record<string, unknown>>(props.config.entity, page.value, size);
    if (requestId !== requestSequence) return;
    rows.value = result.items;
    total.value = result.total;
    selectedIndex.value = 0;
  } catch (ex) {
    if (requestId !== requestSequence) return;
    rows.value = [];
    total.value = 0;
    error.value = ex instanceof ApiError ? ex.message : '加载失败';
  } finally {
    if (requestId === requestSequence) loading.value = false;
  }
}

function turn(target: number) {
  page.value = target;
}

function refresh() {
  void load();
}

watch(() => props.config.entity, () => {
  const alreadyFirstPage = page.value === 0;
  page.value = 0;
  if (alreadyFirstPage) void load();
});

watch(page, () => void load());

onMounted(() => void load());

defineExpose({ refresh });
</script>
