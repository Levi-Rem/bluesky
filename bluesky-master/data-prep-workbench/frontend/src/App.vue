<template>
  <section class="app">
    <header class="app-header">
      <div class="brand">
        <strong>飞行数据准备与分析</strong>
        <span>基础数据工作台 · 本机数据库</span>
      </div>

      <div class="status">
        <span class="status-dot" :class="{ down: !healthStore.online }"></span>
        {{ healthStore.online ? '服务正常' : '服务不可用' }} · 修订
        <b>{{ healthStore.revision }}</b>
      </div>
    </header>

    <nav class="top-nav">
      <div class="nav-group">
        <button
          class="nav-button"
          :class="{ active: airspacePages.includes(currentPage) }"
          @click.stop="toggleMenu('airspaceMenu')"
        >
          空域数据 ▾
        </button>
        <div class="menu" v-show="openMenu === 'airspaceMenu'">
          <button v-for="key in airspacePages" :key="key" @click="switchPage(key)">
            {{ pageTitles[key] }}
          </button>
        </div>
      </div>

      <button
        class="nav-button"
        :class="{ active: currentPage === 'weather' }"
        @click="switchPage('weather')"
      >
        气象数据
      </button>
      <button
        class="nav-button"
        :class="{ active: currentPage === 'performance' }"
        @click="switchPage('performance')"
      >
        机型性能
      </button>
      <button
        class="nav-button"
        :class="{ active: currentPage === 'radar' }"
        @click="switchPage('radar')"
      >
        雷达与通道
      </button>

      <div class="nav-group data-edit-group">
        <button class="nav-button" @click.stop="toggleMenu('editMenu')">数据编辑 ▾</button>
        <div class="menu" v-show="openMenu === 'editMenu'">
          <button @click="editAction('new')">新建</button>
          <button @click="editAction('import')">导入 Excel</button>
          <button @click="editAction('asf')">导入运行系统 ASF</button>
          <button @click="editAction('export')">导出 Excel</button>
          <button @click="editAction('map')">地图编辑</button>
        </div>
      </div>
    </nav>

    <main class="main-content">
      <DataTable
        ref="tableRef"
        :config="pages[currentPage]"
        @edit="onEdit"
        @delete="onDelete"
      />
    </main>

    <MapEditor v-if="mapOpen" @close="mapOpen = false" />

    <EditDrawer
      v-if="drawer"
      :config="drawer.config"
      :mode="drawer.mode"
      :record-id="drawer.recordId"
      :revision="drawer.revision"
      :warning="drawer.warning"
      @close="drawer = null"
      @saved="onSaved"
      @toast="toast"
    />

    <ImportWizard
      v-if="wizardOpen"
      :default-entity="createEntityOf(currentPage)"
      @close="wizardOpen = false"
      @imported="onSaved"
      @toast="toast"
    />

    <AsfImportWizard
      v-if="asfOpen"
      @close="asfOpen = false"
      @imported="onSaved"
      @toast="toast"
    />

    <div class="toast" :class="{ error: toastTone === 'error' }" v-show="toastText">
      {{ toastText }}
    </div>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, provide, ref } from 'vue';
import DataTable from './components/DataTable.vue';
import MapEditor from './components/MapEditor.vue';
import EditDrawer from './components/EditDrawer.vue';
import ImportWizard from './components/ImportWizard.vue';
import AsfImportWizard from './components/AsfImportWizard.vue';
import {
  createEntityOf,
  drawerConfigs,
  entityOfRow,
  pages,
  pageTitles,
  type DrawerConfig
} from './pages/config';
import { ApiError, exportUrl, remove } from './api/client';
import { useHealthStore } from './stores/health';

const healthStore = useHealthStore();
const airspacePages: string[] = ['navigation', 'airway', 'physicalSector'];

const currentPage = ref<keyof typeof pages>('navigation');
const openMenu = ref('');
const tableRef = ref<InstanceType<typeof DataTable>>();
const mapOpen = ref(false);
const wizardOpen = ref(false);
const asfOpen = ref(false);

interface DrawerState {
  config: DrawerConfig;
  mode: 'view' | 'edit' | 'create';
  recordId?: string;
  revision?: number;
  warning?: string;
}

const drawer = ref<DrawerState | null>(null);

const toastText = ref('');
const toastTone = ref<'ok' | 'error'>('ok');
let toastTimer = 0;

function toast(message: string, tone: 'ok' | 'error' = 'ok') {
  toastText.value = message;
  toastTone.value = tone;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toastText.value = '';
  }, 1800);
}

provide('toast', toast);

function toggleMenu(menu: string) {
  openMenu.value = openMenu.value === menu ? '' : menu;
}

function switchPage(key: string) {
  currentPage.value = key as keyof typeof pages;
  openMenu.value = '';
}

function editAction(action: string) {
  openMenu.value = '';
  if (action === 'map') {
    mapOpen.value = true;
    return;
  }
  if (action === 'export') {
    const entity = createEntityOf(currentPage.value);
    if (!entity) {
      toast('当前页面不支持导出', 'error');
      return;
    }
    window.open(exportUrl(entity), '_blank');
    toast('已开始导出 Excel');
    return;
  }
  if (action === 'import') {
    wizardOpen.value = true;
    return;
  }
  if (action === 'asf') {
    asfOpen.value = true;
    return;
  }
  const entity = createEntityOf(currentPage.value);
  if (!entity || !drawerConfigs[entity]) {
    toast('当前页面不支持新建', 'error');
    return;
  }
  drawer.value = { config: drawerConfigs[entity], mode: 'create' };
}

function openRowDrawer(row: Record<string, unknown>, mode: 'view' | 'edit') {
  const entity = entityOfRow(currentPage.value, row);
  if (!entity || !drawerConfigs[entity]) {
    toast('该类数据为二期只读数据', 'error');
    return;
  }
  drawer.value = {
    config: drawerConfigs[entity],
    mode,
    recordId: String(row.id ?? ''),
    revision: Number(row.revision ?? 0),
    warning:
      row.sourceType === 'BLUESKY'
        ? `${row.sourceType} 来源数据为只读，保存将被服务端拒绝`
        : undefined
  };
}

function onEdit(row: Record<string, unknown>) {
  openRowDrawer(row, 'edit');
}

async function onDelete(row: Record<string, unknown>) {
  const entity = entityOfRow(currentPage.value, row);
  if (!entity) {
    toast('该类数据不支持删除', 'error');
    return;
  }
  const label = String(row.name ?? row.code ?? '当前记录');
  if (!window.confirm(`确认删除“${label}”吗？`)) {
    return;
  }
  try {
    await remove(entity, String(row.id ?? ''), Number(row.revision ?? 0));
    toast(`已删除“${label}”`);
    onSaved();
  } catch (error) {
    toast(error instanceof ApiError ? error.message : '删除失败', 'error');
  }
}

function onSaved() {
  tableRef.value?.refresh();
  healthStore.bumpRevision();
}

let pollTimer = 0;

onMounted(() => {
  void healthStore.refresh();
  pollTimer = window.setInterval(() => healthStore.refresh(), 5000);
});

onBeforeUnmount(() => {
  window.clearInterval(pollTimer);
  window.clearTimeout(toastTimer);
});
</script>
