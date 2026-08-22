<template>
  <section class="map-editor">
    <div ref="canvasRef" class="map-canvas"></div>

    <header class="map-command-bar">
      <div class="map-title">
        <strong>地图编辑</strong>
        <span>综合数据视图</span>
        <span class="mouse-coordinate" title="当前鼠标 DMS 坐标">{{ mouseCoordinate }}</span>
      </div>

      <nav class="map-tools" aria-label="地图编辑工具">
        <button
          v-for="tool in tools"
          :key="tool.id"
          :class="{ active: currentTool === tool.id }"
          :title="tool.hint"
          :disabled="tool.id === 'vertex' && !canEditVertices"
          @click="useTool(tool.id)"
        >
          <span class="tool-icon">{{ tool.icon }}</span>
          {{ tool.label }}
        </button>
      </nav>

      <div class="command-actions">
        <span v-if="pending.length" class="pending-badge">待提交 {{ pending.length }}</span>
        <button class="command-button" title="缩放到全部可见数据" @click="fitVisibleData">全图</button>
        <button class="command-button" :disabled="!dirty || saving" @click="discardChanges">撤销</button>
        <button class="save-button" :disabled="!dirty || Boolean(draftValidationMessage) || saving" @click="save">{{ saving ? '保存中…' : '保存修改' }}</button>
        <button class="map-close" :disabled="saving" aria-label="关闭" @click="requestClose">×</button>
      </div>
    </header>

    <aside class="floating-panel layer-panel">
      <div class="panel-heading">
        <div>
          <strong>地图数据</strong>
          <span>{{ visibleFeatureCount }} / {{ totalFeatureCount }} 个对象</span>
        </div>
        <div class="layer-actions">
          <button @click="setAllLayers(true)">全选</button>
          <button @click="setAllLayers(false)">清空</button>
        </div>
      </div>
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
      <p class="panel-tip">勾选数据类型控制地图显示；五类地图对象均可选择、修改几何和属性。</p>
    </aside>

    <aside class="floating-panel property-panel">
      <div class="panel-heading property-heading">
        <div>
          <strong>对象属性</strong>
          <span v-if="selection">{{ editable ? '可编辑对象' : '只读对象' }}</span>
          <span v-else>尚未选择</span>
        </div>
      </div>

      <template v-if="selection">
        <div class="selected-object">
          <div>
            <strong>{{ selection.name || selection.code || draftTitle }}</strong>
            <span>{{ selection.entityId.startsWith('draft-') ? '尚未保存' : (selection.name || '—') }}</span>
          </div>
          <span class="type-badge">{{ entityTypeLabel(selection.entityType) }}</span>
        </div>

        <div v-if="isDraftPoint" class="property-fields draft-fields">
          <div class="field">
            <label>名称*</label>
            <input v-model.trim="selection.name" placeholder="输入点名称" @input="markPropertyDirty" />
          </div>
          <div class="field">
            <label>数据类型*</label>
            <select v-model="selection.dataCategory" @change="markPropertyDirty">
              <option value="AIRSPACE">空域数据</option>
            </select>
          </div>
          <div class="field">
            <label>坐标</label>
            <input :value="selection.coordinateText" readonly />
          </div>
          <p class="form-tip">点位已从地图获取；填写名称后，右键结束本次绘制。</p>
        </div>

        <div v-else-if="isDraftArea" class="property-fields draft-fields">
          <div class="field">
            <label>名称*</label>
            <input v-model.trim="selection.name" placeholder="输入区域名称" @input="markPropertyDirty" />
          </div>
          <div class="field">
            <label>区域类型*</label>
            <select v-model="selection.airspaceType" @change="markPropertyDirty">
              <option v-for="type in airspaceTypes" :key="type" :value="type">{{ type }}</option>
            </select>
          </div>
          <div class="field-row">
            <div class="field">
              <label>下限*</label>
              <input v-model.trim="selection.lowerLimit" placeholder="S0000" @input="markPropertyDirty" />
            </div>
            <div class="field">
              <label>上限*</label>
              <input v-model.trim="selection.upperLimit" placeholder="S3000" @input="markPropertyDirty" />
            </div>
          </div>
          <p class="form-tip">高度使用 S 加四位数字；区域绘制完成后右键结束。</p>
        </div>

        <div v-else class="property-fields">
          <div class="field">
            <label>标识</label>
            <input v-model="selection.code" :disabled="!editable" @input="markPropertyDirty" />
          </div>
          <div class="field">
            <label>名称</label>
            <input v-model="selection.name" :disabled="!editable" @input="markPropertyDirty" />
          </div>
        </div>

        <dl class="object-meta">
          <div><dt>几何</dt><dd>{{ selection.geometryType }}</dd></div>
          <div><dt>位置/范围</dt><dd :title="selection.geometryDescription">{{ selection.geometryDescription }}</dd></div>
          <div><dt>修订</dt><dd>{{ selection.entityId.startsWith('draft-') ? '新建' : selection.revision }}</dd></div>
        </dl>

        <div v-if="selection.vertices.length" class="coordinate-editor">
          <div class="coordinate-heading">
            <strong>坐标列表</strong>
            <span>DMS 坐标</span>
          </div>
          <div class="coordinate-list">
            <div v-for="(vertex, index) in selection.vertices" :key="vertex.key" class="coordinate-row">
              <span class="coordinate-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <input
                type="text"
                :value="vertex.dms"
                :disabled="!editable"
                :aria-label="`第 ${index + 1} 个顶点 DMS 坐标`"
                maxlength="15"
                spellcheck="false"
                @input="applyVertexDms(index, $event)"
              />
            </div>
          </div>
        </div>

        <p v-if="draftValidationMessage" class="validation-tip">{{ draftValidationMessage }}</p>
        <p v-else-if="!editable" class="readonly-tip">该对象类型暂不支持地图编辑。</p>
      </template>
      <div v-else class="empty-selection">
        <span class="empty-icon">⌖</span>
        <strong>选择一个地图对象</strong>
        <p>使用“选择”工具点击对象，可查看属性、修改名称或删除可编辑对象。</p>
      </div>

      <div class="property-actions">
        <button class="danger-button" :disabled="!selection || !editable || saving" @click="removeSelection">删除对象</button>
        <span></span>
        <button class="secondary-button" :disabled="!dirty || saving" @click="discardChanges">撤销修改</button>
        <button class="primary-button" :disabled="!dirty || Boolean(draftValidationMessage) || saving" @click="save">{{ saving ? '保存中…' : '保存修改' }}</button>
      </div>
    </aside>

    <div class="map-message" role="status">
      <span class="message-dot" :class="{ dirty }"></span>{{ message }}
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import Collection from 'ol/Collection';
import Feature from 'ol/Feature';
import GeoJSON from 'ol/format/GeoJSON';
import Geometry from 'ol/geom/Geometry';
import LineString from 'ol/geom/LineString';
import MultiPolygon from 'ol/geom/MultiPolygon';
import Point from 'ol/geom/Point';
import Polygon from 'ol/geom/Polygon';
import MapLib from 'ol/Map';
import View from 'ol/View';
import { defaults as defaultControls } from 'ol/control/defaults';
import { createEmpty, extend, isEmpty } from 'ol/extent';
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style';
import Draw from 'ol/interaction/Draw';
import Modify from 'ol/interaction/Modify';
import Select from 'ol/interaction/Select';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { fromLonLat, toLonLat } from 'ol/proj';
import { ApiError, mapLayers, saveMapFeatures, type MapLayerData, type MapOperation } from '../api/client';
import { useHealthStore } from '../stores/health';
import { decimalCoordinateToDms, parseDmsCoordinate } from '../utils/coordinates';
import 'ol/ol.css';
import './map-editor.css';

const emit = defineEmits<{ close: [] }>();
const healthStore = useHealthStore();

const canvasRef = ref<HTMLElement>();
const layers = ref<MapLayerData[]>([]);
const visible = ref<Record<string, boolean>>({});
const currentTool = ref('select');
const message = ref('勾选左侧数据项控制地图显示');
const mouseCoordinate = ref('—');
interface VertexCoordinate {
  key: string;
  path: number[];
  longitude: number;
  latitude: number;
  dms: string;
}
interface MapSelection {
  featureId: string;
  entityId: string;
  entityType: string;
  code: string;
  name: string;
  revision: number;
  geometryType: string;
  geometryDescription: string;
  coordinateText: string;
  dataCategory: string;
  pointType: string;
  airspaceType: string;
  lowerLimit: string;
  upperLimit: string;
  vertices: VertexCoordinate[];
}
const selection = ref<MapSelection | null>(null);
const dirty = ref(false);
const propertyDirty = ref(false);
const saving = ref(false);

const tools = [
  { id: 'select', label: '选择', icon: '⌖', hint: '点击地图对象查看和编辑属性' },
  { id: 'point', label: '新增点', icon: '＋', hint: '左键放置空域数据点，右键结束绘制' },
  { id: 'polygon', label: '绘制区域', icon: '◇', hint: '左键依次绘制区域顶点，右键结束绘制' },
  { id: 'vertex', label: '编辑顶点', icon: '⌁', hint: '选择已有对象后，拖动点位、航路线或区域顶点' }
] as const;

const airspaceTypes = ['FIR', 'TMA', 'CTR', 'CTA', 'RESTRICTED', 'DANGER', 'PROHIBITED'];
const editableTypes = new Set(['nav-point', 'airspace', 'airway', 'wind-field', 'sig-weather', 'radar-site']);
const editable = computed(() => Boolean(selection.value && editableTypes.has(selection.value.entityType)));
const canEditVertices = computed(() => Boolean(editable.value && selection.value?.geometryType !== '—'));
const isDraftPoint = computed(() => Boolean(
  selection.value?.entityId.startsWith('draft-') && selection.value.entityType === 'nav-point'
));
const isDraftArea = computed(() => Boolean(
  selection.value?.entityId.startsWith('draft-') && selection.value.entityType === 'airspace'
));
const draftTitle = computed(() => isDraftPoint.value ? '新建点' : isDraftArea.value ? '新建区域' : '未命名对象');
const draftValidationMessage = computed(() => {
  if (!selection.value?.entityId.startsWith('draft-')) return '';
  if (!selection.value.name.trim()) return '请填写名称后再保存。';
  if (isDraftArea.value) {
    if (!/^S\d{4}$/.test(selection.value.lowerLimit) || !/^S\d{4}$/.test(selection.value.upperLimit)) {
      return '上下限必须使用 S 加四位数字，例如 S0000、S3000。';
    }
    if (Number(selection.value.lowerLimit.slice(1)) > Number(selection.value.upperLimit.slice(1))) {
      return '下限不能高于上限。';
    }
  }
  return '';
});
const totalFeatureCount = computed(() => layers.value.reduce((sum, layer) => sum + layer.count, 0));
const visibleFeatureCount = computed(() => layers.value.reduce(
  (sum, layer) => sum + (visible.value[layer.category] === false ? 0 : layer.count), 0
));

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
const pending = reactive<MapOperation[]>([]);
let resizeObserver: ResizeObserver | null = null;
let hasFittedInitialData = false;

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
  let data: { layers: MapLayerData[] };
  try {
    data = await mapLayers();
  } catch (ex) {
    message.value = ex instanceof Error ? `地图数据加载失败：${ex.message}` : '地图数据加载失败';
    return false;
  }
  const revisions = new Map<string, number>();
  for (const layer of data.layers) {
    for (const item of layer.features) revisions.set(item.entityId, item.revision);
  }
  for (const operation of pending) {
    if (operation.operationType !== 'CREATE' && revisions.has(operation.entityId)) {
      operation.revision = revisions.get(operation.entityId) ?? operation.revision;
    }
  }
  selectInteraction?.getFeatures().clear();
  layerMap.forEach(layer => map?.removeLayer(layer));
  layerMap.clear();
  featureIndex.clear();
  layers.value = data.layers;
  for (const layer of data.layers) {
    if (visible.value[layer.category] === undefined) visible.value[layer.category] = true;
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
      feature.set('featureId', item.featureId, true);
      feature.set('entityId', item.entityId, true);
      feature.set('entityType', item.entityType, true);
      feature.set('code', item.code, true);
      feature.set('name', item.name, true);
      feature.set('revision', item.revision, true);
      feature.setStyle(featureStyle(layer.category, item.code));
      source.addFeature(feature);
      if (item.featureId) {
        featureIndex.set(item.featureId, feature);
      }
    }
    const vectorLayer = new VectorLayer({ source });
    vectorLayer.setVisible(visible.value[layer.category] !== false);
    layerMap.set(layer.category, vectorLayer);
    map?.addLayer(vectorLayer);
  }
  map?.updateSize();
  if (!hasFittedInitialData) {
    hasFittedInitialData = true;
    window.requestAnimationFrame(() => fitVisibleData());
  }
  return true;
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

function setAllLayers(next: boolean) {
  for (const layer of layers.value) {
    visible.value[layer.category] = next;
    layerMap.get(layer.category)?.setVisible(next);
  }
  message.value = next ? '已显示全部地图数据' : '已隐藏全部地图数据';
}

function fitVisibleData() {
  if (!map) return;
  const extent = createEmpty();
  for (const [category, layer] of layerMap.entries()) {
    if (visible.value[category] === false) continue;
    const layerExtent = layer.getSource()?.getExtent();
    if (layerExtent && !isEmpty(layerExtent)) extend(extent, layerExtent);
  }
  if (isEmpty(extent)) {
    message.value = '当前没有可缩放的数据';
    return;
  }
  const compact = window.innerWidth < 800;
  map.getView().fit(extent, {
    padding: compact ? [88, 18, 150, 18] : [88, 330, 68, 250],
    maxZoom: 10,
    duration: 280
  });
  message.value = '已缩放到全部可见数据';
}

function dataName(category: string): string {
  return layers.value.find(item => item.category === category)?.name ?? category;
}

function entityTypeLabel(entityType: string): string {
  const labels: Record<string, string> = {
    'nav-point': '空域信息点',
    airspace: '空域区域',
    airway: '航路',
    'wind-field': '风场',
    'sig-weather': '重要天气区域',
    'radar-site': '雷达覆盖'
  };
  return labels[entityType] ?? entityType;
}

function vertexCoordinate(path: number[], coordinate: number[]): VertexCoordinate {
  const [longitude, latitude] = toLonLat(coordinate);
  return {
    key: path.join('-') || 'point',
    path,
    longitude,
    latitude,
    dms: decimalCoordinateToDms(latitude, longitude)
  };
}

function verticesFromGeometry(geometry: Geometry | undefined): VertexCoordinate[] {
  if (geometry instanceof Point) {
    return [vertexCoordinate([], geometry.getCoordinates())];
  }
  if (geometry instanceof LineString) {
    return geometry.getCoordinates().map((coordinate, index) => vertexCoordinate([index], coordinate));
  }
  if (geometry instanceof Polygon) {
    const ring = geometry.getCoordinates()[0] ?? [];
    return ring.slice(0, Math.max(0, ring.length - 1))
      .map((coordinate, index) => vertexCoordinate([0, index], coordinate));
  }
  if (geometry instanceof MultiPolygon) {
    const result: VertexCoordinate[] = [];
    geometry.getCoordinates().forEach((polygon, polygonIndex) => {
      const ring = polygon[0] ?? [];
      ring.slice(0, Math.max(0, ring.length - 1)).forEach((coordinate, index) => {
        result.push(vertexCoordinate([polygonIndex, 0, index], coordinate));
      });
    });
    return result;
  }
  return [];
}

function selectionFromFeature(feature: Feature): MapSelection {
  const geometry = feature.getGeometry();
  let geometryType = '—';
  let geometryDescription = '—';
  let coordinateText = '';
  if (geometry instanceof Point) {
    const [longitude, latitude] = toLonLat(geometry.getCoordinates());
    geometryType = '点';
    coordinateText = decimalCoordinateToDms(latitude, longitude);
    geometryDescription = coordinateText;
  } else if (geometry instanceof LineString) {
    geometryType = '线';
    geometryDescription = `${geometry.getCoordinates().length} 个节点`;
  } else if (geometry instanceof Polygon) {
    geometryType = '区域';
    geometryDescription = `${Math.max(0, (geometry.getCoordinates()[0]?.length ?? 1) - 1)} 个顶点`;
  } else if (geometry instanceof MultiPolygon) {
    geometryType = '多区域';
    geometryDescription = `${verticesFromGeometry(geometry).length} 个顶点`;
  }
  return {
    featureId: String(feature.get('featureId') ?? feature.get('entityId') ?? ''),
    entityId: String(feature.get('entityId') ?? ''),
    entityType: String(feature.get('entityType') ?? ''),
    code: String(feature.get('code') ?? ''),
    name: String(feature.get('name') ?? ''),
    revision: Number(feature.get('revision') ?? 0),
    geometryType,
    geometryDescription,
    coordinateText,
    dataCategory: String(feature.get('dataCategory') ?? 'AIRSPACE'),
    pointType: String(feature.get('pointType') ?? 'REPORT'),
    airspaceType: String(feature.get('airspaceType') ?? 'TMA'),
    lowerLimit: String(feature.get('lowerLimit') ?? 'S0000'),
    upperLimit: String(feature.get('upperLimit') ?? 'S3000'),
    vertices: verticesFromGeometry(geometry)
  };
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

function geometryJson(feature: Feature): string {
  const geometry = feature.getGeometry();
  return geometry
    ? JSON.stringify(
        new GeoJSON().writeGeometryObject(geometry, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })
      )
    : '';
}

function draftProperties(feature: Feature): Record<string, string> {
  const name = String(feature.get('name') ?? '').trim();
  const entityType = String(feature.get('entityType') ?? '');
  const properties: Record<string, string> = { code: name, name };
  if (entityType === 'nav-point') {
    properties.dataCategory = String(feature.get('dataCategory') ?? 'AIRSPACE');
    properties.pointType = String(feature.get('pointType') ?? 'REPORT');
  } else if (entityType === 'airspace') {
    properties.airspaceType = String(feature.get('airspaceType') ?? 'TMA');
    properties.lowerLimit = String(feature.get('lowerLimit') ?? 'S0000');
    properties.upperLimit = String(feature.get('upperLimit') ?? 'S3000');
  }
  return properties;
}

function createDraft(feature: Feature, tool: 'point' | 'polygon') {
  const category = tool === 'point' ? 'NAVIGATION' : 'AIRSPACE';
  const entityType = tool === 'point' ? 'nav-point' : 'airspace';
  const draftId = `draft-${Date.now()}-${Math.round(Math.random() * 10000)}`;
  feature.set('category', category, true);
  feature.set('featureId', draftId, true);
  feature.set('entityId', draftId, true);
  feature.set('entityType', entityType, true);
  feature.set('code', '', true);
  feature.set('name', '', true);
  feature.set('revision', 0, true);
  feature.set('dataCategory', 'AIRSPACE', true);
  feature.set('pointType', 'REPORT', true);
  feature.set('airspaceType', 'TMA', true);
  feature.set('lowerLimit', 'S0000', true);
  feature.set('upperLimit', 'S3000', true);
  feature.setStyle(featureStyle(category, tool === 'point' ? '新建点' : '新建区域'));
  featureIndex.set(draftId, feature);
  pending.push({
    operationType: 'CREATE',
    entityType,
    entityId: draftId,
    featureId: draftId,
    revision: 0,
    geometry: geometryJson(feature),
    properties: draftProperties(feature)
  });
  dirty.value = true;
  selection.value = selectionFromFeature(feature);
  propertyDirty.value = false;
  selectInteraction?.getFeatures().clear();
  selectInteraction?.getFeatures().push(feature);
}

function useTool(tool: string) {
  currentTool.value = tool;
  clearInteractions();
  selectInteraction?.setActive(tool === 'select');
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
      createDraft(feature, tool);
      if (tool === 'point') drawInteraction?.setActive(false);
      message.value = tool === 'point'
        ? '点位已获取：请填写右侧属性，右键结束绘制'
        : '区域已绘制：请填写右侧属性，右键结束绘制';
    });
    map?.addInteraction(drawInteraction);
    message.value = tool === 'point'
      ? '新增点：左键放置点位，右键结束'
      : '绘制区域：左键依次添加顶点，右键结束';
    return;
  }
  if (tool === 'vertex') {
    if (!selection.value || !editable.value) {
      useTool('select');
      message.value = '请先选择一个已有点、航路或区域对象';
      return;
    }
    if (selection.value.entityType === 'airway'
        && !window.confirm('编辑航路顶点会移动其引用的导航点，并可能影响其他航路。是否继续？')) {
      useTool('select');
      message.value = '已取消航路顶点编辑';
      return;
    }
    const feature = featureIndex.get(selection.value.featureId);
    if (!feature) {
      useTool('select');
      message.value = '未找到选中对象，请重新选择';
      return;
    }
    modifyInteraction = new Modify({ features: new Collection([feature]) });
    modifyInteraction.on('modifyend', event => {
      for (const feature of event.features.getArray()) {
        queueGeometry(feature as Feature);
        selection.value = selectionFromFeature(feature as Feature);
      }
      message.value = '几何已修改，点击保存修改提交';
    });
    map?.addInteraction(modifyInteraction);
    message.value = '编辑顶点：拖动点位、航路线或区域顶点，完成后保存';
    return;
  }
}

function queueGeometry(feature: Feature) {
  const entityType = String(feature.get('entityType') ?? '');
  const entityId = String(feature.get('entityId') ?? '');
  const featureId = String(feature.get('featureId') ?? entityId);
  const geometry = feature.getGeometry();
  if (!geometry || !entityId || !editableTypes.has(entityType)) {
    return;
  }
  const geometryJson = JSON.stringify(
    new GeoJSON().writeGeometryObject(geometry, {
      featureProjection: 'EPSG:3857',
      dataProjection: 'EPSG:4326'
    })
  );
  if (entityId.startsWith('draft-')) {
    const create = pending.find(op => op.entityId === entityId && op.operationType === 'CREATE');
    if (create) create.geometry = geometryJson;
    dirty.value = pending.length > 0;
    return;
  }
  const index = pending.findIndex(op =>
    op.entityId === entityId && op.featureId === featureId && op.operationType === 'UPDATE_GEOMETRY'
  );
  const operation: MapOperation = {
    operationType: 'UPDATE_GEOMETRY',
    entityType,
    entityId,
    featureId,
    revision: Number(feature.get('revision') ?? 0),
    geometry: geometryJson
  };
  if (index >= 0) {
    pending[index] = operation;
  } else {
    pending.push(operation);
  }
  dirty.value = true;
}

function applyVertexDms(index: number, event: Event) {
  const selected = selection.value;
  const vertex = selected?.vertices[index];
  const input = event.target as HTMLInputElement;
  if (!selected || !vertex) return;
  const value = input.value.toUpperCase().replace(/\s+/g, '');
  input.value = value;
  if (value.length < 15) {
    message.value = '请输入完整 DMS 坐标，例如 042018N1135931E';
    return;
  }
  const coordinate = parseDmsCoordinate(value);
  if (!coordinate) {
    message.value = 'DMS 坐标格式不正确，请使用 042018N1135931E 格式';
    return;
  }
  const feature = featureIndex.get(selected.featureId);
  const geometry = feature?.getGeometry();
  if (!feature || !geometry) return;
  const projected = fromLonLat([coordinate.longitude, coordinate.latitude]);
  if (geometry instanceof Point) {
    geometry.setCoordinates(projected);
  } else if (geometry instanceof LineString) {
    const coordinates = geometry.getCoordinates();
    coordinates[vertex.path[0]] = projected;
    geometry.setCoordinates(coordinates);
  } else if (geometry instanceof Polygon) {
    const coordinates = geometry.getCoordinates();
    const ring = coordinates[vertex.path[0]];
    const vertexIndex = vertex.path[1];
    ring[vertexIndex] = projected;
    if (vertexIndex === 0) ring[ring.length - 1] = [...projected];
    geometry.setCoordinates(coordinates);
  } else if (geometry instanceof MultiPolygon) {
    const coordinates = geometry.getCoordinates();
    const ring = coordinates[vertex.path[0]][vertex.path[1]];
    const vertexIndex = vertex.path[2];
    ring[vertexIndex] = projected;
    if (vertexIndex === 0) ring[ring.length - 1] = [...projected];
    geometry.setCoordinates(coordinates);
  } else {
    return;
  }
  queueGeometry(feature);
  selection.value = selectionFromFeature(feature);
  message.value = `第 ${index + 1} 个 DMS 坐标已调整，点击保存修改提交`;
}

function markPropertyDirty() {
  if (!editable.value || !selection.value) {
    return;
  }
  const { featureId, entityId, entityType, code, name, revision } = selection.value;
  if (entityId.startsWith('draft-')) {
    const draftFeature = featureIndex.get(featureId);
    if (draftFeature) {
      const draftCode = name.trim();
      selection.value.code = draftCode;
      draftFeature.set('code', draftCode, true);
      draftFeature.set('name', name, true);
      draftFeature.set('dataCategory', selection.value.dataCategory, true);
      draftFeature.set('pointType', selection.value.pointType, true);
      draftFeature.set('airspaceType', selection.value.airspaceType, true);
      draftFeature.set('lowerLimit', selection.value.lowerLimit.toUpperCase(), true);
      draftFeature.set('upperLimit', selection.value.upperLimit.toUpperCase(), true);
      selection.value.lowerLimit = selection.value.lowerLimit.toUpperCase();
      selection.value.upperLimit = selection.value.upperLimit.toUpperCase();
      draftFeature.setStyle(featureStyle(
        String(draftFeature.get('category') ?? 'NAVIGATION'),
        name.trim() || (entityType === 'nav-point' ? '新建点' : '新建区域')
      ));
      const create = pending.find(op => op.entityId === entityId && op.operationType === 'CREATE');
      if (create) create.properties = draftProperties(draftFeature);
    }
    propertyDirty.value = true;
    dirty.value = pending.length > 0;
    return;
  }
  const operation: MapOperation = {
    operationType: 'UPDATE_PROPERTIES',
    entityType,
    entityId,
    featureId,
    revision,
    properties: { code, name }
  };
  const index = pending.findIndex(
    item => item.entityId === entityId && item.operationType === 'UPDATE_PROPERTIES'
  );
  if (index >= 0) {
    pending[index] = operation;
  } else {
    pending.push(operation);
  }
  for (const feature of featureIndex.values()) {
    if (String(feature.get('entityId') ?? '') === entityId) {
      feature.set('code', code, true);
      feature.set('name', name, true);
      feature.setStyle(featureStyle(String(feature.get('category') ?? 'NAVIGATION'), code));
    }
  }
  propertyDirty.value = true;
  dirty.value = true;
}

function removeSelection() {
  if (!selection.value) {
    return;
  }
  const { featureId, entityId, entityType, revision } = selection.value;
  if (!window.confirm(entityType === 'wind-field' ? '确认删除该风场点？' : '确认删除该地图对象？')) return;
  for (let index = pending.length - 1; index >= 0; index -= 1) {
    if (pending[index].entityId === entityId) {
      pending.splice(index, 1);
    }
  }
  const feature = featureIndex.get(featureId);
  if (entityId.startsWith('draft-')) {
    if (feature) {
      const category = String(feature.get('category') ?? '');
      layerMap.get(category)?.getSource()?.removeFeature(feature);
      featureIndex.delete(featureId);
    }
    selectInteraction?.getFeatures().clear();
    selection.value = null;
    propertyDirty.value = false;
    dirty.value = pending.length > 0;
    message.value = '已移除未保存对象';
    return;
  }
  pending.push({ operationType: 'DELETE', entityType, entityId, featureId, revision });
  for (const candidate of featureIndex.values()) {
    const sameTarget = entityType === 'wind-field'
      ? String(candidate.get('featureId') ?? '') === featureId
      : String(candidate.get('entityId') ?? '') === entityId;
    if (sameTarget) candidate.setStyle(new Style({}));
  }
  selectInteraction?.getFeatures().clear();
  selection.value = null;
  propertyDirty.value = false;
  dirty.value = true;
  message.value = '删除待保存：点击保存修改提交';
}

async function save() {
  if (saving.value) return;
  if (draftValidationMessage.value) {
    message.value = draftValidationMessage.value;
    return;
  }
  if (currentTool.value === 'point' || currentTool.value === 'polygon') useTool('select');
  if (
    selection.value &&
    editable.value &&
    propertyDirty.value &&
    !selection.value.entityId.startsWith('draft-')
  ) {
    const { featureId, entityId, entityType, code, name, revision } = selection.value;
    const index = pending.findIndex(
      op => op.entityId === entityId && op.operationType === 'UPDATE_PROPERTIES'
    );
    const operation: MapOperation = {
      operationType: 'UPDATE_PROPERTIES',
      entityType,
      entityId,
      featureId,
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
  saving.value = true;
  try {
    const result = await saveMapFeatures(pending);
    pending.length = 0;
    dirty.value = false;
    propertyDirty.value = false;
    selection.value = null;
    healthStore.bumpRevision();
    const loaded = await loadLayers();
    if (loaded) message.value = `已保存 ${result.saved} 项修改`;
  } catch (ex) {
    if (ex instanceof ApiError && ex.status === 409) {
      await loadLayers();
      message.value = '数据已被其他操作修改；待提交内容已更新到最新修订，请检查后再次保存';
    } else {
      message.value = ex instanceof Error ? ex.message : '保存失败';
    }
  } finally {
    saving.value = false;
  }
}

async function discardChanges() {
  if (!dirty.value) return;
  clearInteractions();
  currentTool.value = 'select';
  selectInteraction?.setActive(true);
  selectInteraction?.getFeatures().clear();
  pending.splice(0, pending.length);
  dirty.value = false;
  propertyDirty.value = false;
  selection.value = null;
  await loadLayers();
  message.value = '已撤销所有未保存修改';
}

function requestClose() {
  if (saving.value) return;
  if (dirty.value && !window.confirm('仍有未保存修改，确定关闭并放弃吗？')) return;
  emit('close');
}

function handleMapContextMenu(event: MouseEvent) {
  if (currentTool.value !== 'point' && currentTool.value !== 'polygon') return;
  event.preventDefault();
  const tool = currentTool.value;
  if (tool === 'polygon') drawInteraction?.finishDrawing();
  const hasDraft = Boolean(selection.value?.entityId.startsWith('draft-'));
  useTool('select');
  message.value = hasDraft
    ? '绘制已结束：请在右侧完成属性并保存'
    : '绘制已结束，未新增对象';
}

function handlePointerMove(event: any) {
  const [longitude, latitude] = toLonLat(event.coordinate);
  mouseCoordinate.value = decimalCoordinateToDms(latitude, longitude);
}

onMounted(async () => {
  map = new MapLib({
    target: canvasRef.value,
    controls: defaultControls({ attribution: false, rotate: false, zoom: true }),
    view: new View({
      center: fromLonLat([121.5, 31.2]),
      zoom: 7
    })
  });
  map.on('pointermove', handlePointerMove);
  selectInteraction = new Select();
  selectInteraction.on('select', event => {
    const feature = event.selected[0];
    if (!feature) {
      selection.value = null;
      propertyDirty.value = false;
      dirty.value = pending.length > 0;
      return;
    }
    selection.value = selectionFromFeature(feature);
    propertyDirty.value = false;
    dirty.value = pending.length > 0;
  });
  map.addInteraction(selectInteraction);
  resizeObserver = new ResizeObserver(() => map?.updateSize());
  if (canvasRef.value) {
    resizeObserver.observe(canvasRef.value);
    canvasRef.value.addEventListener('contextmenu', handleMapContextMenu);
  }
  window.requestAnimationFrame(() => map?.updateSize());
  await loadLayers();
});

onBeforeUnmount(() => {
  canvasRef.value?.removeEventListener('contextmenu', handleMapContextMenu);
  resizeObserver?.disconnect();
  resizeObserver = null;
  clearInteractions();
  map?.un('pointermove', handlePointerMove);
  layerMap.forEach(layer => {
    layer.getSource()?.clear();
    layer.getSource()?.dispose();
    layer.dispose();
  });
  layerMap.clear();
  featureIndex.clear();
  map?.setTarget(undefined);
  map?.dispose();
  map = null;
});
</script>
