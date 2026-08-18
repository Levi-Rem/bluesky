<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from './api'
import { buildAircraftPayload, validateAircraftForm, validateAircraftPosition } from './aircraftForm'
import { validateAircraftReferences, validateInitialWaypointReference } from './aircraftReferenceValidation'

defineProps<{ disabled?: boolean }>()

const emit = defineEmits<{ created: [] }>()
const open = ref(false)
const error = ref('')
const form = reactive({
  callsign: 'CCA3582', aircraftType: 'A320', wakeCategory: 'M', transponderCode: '1234',
  origin: 'ZSSS', destination: 'ZBAA', appearanceOffset: '0000', latitude: 31.1434,
  longitude: 121.8052, initialWaypoint: '', headingDegrees: 360, altitudeFeet: 9000, speedKnots: 250,
  route: 'ZSSS ZBAA'
})

/* 浮动面板：打开时居中显示在主窗口（态势图区域），任何情况下都完整落在视口内 */
const MARGIN = 12
const panelX = ref(0)
const panelY = ref(0)
const formEl = ref<HTMLElement | null>(null)
let dragging = false

function panelSize() {
  return { w: formEl.value?.offsetWidth ?? 680, h: formEl.value?.offsetHeight ?? 420 }
}

function clampToViewport(x: number, y: number) {
  const { w, h } = panelSize()
  const maxX = Math.max(0, window.innerWidth - w - MARGIN)
  const maxY = Math.max(0, window.innerHeight - h - MARGIN)
  return { x: Math.min(Math.max(MARGIN, x), Math.max(MARGIN, maxX)), y: Math.min(Math.max(MARGIN, y), Math.max(MARGIN, maxY)) }
}

function placeAtPreferred() {
  const { w, h } = panelSize()
  const pos = clampToViewport((window.innerWidth - w) / 2, (window.innerHeight - h) / 2)
  panelX.value = pos.x
  panelY.value = pos.y
}

function nextFrame() {
  return new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
}

async function toggle() {
  open.value = !open.value
  if (open.value) {
    error.value = ''
    await nextTick()
    await nextFrame()
    placeAtPreferred()
    observePanelSize()
  } else {
    unobservePanelSize()
  }
}

function onDragStart(event: PointerEvent) {
  if ((event.target as HTMLElement).closest('button')) return
  dragging = true
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onDragMove(event: PointerEvent) {
  if (!dragging) return
  const pos = clampToViewport(panelX.value + event.movementX, panelY.value + event.movementY)
  panelX.value = pos.x
  panelY.value = pos.y
}

function onDragEnd() {
  dragging = false
}

function onWindowResize() {
  if (!open.value) return
  const pos = clampToViewport(panelX.value, panelY.value)
  panelX.value = pos.x
  panelY.value = pos.y
}

/* 面板尺寸后置变化（错误提示出现、媒体查询切换、字体加载）时保持完整可见 */
let sizeObserver: ResizeObserver | null = null
function observePanelSize() {
  if (typeof ResizeObserver === 'undefined' || !formEl.value) return
  if (!sizeObserver) {
    sizeObserver = new ResizeObserver(() => {
      if (!open.value || dragging) return
      const pos = clampToViewport(panelX.value, panelY.value)
      panelX.value = pos.x
      panelY.value = pos.y
    })
  }
  sizeObserver.disconnect()
  sizeObserver.observe(formEl.value)
}

function unobservePanelSize() {
  sizeObserver?.disconnect()
}

onMounted(() => window.addEventListener('resize', onWindowResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize)
  unobservePanelSize()
})

async function submit() {
  error.value = ''
  error.value = validateAircraftForm(form.callsign, form.appearanceOffset, form.transponderCode)
  if (!error.value) {
    error.value = validateAircraftPosition(form.latitude, form.longitude, form.initialWaypoint)
  }
  if (error.value) return
  try {
    error.value = await validateAircraftReferences(
      form.aircraftType, form.origin, form.destination, form.route, api.reference
    )
    if (error.value) return
    error.value = await validateInitialWaypointReference(form.initialWaypoint, api.reference)
    if (error.value) return
    await api.createAircraft(buildAircraftPayload(form))
    open.value = false
    emit('created')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : String(reason)
  }
}
</script>

<template>
  <button class="icon-button" title="创建航空器" :disabled="disabled" @click="toggle">＋</button>
  <Teleport to="body">
    <!-- Teleport 到 body：脱离 .aircraft-list 的 backdrop-filter 包含块，fixed 定位才能真正以视口为参照 -->
    <form
      v-if="open"
      ref="formEl"
      class="aircraft-form panel"
      :style="{ left: panelX + 'px', top: panelY + 'px' }"
      @submit.prevent="submit"
    >
    <div
      class="form-header"
      @pointerdown="onDragStart"
      @pointermove="onDragMove"
      @pointerup="onDragEnd"
      @pointercancel="onDragEnd"
    >
      <span class="form-title">创建航空器</span>
      <button type="button" class="form-close" title="关闭" @click="open = false">✕</button>
    </div>
    <div class="form-grid">
      <label>呼号<input v-model="form.callsign" minlength="2" maxlength="7" pattern="[A-Za-z0-9]{2,7}" /></label>
      <label>机型<input v-model="form.aircraftType" maxlength="4" /></label>
      <label>尾流<input v-model="form.wakeCategory" maxlength="1" /></label>
      <label>二次代码<input v-model="form.transponderCode" maxlength="4" inputmode="numeric" pattern="[0-7]{4}" /></label>
      <label>起飞<input v-model="form.origin" maxlength="4" /></label>
      <label>落地<input v-model="form.destination" maxlength="4" /></label>
      <label>出现时间<input v-model="form.appearanceOffset" inputmode="numeric" minlength="4" maxlength="4" pattern="[0-9]{4}" /></label>
      <label>航向<input v-model.number="form.headingDegrees" type="number" /></label>
      <label>纬度<input v-model.number="form.latitude" type="number" step="0.0001" /></label>
      <label>经度<input v-model.number="form.longitude" type="number" step="0.0001" /></label>
      <label>初始航路点<input v-model="form.initialWaypoint" maxlength="16" placeholder="与经纬度二选一" /></label>
      <label>高度<input v-model.number="form.altitudeFeet" type="number" /></label>
      <label>速度<input v-model.number="form.speedKnots" type="number" /></label>
      <label class="wide">航路<input v-model="form.route" /></label>
    </div>
    <div v-if="error" class="form-error">{{ error }}</div>
    <div class="form-actions"><button type="button" @click="open = false">取消</button><button type="submit">创建</button></div>
    </form>
  </Teleport>
</template>
