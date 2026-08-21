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
          <button @click="editAction('export')">导出 Excel</button>
          <button @click="editAction('map')">地图编辑</button>
        </div>
      </div>
    </nav>

    <main class="main-content">
      <DataTable
        ref="tableRef"
        :config="pages[currentPage]"
        @view="onView"
        @edit="onEdit"
      />
    </main>

    <MapEditor v-if="mapOpen" @close="mapOpen = false" />

    <div class="toast" :class="{ error: toastTone === 'error' }" v-show="toastText">
      {{ toastText }}
    </div>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, provide, ref } from 'vue';
import DataTable from './components/DataTable.vue';
import MapEditor from './components/MapEditor.vue';
import { pages, pageTitles } from './pages/config';
import { useHealthStore } from './stores/health';

const healthStore = useHealthStore();
const airspacePages: string[] = ['navigation', 'airport', 'airspace', 'airway'];

const currentPage = ref<keyof typeof pages>('navigation');
const openMenu = ref('');
const tableRef = ref<InstanceType<typeof DataTable>>();
const mapOpen = ref(false);

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
    toast('导出向导将在数据编辑任务中开放');
    return;
  }
  if (action === 'import') {
    toast('导入向导将在数据编辑任务中开放');
    return;
  }
  toast('新建窗口将在编辑抽屉任务中开放');
}

function onView(row: Record<string, unknown>) {
  toast(`查看 ${String(row.code)}`);
}

function onEdit(row: Record<string, unknown>) {
  toast(`编辑 ${String(row.code)}`);
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
