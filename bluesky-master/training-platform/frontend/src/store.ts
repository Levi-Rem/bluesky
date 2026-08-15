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
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempt = 0
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
      restoreInstructionsFromSnapshot()
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : String(reason)
      return
    } finally {
      loading.value = false
    }
    try {
      connectEvents()
    } catch (reason) {
      error.value = `工作台已加载，状态连接失败：${reason instanceof Error ? reason.message : String(reason)}`
      scheduleReconnect()
    }
  }

  function connectEvents() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    events?.close()
    const source = new EventSource('/api/v1/events?exerciseGroupId=GROUP-DEFAULT')
    events = source
    source.addEventListener('snapshot', event => {
      bootstrap.value = JSON.parse((event as MessageEvent).data) as Bootstrap
      if (!aircraft.value.some(item => item.id === selectedAircraftId.value)) {
        selectedAircraftId.value = aircraft.value[0]?.id ?? null
      }
      restoreInstructionsFromSnapshot()
    })
    source.addEventListener('exercise-state', event => {
      if (bootstrap.value) bootstrap.value.exerciseGroup = JSON.parse((event as MessageEvent).data) as ExerciseGroup
    })
    source.addEventListener('engine-state', event => updateEngine(JSON.parse((event as MessageEvent).data)))
    source.addEventListener('aircraft-upserted', event => upsertAircraft(JSON.parse((event as MessageEvent).data)))
    source.addEventListener('aircraft-deleted', event => removeAircraft(JSON.parse((event as MessageEvent).data).id))
    source.addEventListener('instruction-upserted', event => upsertInstruction(JSON.parse((event as MessageEvent).data)))
    source.onerror = () => {
      if (events !== source) return
      source.close()
      events = null
      error.value = '状态连接中断，正在自动重连'
      scheduleReconnect()
    }
    source.onopen = () => {
      reconnectAttempt = 0
      error.value = ''
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) return
    const delay = Math.min(1000 * (2 ** reconnectAttempt), 30000)
    reconnectAttempt += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      try {
        connectEvents()
      } catch (reason) {
        error.value = `状态重连失败：${reason instanceof Error ? reason.message : String(reason)}`
        scheduleReconnect()
      }
    }, delay)
  }

  function restoreInstructionsFromSnapshot() {
    const aircraftId = selectedAircraftId.value
    instructions.value = aircraftId
      ? (bootstrap.value?.instructions ?? []).filter(item => item.aircraftId === aircraftId)
      : []
  }

  function upsertAircraft(next: Aircraft) {
    if (!bootstrap.value) return
    const index = bootstrap.value.aircraft.findIndex(item => item.id === next.id)
    if (index >= 0) bootstrap.value.aircraft[index] = next
    else bootstrap.value.aircraft.push(next)
  }

  function updateEngine(next: EngineState) {
    if (bootstrap.value) bootstrap.value.engine = next
  }

  function removeAircraft(id: string) {
    if (!bootstrap.value) return
    bootstrap.value.aircraft = bootstrap.value.aircraft.filter(item => item.id !== id)
    if (selectedAircraftId.value === id) {
      selectedAircraftId.value = aircraft.value[0]?.id ?? null
      void loadInstructions().catch(reason => {
        error.value = reason instanceof Error ? reason.message : String(reason)
      })
    }
  }

  function upsertInstruction(next: Instruction) {
    if (next.aircraftId !== selectedAircraftId.value) return
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
