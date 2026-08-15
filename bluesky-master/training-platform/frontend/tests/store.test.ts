import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkstationStore } from '../src/store'
import type { Instruction } from '../src/types'
import type { EngineState } from '../src/types'

const apiMock = vi.hoisted(() => ({
  bootstrap: vi.fn(), instructions: vi.fn(), deleteAircraft: vi.fn()
}))

vi.mock('../src/api', () => ({ api: apiMock }))

describe('workstation instruction projection', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('upserts the REST result and matching SSE event as one queue item', () => {
    const store = useWorkstationStore()
    const executing: Instruction = {
      id: 'instruction-1', text: 'HDG 090', type: 'HDG', insertion: 'AFTER_CURRENT',
      status: 'EXECUTING', sequenceNumber: 1
    }
    store.upsertInstruction(executing)
    store.upsertInstruction({ ...executing, status: 'COMPLETED' })

    expect(store.instructions).toHaveLength(1)
    expect(store.instructions[0].status).toBe('COMPLETED')
  })

  it('updates the engine lamp from a runtime engine-state event', () => {
    const store = useWorkstationStore()
    const disconnected: EngineState = {
      connected: false, status: 'DISCONNECTED', performanceModel: 'UNKNOWN', message: '连接超时'
    }

    store.updateEngine(disconnected)

    expect(store.bootstrap?.engine.connected).toBe(false)
    expect(store.bootstrap?.engine.message).toBe('连接超时')
  })

  it('surfaces a bootstrap failure instead of spinning forever', async () => {
    apiMock.bootstrap.mockRejectedValueOnce(new Error('平台不可用'))
    const store = useWorkstationStore()

    await store.load()

    expect(store.loading).toBe(false)
    expect(store.bootstrap).toBeNull()
    expect(store.error).toBe('平台不可用')
  })

  it('does not let an older aircraft selection overwrite the current queue', async () => {
    const pending = new Map<string, (value: Instruction[]) => void>()
    apiMock.instructions.mockImplementation((id: string) =>
      new Promise<Instruction[]>(resolve => pending.set(id, resolve)))
    const store = useWorkstationStore()
    store.bootstrap = bootstrapWithAircraft()

    const first = store.selectAircraft('aircraft-a')
    const second = store.selectAircraft('aircraft-b')
    pending.get('aircraft-b')?.([instruction('b')])
    await second
    pending.get('aircraft-a')?.([instruction('a')])
    await first

    expect(store.selectedAircraftId).toBe('aircraft-b')
    expect(store.instructions.map(item => item.id)).toEqual(['b'])
  })

  it('deletes through the API and removes the local aircraft', async () => {
    apiMock.deleteAircraft.mockResolvedValueOnce(undefined)
    const store = useWorkstationStore()
    store.bootstrap = bootstrapWithAircraft()
    store.selectedAircraftId = 'aircraft-a'
    apiMock.instructions.mockResolvedValue([])

    await store.deleteAircraft('aircraft-a')

    expect(apiMock.deleteAircraft).toHaveBeenCalledWith('aircraft-a')
    expect(store.aircraft.map(item => item.id)).toEqual(['aircraft-b'])
  })
})

function instruction(id: string): Instruction {
  return {
    id, text: 'HDG 090', type: 'HDG', insertion: 'AFTER_CURRENT',
    status: 'EXECUTING', sequenceNumber: 1
  }
}

function bootstrapWithAircraft() {
  return {
    terminal: { id: 'PP-DEFAULT', name: 'default' },
    exerciseGroup: { id: 'GROUP-DEFAULT', name: 'default', state: 'READY' as const, simulationTimeSeconds: 0 },
    engine: { connected: true, status: 'CONNECTED', performanceModel: 'OPENAP', message: 'ok' },
    aircraft: ['aircraft-a', 'aircraft-b'].map((id, index) => ({
      id, assignedTerminalId: 'PP-DEFAULT', callsign: `CCA${index + 1}`, aircraftType: 'A320',
      wakeCategory: 'M', transponderCode: '1234', origin: 'ZSSS', destination: 'ZBAA',
      appearanceOffsetMinutes: 0, latitude: 31, longitude: 121, headingDegrees: 90,
      altitudeFeet: 9000, speedKnots: 250, verticalSpeedFeetPerMinute: 0, route: ['ZBAA']
    })),
    uiParameters: { theme: 'DEFAULT_DARK', trackColor: '#fff', selectedTrackColor: '#ff0' }
  }
}
