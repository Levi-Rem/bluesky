<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import OlMap from 'ol/Map'
import View from 'ol/View'
import Feature from 'ol/Feature'
import Point from 'ol/geom/Point'
import LineString from 'ol/geom/LineString'
import VectorSource from 'ol/source/Vector'
import VectorLayer from 'ol/layer/Vector'
import { fromLonLat } from 'ol/proj'
import { defaults as defaultInteractions } from 'ol/interaction'
import DragPan from 'ol/interaction/DragPan'
import { Fill, Icon, Stroke, Style, Text } from 'ol/style'
import type { Aircraft } from './types'
import { formatHeading, hasTrackPosition } from './situationGeometry'
import { clampDistance, labelCenterOffset, nearestEdgeMidpoint, symbolRotation, defaultLayout } from './labelGeometry'

const props = defineProps<{
  aircraft: Aircraft[]
  selectedId: string | null
  trackColor: string
  selectedTrackColor: string
}>()
const emit = defineEmits<{ select: [id: string] }>()
let map: OlMap | null = null
const source = new VectorSource()

/* 标牌布局：angle 为屏幕角度（deg），dist 为标牌中心到符号中心的像素距离 */
type LabelLayout = { angle: number; dist: number }
const layouts = new Map<string, LabelLayout>()

/* 标牌尺寸近似（Consolas 12px 三行 + padding），用于标杆线端点与距离限制 */
const LABEL_W = 150
const LABEL_H = 54

/* 符号：iconfont 样式C 实心三角（天然朝上），生成 SVG data-URI */
const TRIANGLE_PATH =
  'M512 106.666667l405.333333 810.666666-405.333333-101.333333L106.666667 917.333333z'
function triangleDataUri(color: string, size: number): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 1024 1024">` +
    `<path d="${TRIANGLE_PATH}" fill="${color}"/></svg>`
  return 'data:image/svg+xml,' + encodeURIComponent(svg)
}

/* 标牌内容：三行，与首版一致（本次不改动） */
function label(item: Aircraft) {
  const active = (item.activeInstruction ?? '').slice(0, 8)
  return `${item.callsign.padEnd(7)} ${(item.transponderCode ?? '----').padEnd(4)} ${item.aircraftType.padEnd(4)} ${item.wakeCategory}\n` +
    `${Math.round(item.altitudeFeet).toString().padStart(4)} ${Math.round(item.speedKnots).toString().padStart(4)} ${formatHeading(item.headingDegrees)} ${Math.round(item.verticalSpeedFeetPerMinute).toString().padStart(4)}\n` +
    `${item.origin.padEnd(4)} ${item.destination.padEnd(4)} ${active.padEnd(8)}`
}

function layoutOf(id: string): LabelLayout {
  let l = layouts.get(id)
  if (!l) {
    l = defaultLayout()
    layouts.set(id, l)
  }
  return l
}

function redraw() {
  source.clear()
  const live = new Set(props.aircraft.map(a => a.id))
  for (const k of Array.from(layouts.keys())) {
    if (!live.has(k)) layouts.delete(k)
  }
  for (const item of props.aircraft) {
    if (!hasTrackPosition(item)) continue
    const coord = fromLonLat([item.longitude, item.latitude])
    const selected = item.id === props.selectedId
    const color = selected ? props.selectedTrackColor : props.trackColor
    const { angle, dist } = layoutOf(item.id)
    const center = labelCenterOffset(angle, dist)
    const anchor = nearestEdgeMidpoint(center, { w: LABEL_W, h: LABEL_H })

    /* 标杆线（zIndex 最低）：几何先落符号处，syncAnchors 计算真实端点 */
    const line = new Feature({ geometry: new LineString([coord, coord]) })
    line.setId(`line:${item.id}`)
    line.setStyle(new Style({ stroke: new Stroke({ color, width: 1.5 }), zIndex: 1 }))
    source.addFeature(line)

    /* 航迹符号：三角，指向=航向 */
    const sym = new Feature({ geometry: new Point(coord), aircraftId: item.id })
    sym.setId(`sym:${item.id}`)
    sym.setStyle(new Style({
      image: new Icon({
        src: triangleDataUri(color, selected ? 26 : 22),
        rotation: symbolRotation(item.headingDegrees)
      }),
      zIndex: 3
    }))
    source.addFeature(sym)

    /* 标牌：文本居中于 center 偏移处；无边框（无 backgroundStroke） */
    const lab = new Feature({ geometry: new Point(coord), aircraftId: item.id })
    lab.setId(`label:${item.id}`)
    lab.setStyle(new Style({
      text: new Text({
        text: label(item),
        offsetX: center.dx,
        offsetY: center.dy,
        textAlign: 'center',
        textBaseline: 'middle',
        font: '12px Consolas, monospace',
        fill: new Fill({ color }),
        backgroundFill: new Fill({ color: 'rgba(4, 15, 22, .86)' }),
        padding: [3, 4, 3, 4]
      }),
      zIndex: 2
    }))
    source.addFeature(lab)
  }
  syncAnchors()
}

/* 缩放/平移后重算标杆线端点（标牌偏移是像素单位，天然跟随符号） */
function syncAnchors() {
  if (!map) return
    for (const f of source.getFeatures()) {
    const fid = String(f.getId() ?? '')
    if (!fid.startsWith('line:')) continue
    const id = fid.slice(5)
    const item = props.aircraft.find(a => a.id === id)
    if (!item || !hasTrackPosition(item)) continue
    const coord = fromLonLat([item.longitude, item.latitude])
    const symPixel = map.getPixelFromCoordinate(coord)
    if (!symPixel) continue
    const { angle, dist } = layoutOf(id)
    const anchor = nearestEdgeMidpoint(labelCenterOffset(angle, dist), { w: LABEL_W, h: LABEL_H })
    const anchorCoord = map.getCoordinateFromPixel([symPixel[0] + anchor.x, symPixel[1] + anchor.y])
    const geom = f.getGeometry()
    if (geom instanceof LineString) geom.setCoordinates([coord, anchorCoord])
  }
}

/* ---- 标牌拖动：绕符号旋转 + 距离钳制；拖动标牌时不能拖动地图 ---- */
let dragTarget: string | null = null

function symPixelOf(id: string): number[] | null {
  if (!map) return null
  const item = props.aircraft.find(a => a.id === id)
  if (!item || !hasTrackPosition(item)) return null
  return map.getPixelFromCoordinate(fromLonLat([item.longitude, item.latitude]))
}

/* 命中检测：返回被点中的标牌所属航空器 id（或 null） */
function labelHit(px: number, py: number): string | null {
  for (const item of props.aircraft) {
    const sym = symPixelOf(item.id)
    if (!sym) continue
    const c = labelCenterOffset(layoutOf(item.id).angle, layoutOf(item.id).dist)
    const cx = sym[0] + c.dx
    const cy = sym[1] + c.dy
    if (Math.abs(px - cx) <= LABEL_W / 2 && Math.abs(py - cy) <= LABEL_H / 2) {
      return item.id
    }
  }
  return null
}

function onPointerDown(e: PointerEvent) {
  if (!map) return
  const rect = map.getTargetElement().getBoundingClientRect()
  const id = labelHit(e.clientX - rect.left, e.clientY - rect.top)
  if (id) {
    dragTarget = id
    map.getTargetElement().style.cursor = 'grabbing'
  }
}

function onPointerMove(e: PointerEvent) {
  if (!dragTarget || !map) return
  const sym = symPixelOf(dragTarget)
  if (!sym) return
  const rect = map.getTargetElement().getBoundingClientRect()
  const dx = e.clientX - rect.left - sym[0]
  const dy = e.clientY - rect.top - sym[1]
  const l = layoutOf(dragTarget)
  l.angle = (Math.atan2(dy, dx) * 180) / Math.PI
  l.dist = clampDistance(Math.hypot(dx, dy), LABEL_H)
  redraw()
}

function onPointerUp() {
  if (dragTarget === null) return
  dragTarget = null
  if (map) map.getTargetElement().style.cursor = ''
}

onMounted(() => {
  /* 自定义 DragPan：pointerdown 命中标牌时当场拒绝地图拖拽（拖标牌不拖地图） */
  const dragPan = new DragPan({
    condition: evt => labelHit(evt.pixel[0], evt.pixel[1]) === null
  })
  map = new OlMap({
    target: 'situation-map',
    interactions: defaultInteractions({ dragPan: false }).extend([dragPan]),
    layers: [new VectorLayer({ source })],
    view: new View({ center: fromLonLat([116.5, 34]), zoom: 5, minZoom: 2, maxZoom: 14 })
  })
  map.on('singleclick', event => {
    map?.forEachFeatureAtPixel(event.pixel, feature => {
      emit('select', String(feature.get('aircraftId')))
      return true
    })
  })
  map.getView().on('change', syncAnchors)
  /* 测试钩子：e2e 用（读取视图中心/像素换算，验证拖标牌不动地图） */
  ;(window as unknown as { __situationMap?: OlMap }).__situationMap = map
  map.getViewport().addEventListener('pointerdown', onPointerDown)
  document.addEventListener('pointermove', onPointerMove)
  document.addEventListener('pointerup', onPointerUp)
  redraw()
})
watch(() => [props.aircraft, props.selectedId, props.trackColor, props.selectedTrackColor], redraw, { deep: true })
onBeforeUnmount(() => {
  document.removeEventListener('pointermove', onPointerMove)
  document.removeEventListener('pointerup', onPointerUp)
  map?.setTarget(undefined)
})
</script>

<template><div id="situation-map" class="situation-map" /></template>
