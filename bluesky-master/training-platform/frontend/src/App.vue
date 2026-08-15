<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SituationMap from './SituationMap.vue'
import CreateAircraftDialog from './CreateAircraftDialog.vue'
import { insertionForEnter } from './commandKeys'
import { arrangeInstructionQueue } from './instructionQueue'
import { api } from './api'
import { useWorkstationStore } from './store'

const store = useWorkstationStore()
const topHidden = ref(false)
const leftHidden = ref(false)
const command = ref('')
const commandError = ref('')
const busy = ref(false)
const deletingId = ref<string | null>(null)

const group = computed(() => store.bootstrap?.exerciseGroup)
const engine = computed(() => store.bootstrap?.engine)
const colors = computed(() => store.bootstrap?.uiParameters ?? {
  trackColor: '#58d7ff', selectedTrackColor: '#ffe66d'
})
const arrangedInstructions = computed(() => arrangeInstructionQueue(store.instructions))

function formatTime(seconds = 0) {
  const whole = Math.floor(seconds)
  const hh = Math.floor(whole / 3600).toString().padStart(2, '0')
  const mm = Math.floor((whole % 3600) / 60).toString().padStart(2, '0')
  const ss = (whole % 60).toString().padStart(2, '0')
  return `${hh}:${mm}:${ss}`
}

async function toggleRun() {
  if (!group.value || busy.value) return
  busy.value = true
  try {
    if (group.value.state === 'READY') store.bootstrap!.exerciseGroup = await api.start()
    else if (group.value.state === 'RUNNING') store.bootstrap!.exerciseGroup = await api.pause()
    else store.bootstrap!.exerciseGroup = await api.resume()
  } finally { busy.value = false }
}

async function submitCommand(event: KeyboardEvent) {
  if (event.key !== 'Enter' || !store.selectedAircraft || !command.value.trim()) return
  event.preventDefault()
  const text = command.value
  command.value = ''
  commandError.value = ''
  try {
    const created = await api.instruction(store.selectedAircraft.id, text, insertionForEnter(event))
    store.upsertInstruction(created)
  } catch (reason) {
    command.value = text
    commandError.value = reason instanceof Error ? reason.message : String(reason)
  }
}

async function deleteAircraft(id: string) {
  if (deletingId.value) return
  deletingId.value = id
  try {
    await store.deleteAircraft(id)
  } catch (reason) {
    commandError.value = reason instanceof Error ? reason.message : String(reason)
  } finally {
    deletingId.value = null
  }
}

onMounted(() => store.load())
</script>

<template>
  <main v-if="store.bootstrap" class="workstation" :style="{
    '--track-color': colors.trackColor,
    '--selected-color': colors.selectedTrackColor
  }">
    <SituationMap :aircraft="store.aircraft" :selected-id="store.selectedAircraftId"
      :track-color="colors.trackColor" :selected-track-color="colors.selectedTrackColor"
      @select="store.selectAircraft" />

    <header v-if="!topHidden" class="top-bar panel">
      <button class="run-button" :disabled="busy || !engine?.connected" @click="toggleRun">
        {{ group?.state === 'RUNNING' ? 'Ⅱ' : '▶' }}
      </button>
      <time>{{ formatTime(group?.simulationTimeSeconds) }}</time>
      <span>{{ store.aircraft.length }}/{{ store.aircraft.length }}</span>
      <span class="engine-light" :class="engine?.connected ? 'connected' : 'disconnected'" :title="engine?.message" />
      <button class="collapse" title="折叠状态栏" @click="topHidden = true">‹</button>
    </header>
    <button v-else class="restore top-restore" title="展开状态栏" @click="topHidden = false">›</button>

    <aside v-if="!leftHidden" class="aircraft-list panel">
      <div class="aircraft-list-actions">
        <CreateAircraftDialog :disabled="!engine?.connected" @created="store.load" />
        <button class="collapse" title="隐藏航空器列表" @click="leftHidden = true">‹</button>
      </div>
      <div v-for="item in store.aircraft" :key="item.id" class="aircraft-row-wrap"
        :class="{ selected: item.id === store.selectedAircraftId }">
        <button class="aircraft-row" @click="store.selectAircraft(item.id)">
          <span>{{ item.callsign }} {{ item.aircraftType }} {{ item.origin }}/{{ item.destination }}</span>
          <span>{{ item.route.slice(0, 3).join(' ') }} {{ item.activeInstruction ?? '' }}</span>
        </button>
        <button class="delete-aircraft" :disabled="deletingId === item.id || !engine?.connected"
          :title="`删除 ${item.callsign}`" @click="deleteAircraft(item.id)">×</button>
      </div>
    </aside>
    <button v-else class="restore left-restore" title="展开航空器列表" @click="leftHidden = false">›</button>

    <section class="command-dock">
      <input v-model="command" :disabled="!store.selectedAircraft || !engine?.connected"
        :placeholder="store.selectedAircraft ? store.selectedAircraft.callsign : '选择航空器'"
        autocomplete="off" spellcheck="false" @keydown="submitCommand" />
      <div class="instruction-queue panel">
        <div v-for="item in arrangedInstructions" :key="item.id" class="queue-item" :class="item.status.toLowerCase()"
          :title="item.failureMessage ?? ''">
          {{ item.text }}
        </div>
      </div>
    </section>

    <div v-if="store.error || commandError" class="connection-note">{{ store.error || commandError }}</div>
  </main>
  <div v-else class="loading">
    <span>{{ store.error || '正在连接仿真平台…' }}</span>
    <button v-if="store.error && !store.loading" @click="store.load">重试</button>
  </div>
</template>
