<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import Map from 'ol/Map'
import View from 'ol/View'
import Feature from 'ol/Feature'
import Point from 'ol/geom/Point'
import VectorSource from 'ol/source/Vector'
import VectorLayer from 'ol/layer/Vector'
import { fromLonLat } from 'ol/proj'
import { Fill, Icon, Stroke, Style, Text } from 'ol/style'
import CircleStyle from 'ol/style/Circle'
import type { Aircraft } from './types'
import { formatHeading, hasTrackPosition } from './situationGeometry'

const props = defineProps<{
  aircraft: Aircraft[]
  selectedId: string | null
  trackColor: string
  selectedTrackColor: string
}>()
const emit = defineEmits<{ select: [id: string] }>()
let map: Map | null = null
const source = new VectorSource()

function label(item: Aircraft) {
  const active = (item.activeInstruction ?? '').slice(0, 8)
  return `${item.callsign.padEnd(7)} ${(item.transponderCode ?? '----').padEnd(4)} ${item.aircraftType.padEnd(4)} ${item.wakeCategory}\n` +
    `${Math.round(item.altitudeFeet).toString().padStart(4)} ${Math.round(item.speedKnots).toString().padStart(4)} ${formatHeading(item.headingDegrees)} ${Math.round(item.verticalSpeedFeetPerMinute).toString().padStart(4)}\n` +
    `${item.origin.padEnd(4)} ${item.destination.padEnd(4)} ${active.padEnd(8)}`
}

function redraw() {
  source.clear()
  for (const item of props.aircraft) {
    if (!hasTrackPosition(item)) continue
    const selected = item.id === props.selectedId
    const color = selected ? props.selectedTrackColor : props.trackColor
    const feature = new Feature({ geometry: new Point(fromLonLat([item.longitude, item.latitude])), aircraftId: item.id })
    feature.setStyle(new Style({
      image: new CircleStyle({ radius: selected ? 5 : 4, fill: new Fill({ color }), stroke: new Stroke({ color: '#08131a', width: 1 }) }),
      text: new Text({
        text: label(item), offsetX: 14, offsetY: 25, textAlign: 'left',
        font: '12px Consolas, monospace', fill: new Fill({ color }),
        backgroundFill: new Fill({ color: 'rgba(4, 15, 22, .86)' }),
        padding: [3, 4, 3, 4]
      })
    }))
    source.addFeature(feature)
  }
}

onMounted(() => {
  map = new Map({
    target: 'situation-map',
    layers: [new VectorLayer({ source })],
    view: new View({ center: fromLonLat([116.5, 34]), zoom: 5, minZoom: 2, maxZoom: 14 })
  })
  map.on('singleclick', event => {
    map?.forEachFeatureAtPixel(event.pixel, feature => {
      emit('select', String(feature.get('aircraftId')))
      return true
    })
  })
  redraw()
})
watch(() => [props.aircraft, props.selectedId, props.trackColor, props.selectedTrackColor], redraw, { deep: true })
onBeforeUnmount(() => map?.setTarget(undefined))
</script>

<template><div id="situation-map" class="situation-map" /></template>
