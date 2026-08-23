<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { DisplaySettings } from './types'

const props = defineProps<{
  open: boolean
  saved: DisplaySettings
  defaults: DisplaySettings
  busy: boolean
  error: string
}>()
const emit = defineEmits<{
  preview: [settings: DisplaySettings]
  save: [settings: DisplaySettings]
  close: []
}>()
const dialog = ref<HTMLElement | null>(null)

const fields: Array<{ key: keyof DisplaySettings; label: string; group: 'track' | 'map'; note?: string }> = [
  { key: 'trackColor', label: '普通航迹', group: 'track' },
  { key: 'selectedTrackColor', label: '选中航迹', group: 'track' },
  { key: 'mapWaypointColor', label: '航路点', group: 'map' },
  { key: 'mapAirwayColor', label: '航线', group: 'map' },
  { key: 'mapSectorColor', label: '扇区边界/文字', group: 'map' },
  { key: 'mapSectorFillColor', label: '扇区填充', group: 'map', note: '20%' },
  { key: 'mapWeatherColor', label: '天气边界/文字', group: 'map' },
  { key: 'mapWeatherFillColor', label: '天气填充', group: 'map', note: '20%' }
]
const draft = reactive<DisplaySettings>({ ...props.saved })
const valid = computed(() => fields.every(field => /^#[0-9a-fA-F]{6}$/.test(draft[field.key])))

watch(() => props.open, async open => {
  if (open) {
    Object.assign(draft, props.saved)
    await nextTick()
    focusableElements()[0]?.focus()
  }
})

function update(key: keyof DisplaySettings, value: string) {
  draft[key] = value.toUpperCase()
  if (/^#[0-9a-fA-F]{6}$/.test(draft[key])) emit('preview', { ...draft })
}
function resetDefaults() {
  Object.assign(draft, props.defaults)
  emit('preview', { ...draft })
}
function cancel() {
  if (props.busy) return
  emit('preview', { ...props.saved })
  emit('close')
}
function save() {
  if (valid.value && !props.busy) emit('save', { ...draft })
}
function onKey(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape' && !props.busy) cancel()
  if (event.key === 'Tab') trapFocus(event)
}
function focusableElements() {
  if (!dialog.value) return []
  return Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
  ))
}
function trapFocus(event: KeyboardEvent) {
  const elements = focusableElements()
  if (!elements.length) return
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="open" class="display-settings-mask" @pointerdown.self="cancel">
    <section ref="dialog" class="display-settings panel" role="dialog" aria-modal="true"
      aria-labelledby="display-settings-title" tabindex="-1">
      <header class="display-settings-header">
        <strong id="display-settings-title">显示设置</strong>
        <button class="form-close" :disabled="busy" aria-label="关闭显示设置" @click="cancel">×</button>
      </header>
      <div class="display-settings-title">航迹</div>
      <div class="display-settings-grid">
        <label v-for="field in fields.filter(item => item.group === 'track')" :key="field.key" class="color-setting">
          <span>{{ field.label }}</span>
          <input type="color" :value="draft[field.key]" @input="update(field.key, ($event.target as HTMLInputElement).value)" />
          <input class="color-text" :value="draft[field.key]" maxlength="7" spellcheck="false"
            @input="update(field.key, ($event.target as HTMLInputElement).value)" />
        </label>
      </div>
      <div class="display-settings-title">地图图层</div>
      <div class="display-settings-grid">
        <label v-for="field in fields.filter(item => item.group === 'map')" :key="field.key" class="color-setting">
          <span>{{ field.label }} <small v-if="field.note">{{ field.note }}</small></span>
          <input type="color" :value="/^#[0-9a-fA-F]{6}$/.test(draft[field.key]) ? draft[field.key] : '#000000'"
            @input="update(field.key, ($event.target as HTMLInputElement).value)" />
          <input class="color-text" :class="{ invalid: !/^#[0-9a-fA-F]{6}$/.test(draft[field.key]) }"
            :value="draft[field.key]" maxlength="7" spellcheck="false"
            @input="update(field.key, ($event.target as HTMLInputElement).value)" />
        </label>
      </div>
      <div v-if="error" class="display-settings-error" role="alert">{{ error }}</div>
      <footer class="display-settings-actions">
        <button :disabled="busy" @click="resetDefaults">恢复默认</button>
        <span />
        <button :disabled="busy" @click="cancel">取消</button>
        <button class="save-display-settings" :disabled="busy || !valid" @click="save">保存</button>
      </footer>
    </section>
  </div>
</template>
