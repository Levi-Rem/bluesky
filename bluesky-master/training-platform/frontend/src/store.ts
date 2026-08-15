import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from './api'
import type { Aircraft, Bootstrap, EngineState, ExerciseGroup, Instruction } from './types'

export const useWorkstationStore = defineStore('workstation', () => {
  const bootstrap = ref<Bootstrap | null>(null)
  const selectedAircraftId = ref<string | null>(null)
  const instructions = ref<Instruction[]>([])
  const error = ref('')
  const loading = ref(false)
  let events: EventSource | null = null
  let instructionRequest = 0

  const aircraft = computed(() => bootstrap.value?.aircraft ?? [])
  const selectedAircraft = computed(() =>
    aircraft.value.find(item => item.id === selectedAircraftId.value) ?? null)

  async function load() {
    loading.value = true
    error.value = ''
    try {
      bootstrap.value = await api.bootstrap()
      if (!bootstrap.value.aircraft.some(item => item.id === selectedAircraftId.value)) {
        selectedAircraftId.value = bootstrap.value.aircraft[0]?.id ?? null
      }
      await loadInstructions()
      connectEvents()
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : String(reason)
    } finally {
      loading.value = false
    }
  }

  function connectEvents() {
    events?.close()
    events = new EventSource('/api/v1/events?exerciseGroupId=GROUP-DEFAULT')
    events.addEventListener('snapshot', event => {
      bootstrap.value = JSON.parse((event as MessageEvent).data) as Bootstrap
      if (!aircraft.value.some(item => item.id === selectedAircraftId.value)) {
        selectedAircraftId.value = aircraft.value[0]?.id ?? null
      }
      void loadInstructions()
    })
    events.addEventListener('exercise-state', event => {
      if (bootstrap.value) bootstrap.value.exerciseGroup = JSON.parse((event as MessageEvent).data) as ExerciseGroup
    })
    events.addEventListener('engine-state', event => updateEngine(JSON.parse((event as MessageEvent).data)))
    events.addEventListener('aircraft-upserted', event => upsertAircraft(JSON.parse((event as MessageEvent).data)))
    events.addEventListener('aircraft-deleted', event => removeAircraft(JSON.parse((event as MessageEvent).data).id))
    events.addEventListener('instruction-upserted', event => upsertInstruction(JSON.parse((event as MessageEvent).data)))
    events.onerror = () => { error.value = '状态连接中断，正在自动重连' }
    events.onopen = () => { error.value = '' }
  }

  function upsertAircraft(next: Aircraft) {
    if (!bootstrap.value) return
    const index = bootstrap.value.aircraft.findIndex(item => item.id === next.id)
    if (index >= 0) bootstrap.value.aircraft[index] = next
    else bootstrap.value.aircraft.push(next)
  }

  function updateEngine(next: EngineState) {
    if (bootstrap.value) bootstrap.value.engine = next
    else bootstrap.value = {
      terminal: { id: '', name: '' },
      exerciseGroup: { id: '', name: '', state: 'READY', simulationTimeSeconds: 0 },
      engine: next,
      aircraft: [],
      uiParameters: { theme: 'DEFAULT_DARK', trackColor: '#58d7ff', selectedTrackColor: '#ffe66d' }
    }
  }

  function removeAircraft(id: string) {
    if (!bootstrap.value) return
    bootstrap.value.aircraft = bootstrap.value.aircraft.filter(item => item.id !== id)
    if (selectedAircraftId.value === id) {
      selectedAircraftId.value = aircraft.value[0]?.id ?? null
      void loadInstructions()
    }
  }

  function upsertInstruction(next: Instruction) {
    const index = instructions.value.findIndex(item => item.id === next.id)
    if (index >= 0) instructions.value[index] = next
    else instructions.value.push(next)
    instructions.value.sort((a, b) => a.sequenceNumber - b.sequenceNumber)
  }

  async function selectAircraft(id: string) {
    selectedAircraftId.value = id
    await loadInstructions()
  }

  async function loadInstructions() {
    const request = ++instructionRequest
    const aircraftId = selectedAircraftId.value
    const result = aircraftId ? await api.instructions(aircraftId) : []
    if (request === instructionRequest && aircraftId === selectedAircraftId.value) {
      instructions.value = result
    }
  }

  async function deleteAircraft(id: string) {
    await api.deleteAircraft(id)
    removeAircraft(id)
  }

  return {
    bootstrap, aircraft, selectedAircraft, selectedAircraftId, instructions, error, loading,
    load, loadInstructions, selectAircraft, deleteAircraft, upsertAircraft, upsertInstruction, updateEngine
  }
})
