<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { MapLayerCategory, MapLayerVisibility, RuntimeMapLayer } from './types'

const props = defineProps<{
  available: boolean
  layers: RuntimeMapLayer[]
  visibility: MapLayerVisibility
}>()
const emit = defineEmits<{ toggle: [category: MapLayerCategory, visible: boolean] }>()
const root = ref<HTMLElement | null>(null)
const open = ref(false)
const definitions: Array<{ category: MapLayerCategory; label: string }> = [
  { category: 'WAYPOINT', label: '航路点' },
  { category: 'AIRWAY', label: '航线' },
  { category: 'PHYSICAL_SECTOR', label: '扇区' },
  { category: 'WEATHER', label: '天气' }
]
const counts = computed(() => Object.fromEntries(
  definitions.map(item => [item.category, props.layers.find(layer => layer.category === item.category)?.count ?? 0])
) as Record<MapLayerCategory, number>)

function close() { open.value = false }
function onDocumentPointer(event: PointerEvent) {
  if (!root.value?.contains(event.target as Node)) close()
}
function onDocumentKey(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}
onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointer)
  document.addEventListener('keydown', onDocumentKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointer)
  document.removeEventListener('keydown', onDocumentKey)
})
</script>

<template>
  <div ref="root" class="map-layer-control">
    <button class="map-layer-trigger" :disabled="!available" aria-haspopup="true"
      :aria-expanded="open" title="地图图层" @click="open = !open">
      <span aria-hidden="true">⌖</span> 地图 <span aria-hidden="true">▾</span>
    </button>
    <div v-if="open" class="map-layer-menu panel" role="group" aria-label="地图图层">
      <label v-for="item in definitions" :key="item.category" class="map-layer-option"
        :class="{ disabled: counts[item.category] === 0 }">
        <input type="checkbox" :checked="visibility[item.category]" :disabled="counts[item.category] === 0"
          @change="emit('toggle', item.category, ($event.target as HTMLInputElement).checked)" />
        <span>{{ item.label }}</span>
        <span class="map-layer-count">{{ counts[item.category] }}</span>
      </label>
    </div>
  </div>
</template>
