<template>
  <section class="map-editor">
    <div ref="canvasRef" class="map-canvas"></div>

    <div class="floating-panel map-title">
      <strong>地图编辑</strong>
      <span>综合数据视图</span>
    </div>

    <button class="map-close" aria-label="关闭" @click="emit('close')">×</button>

    <aside class="floating-panel layer-panel">
      <div class="panel-title">地图数据</div>
      <div class="layer-list">
        <label v-for="layer in layers" :key="layer.category" class="layer-row">
          <input
            type="checkbox"
            :checked="visible[layer.category] !== false"
            @change="toggleLayer(layer.category)"
          />
          <span class="layer-name">{{ layer.name }}</span>
          <span class="layer-count">{{ layer.count }}</span>
        </label>
      </div>
    </aside>

    <div class="map-tools">
      <button
        v-for="tool in tools"
        :key="tool.id"
        :class="{ active: currentTool === tool.id }"
        @click="useTool(tool.id)"
      >
        {{ tool.label }}
      </button>
    </div>

    <aside class="floating-panel property-panel">
      <div class="panel-title">对象属性</div>

      <template v-if="selection">
        <div class="field">
          <label>标识</label>
          <input v-model="selection.code" :disabled="!editable" />
        </div>
        <div class="field">
          <label>名称</label>
          <input v-model="selection.name" :disabled="!editable" />
        </div>
        <div class="field">
          <label>类型</label>
          <input :value="selection.entityType" disabled />
        </div>
        <div class="field">
          <label>修订</label>
          <input :value="selection.revision" disabled />
        </div>
        <div class="property-actions">
          <button class="danger-button" :disabled="!editable" @click="removeSelection">删除</button>
          <button class="primary-button" :disabled="!dirty" @click="save">保存修改</button>
        </div>
      </template>
      <p v-else class="hint">选择地图对象查看属性</p>
    </aside>

    <div class="map-message">{{ message }}</div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import Feature from 'ol/Feature';
import GeoJSON from 'ol/format/GeoJSON';
import MapLib from 'ol/Map';
import View from 'ol/View';
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style';
import Draw from 'ol/interaction/Draw';
import Modify from 'ol/interaction/Modify';
import Select from 'ol/interaction/Select';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { fromLonLat } from 'ol/proj';
import { mapLayers, saveMapFeatures, type MapLayerData, type MapOperation } from '../api/client';
import { useHealthStore } from '../stores/health';
import 'ol/ol.css';
import './map-editor.css';

const emit = defineEmits<{ close: [] }>();
const healthStore = useHealthStore();

const canvasRef = ref<HTMLElement>();
const layers = ref<MapLayerData[]>([]);
const visible = ref<Record<string, boolean>>({});
const currentTool = ref('select');
const message = ref('勾选左侧数据项控制地图显示');
const selection = ref<{ entityId: string; entityType: string; code: string; name: string; revision: number } | null>(null);
const dirty = ref(false);

const tools = [
  { id: 'select', label: '选择' },
  { id: 'point', label: '新增点' },
  { id: 'polygon', label: '绘制区域' },
  { id: 'vertex', label: '编辑顶点' },
  { id: 'delete', label: '删除' }
] as const;

const editable = computed(
  () => selection.value?.entityType === 'nav-point' || selection.value?.entityType === 'airspace'
);

const styles: Record<string, { stroke: string; fill: string }> = {
  NAVIGATION: { stroke: '#087079', fill: 'rgba(8,112,121,0.9)' },
  AIRSPACE: { stroke: '#bd6727', fill: 'rgba(211,122,43,0.22)' },
  AIRWAY: { stroke: '#087079', fill: 'transparent' },
  WEATHER: { stroke: '#397baa', fill: 'rgba(66,126,180,0.22)' },
  RADAR: { stroke: '#087079', fill: 'rgba(8,112,121,0.08)' }
};

let map: MapLib | null = null;
const layerMap = new Map<string, VectorLayer<VectorSource>>();
const featureIndex = new Map<string, Feature>();
let selectInteraction: Select | null = null;
let drawInteraction: Draw | null = null;
let modifyInteraction: Modify | null = null;
/** 待保存操作队列 */
const pending: MapOperation[] = [];

function featureStyle(category: string, code: string): Style {
  const colors = styles[category] ?? styles.NAVIGATION;
  return new Style({
    stroke: new Stroke({ color: colors.stroke, width: 2 }),
    fill: new Fill({ color: colors.fill }),
    image: new CircleStyle({
      radius: 7,
      fill: new Fill({ color: colors.fill }),
      stroke: new Stroke({ color: '#ffffff', width: 2 })
    }),
    text: new Text({
      text: code,
      offsetY: -14,
      font: '12px sans-serif',
      fill: new Fill({ color: '#17212b' }),
      stroke: new Stroke({ color: '#ffffff', width: 3 })
    })
  });
}

async function loadLayers() {
  const data = await mapLayers();
  layers.value = data.layers;
  for (const layer of data.layers) {
    visible.value[layer.category] = true;
    const source = new VectorSource();
    const format = new GeoJSON();
    for (const item of layer.features) {
      if (!item.geometry) {
        continue;
      }
      const feature = format.readFeature(item.geometry, {
        featureProjection: 'EPSG:3857',
        dataProjection: 'EPSG:4326'
      }) as Feature;
      feature.set('category', layer.category, true);
      feature.set('entityId', item.entityId, true);
      feature.set('entityType', item.entityType, true);
      feature.set('code', item.code, true);
      feature.set('name', item.name, true);
      feature.set('revision', 0, true);
      feature.setStyle(featureStyle(layer.category, item.code));
      source.addFeature(feature);
      if (item.entityId) {
        featureIndex.set(item.entityId, feature);
      }
    }
    const vectorLayer = new VectorLayer({ source });
    layerMap.set(layer.category, vectorLayer);
    map?.addLayer(vectorLayer);
  }
}

async function reloadRevisions() {
  const data = await mapLayers();
  layers.value = data.layers;
}

function toggleLayer(category: string) {
  const layer = layerMap.get(category);
  if (!layer) {
    return;
  }
  const next = !(visible.value[category] !== false);
  visible.value[category] = next;
  layer.setVisible(next);
  message.value = `${dataName(category)}已${next ? '显示' : '隐藏'}`;
}

function dataName(category: string): string {
  return layers.value.find(item => item.category === category)?.name ?? category;
}

function clearInteractions() {
  if (drawInteraction) {
    map?.removeInteraction(drawInteraction);
    drawInteraction = null;
  }
  if (modifyInteraction) {
    map?.removeInteraction(modifyInteraction);
    modifyInteraction = null;
  }
}

function useTool(tool: string) {
  currentTool.value = tool;
  clearInteractions();
  if (tool === 'select') {
    message.value = '选择模式：点击对象查看属性';
    return;
  }
  if (tool === 'point' || tool === 'polygon') {
    const type = tool === 'point' ? 'Point' : 'Polygon';
    drawInteraction = new Draw({
      source: layerMap.get(tool === 'point' ? 'NAVIGATION' : 'AIRSPACE')?.getSource() ?? new VectorSource(),
      type
    });
    drawInteraction.on('drawend', event => {
      const feature = event.feature as Feature;
      const category = tool === 'point' ? 'NAVIGATION' : 'AIRSPACE';
      const entityType = tool === 'point' ? 'nav-point' : 'airspace';
      const code = window.prompt(tool === 'point' ? '新导航点编码' : '新空域编码') ?? '';
      if (!code) {
        feature.setStyle(new Style({}));
        message.value = '已取消绘制';
        return;
      }
      const name = window.prompt('名称', code) ?? code;
      feature.set('category', category, true);
      feature.set('entityId', '', true);
      feature.set('entityType', entityType, true);
      feature.set('code', code, true);
      feature.set('name', name, true);
      feature.set('revision', 0, true);
      feature.setStyle(featureStyle(category, code));
      const geometry = feature.getGeometry();
      pending.push({
        operationType: 'UPDATE_GEOMETRY',
        entityType,
        entityId: '',
        revision: 0,
        geometry: geometry
          ? JSON.stringify(
              new GeoJSON().writeGeometryObject(geometry, {
                featureProjection: 'EPSG:3857',
                dataProjection: 'EPSG:4326'
              })
            )
          : ''
      });
      message.value = `新对象 ${code} 已绘制（请先在列表页新建该对象后再编辑几何）`;
    });
    map?.addInteraction(drawInteraction);
    message.value = `${tool === 'point' ? '新增点' : '绘制区域'}模式已启用`;
    return;
  }
  if (tool === 'vertex') {
    const navigation = layerMap.get('NAVIGATION');
    const airspace = layerMap.get('AIRSPACE');
    const source = new VectorSource();
    navigation?.getSource()?.forEachFeature(feature => source.addFeature(feature));
    airspace?.getSource()?.forEachFeature(feature => source.addFeature(feature));
    modifyInteraction = new Modify({ source });
    modifyInteraction.on('modifyend', event => {
      for (const feature of event.features.getArray()) {
        queueGeometry(feature as Feature);
      }
      message.value = '几何已修改，点击保存修改提交';
    });
    map?.addInteraction(modifyInteraction);
    message.value = '编辑顶点模式已启用';
    return;
  }
  if (tool === 'delete') {
    message.value = '删除模式：选中对象后在属性面板删除';
  }
}

function queueGeometry(feature: Feature) {
  const entityType = String(feature.get('entityType') ?? '');
  const entityId = String(feature.get('entityId') ?? '');
  const geometry = feature.getGeometry();
  if (!geometry || !entityId || (entityType !== 'nav-point' && entityType !== 'airspace')) {
    return;
  }
  const index = pending.findIndex(op => op.entityId === entityId && op.operationType === 'UPDATE_GEOMETRY');
  const operation: MapOperation = {
    operationType: 'UPDATE_GEOMETRY',
    entityType,
    entityId,
    revision: Number(feature.get('revision') ?? 0),
    geometry: JSON.stringify(
      new GeoJSON().writeGeometryObject(geometry, {
        featureProjection: 'EPSG:3857',
        dataProjection: 'EPSG:4326'
      })
    )
  };
  if (index >= 0) {
    pending[index] = operation;
  } else {
    pending.push(operation);
  }
  dirty.value = true;
}

function removeSelection() {
  if (!selection.value) {
    return;
  }
  const { entityId, entityType, revision } = selection.value;
  pending.push({ operationType: 'DELETE', entityType, entityId, revision });
  const feature = featureIndex.get(entityId);
  if (feature) {
    feature.setStyle(new Style({}));
  }
  selection.value = null;
  dirty.value = true;
  message.value = '删除待保存：点击保存修改提交';
}

async function save() {
  if (!selection.value) {
    return;
  }
  const { entityId, entityType, code, name, revision } = selection.value;
  if (editable.value) {
    const index = pending.findIndex(
      op => op.entityId === entityId && op.operationType === 'UPDATE_PROPERTIES'
    );
    const operation: MapOperation = {
      operationType: 'UPDATE_PROPERTIES',
      entityType,
      entityId,
      revision,
      properties: { code, name }
    };
    if (index >= 0) {
      pending[index] = operation;
    } else {
      pending.push(operation);
    }
  }
  if (pending.length === 0) {
    message.value = '没有待保存的修改';
    return;
  }
  try {
    const result = await saveMapFeatures(pending);
    pending.length = 0;
    dirty.value = false;
    message.value = `已保存 ${result.saved} 项修改`;
    healthStore.bumpRevision();
    await reloadRevisions();
  } catch (ex) {
    message.value = ex instanceof Error ? ex.message : '保存失败';
  }
}

onMounted(async () => {
  map = new MapLib({
    target: canvasRef.value,
    view: new View({
      center: fromLonLat([121.5, 31.2]),
      zoom: 7
    })
  });
  selectInteraction = new Select();
  selectInteraction.on('select', event => {
    const feature = event.selected[0];
    if (!feature) {
      selection.value = null;
      dirty.value = false;
      return;
    }
    selection.value = {
      entityId: String(feature.get('entityId') ?? ''),
      entityType: String(feature.get('entityType') ?? ''),
      code: String(feature.get('code') ?? ''),
      name: String(feature.get('name') ?? ''),
      revision: Number(feature.get('revision') ?? 0)
    };
    dirty.value = false;
  });
  map.addInteraction(selectInteraction);
  await loadLayers();
});

onBeforeUnmount(() => {
  map?.setTarget(undefined);
  map = null;
});
</script>
