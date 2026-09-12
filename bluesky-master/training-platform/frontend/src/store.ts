import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from './api'
import type { Aircraft, Bootstrap, DisplaySettings, EngineState, ExerciseGroup, Instruction, MapLayerVisibility, ReferenceDataState, RuntimeMapLayer } from './types'

export const useWorkstationStore = defineStore('workstation', () => {
  const bootstrap = ref<Bootstrap | null>(null)
  const selectedAircraftId = ref<string | null>(null)
  const instructions = ref<Instruction[]>([])
  const error = ref('')
  const loading = ref(false)
  const mapDataAvailable = ref(false)
  const mapLayers = ref<RuntimeMapLayer[]>([])
  const mapLayerVisibility = ref<MapLayerVisibility>({
    WAYPOINT: false, AIRWAY: false, PHYSICAL_SECTOR: false, WEATHER: false
  })
  let mapLayersPromise: Promise<void> | null = null
  let events: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempt = 0
  let instructionRequest = 0
  let nativeCursor = ''
  let refreshPending: Promise<void> | null = null
  let refreshRequested = false

  const aircraft = computed(() => bootstrap.value?.aircraft ?? [])
  const mapAircraft = computed<Aircraft[]>(() => [
    ...aircraft.value,
    ...(bootstrap.value?.fakeTargets ?? []).filter(f => f.target_kind === 'RADAR_SYNTHETIC' && ['ACTIVE', 'STOPPED'].includes(f.state)).map(f => ({
      id: `fake:${f.id}`, callsign: f.callsign, aircraftType: 'FAKE', assignedTerminalId: '', wakeCategory: '', transponderCode: null,
      origin: '', destination: '', route: [], appearanceOffsetMinutes: 0, latitude: Number(f.latitude_deg), longitude: Number(f.longitude_deg),
      headingDegrees: Number(f.true_heading_deg), altitudeFeet: Number(f.altitude_ft_msl), speedKnots: Number(f.ground_speed_kt), verticalSpeedFeetPerMinute: 0
    }))
  ])
  const selectedAircraft = computed(() =>
    aircraft.value.find(item => item.id === selectedAircraftId.value) ?? null)

  async function load() {
    loading.value = true
    error.value = ''
    try {
      bootstrap.value = await api.bootstrap()
      if (bootstrap.value.nativeAdapter) nativeCursor = `${bootstrap.value.terminal.id}:${bootstrap.value.streamEpoch}:${bootstrap.value.snapshotSequence}`
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

  function loadMapLayersOnce() {
    if (mapLayersPromise) return mapLayersPromise
    mapLayersPromise = api.mapLayers().then(response => {
      mapDataAvailable.value = response.available
      mapLayers.value = response.available ? response.layers : []
    }).catch(() => {
      mapDataAvailable.value = false
      mapLayers.value = []
    })
    return mapLayersPromise
  }

  function setMapLayerVisible(category: keyof MapLayerVisibility, visible: boolean) {
    mapLayerVisibility.value[category] = visible
  }

  async function saveDisplaySettings(settings: DisplaySettings) {
    const saved = await api.saveDisplaySettings(settings)
    if (bootstrap.value) bootstrap.value.uiParameters = { ...bootstrap.value.uiParameters, ...saved }
    return saved
  }

  function connectEvents() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    events?.close()
    const state = bootstrap.value
    const source = new EventSource(state?.nativeAdapter
      ? `/api/v2/events?exerciseGroupId=${encodeURIComponent(state.exerciseGroup.id)}&terminalId=${encodeURIComponent(state.terminal.id)}&cursor=${encodeURIComponent(nativeCursor)}`
      : '/api/v1/events?exerciseGroupId=GROUP-DEFAULT')
    events = source
    if (state?.nativeAdapter) {
      source.addEventListener('aircraft.state.frame', event => {
        const frame = JSON.parse((event as MessageEvent).data).payload
        if (!bootstrap.value || !frame) return
        bootstrap.value.exerciseGroup.simulationTimeSeconds = frame.simulationTimeSeconds
        if (frame.fakeTargets) bootstrap.value.fakeTargets = frame.fakeTargets
        for (const dynamic of frame.aircraft ?? []) {
          const old = bootstrap.value.aircraft.find(a => a.id === dynamic.id)
          if (old) upsertAircraft({ ...old, ...dynamic })
        }
      })
      const refresh = (event: Event) => {
        const id = (event as MessageEvent).lastEventId
        if (id) nativeCursor = id
        refreshRequested = true
        if (!refreshPending) refreshPending = (async () => {
          do {
            refreshRequested = false
            await refreshNative()
          } while (refreshRequested)
        })().finally(() => { refreshPending = null })
      }
      for (const name of ['group.state.changed', 'aircraft.created', 'aircraft.updated', 'aircraft.lifecycle.changed', 'aircraft.deleted', 'instruction.status.changed', 'command.report.created', 'flight.report.created', 'fake-target.changed', 'script.delivered', 'message.received']) source.addEventListener(name, refresh)
    }
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
    source.addEventListener('reference-data-state', event => updateReferenceData(JSON.parse((event as MessageEvent).data)))
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

  async function refreshNative() {
    try {
      const next = await api.bootstrap()
      if (events) bootstrap.value = next
      await loadInstructions()
    } catch (reason) { error.value = reason instanceof Error ? reason.message : String(reason) }
  }

  function scheduleReconnect() {
    if (reconnectTimer) return
    const delay = Math.min(1000 * (2 ** reconnectAttempt), 30000)
    reconnectAttempt += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (bootstrap.value?.nativeAdapter) {
        // A Java restart changes the stream epoch. EventSource hides HTTP 409,
        // so obtain a consistent snapshot and cursor before reconnecting.
        void load().then(() => { if (!events) scheduleReconnect() })
        return
      }
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

  function updateReferenceData(next: ReferenceDataState) {
    if (bootstrap.value) bootstrap.value.referenceData = next
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
    if (!aircraft.value.some(a => a.id === id)) return
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
    if (bootstrap.value?.nativeAdapter) await refreshNative()
    else removeAircraft(id)
  }

  return {
    bootstrap, aircraft, mapAircraft, selectedAircraft, selectedAircraftId, instructions, error, loading,
    mapDataAvailable, mapLayers, mapLayerVisibility,
    load, loadMapLayersOnce, setMapLayerVisible, saveDisplaySettings,
    loadInstructions, selectAircraft, deleteAircraft, upsertAircraft, upsertInstruction,
    updateEngine, updateReferenceData
  }
})
