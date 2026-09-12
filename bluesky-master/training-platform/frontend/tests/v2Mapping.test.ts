import { describe, expect, it, vi } from 'vitest'
import { instructionFromV2 } from '../src/api/v2Mapping'
import { api } from '../src/api'

describe('v2 instruction boundary', () => {
  it('uses native type lookup and server-relative appearance when creating aircraft', async () => {
    const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({nativeAdapter:true,terminal:{id:'t'},exerciseGroup:{id:'g',simulationTimeSeconds:5},engine:{state:'CONNECTED'},aircraft:[],instructions:[],displayProfiles:[]})})
    vi.stubGlobal('fetch',fetch)
    try {
      await api.bootstrap()
      await api.reference('aircraft-types','B738')
      expect(fetch.mock.calls[1][0]).toBe('/api/v2/exercise-groups/g/reference/aircraft-types?query=B738')
      await api.createAircraft({callsign:'TEST1',appearanceOffsetMinutes:2})
      const body=JSON.parse(fetch.mock.calls[2][1].body)
      expect(body.appearanceDelaySeconds).toBe(120)
      expect(body.targetAppearanceSimulationTimeSeconds).toBeUndefined()
    } finally { vi.unstubAllGlobals() }
  })
  it('preserves aircraft identity and dispatch status from native responses', () => {
    const value = instructionFromV2({ id: 'i', exercise_aircraft_id: 'a', instruction_type: 'HDG', raw_text: 'HDG 120', status: 'DISPATCHING', sequence_number: 4, revision: 3 })
    expect(value).toMatchObject({ aircraftId: 'a', text: 'HDG 120', type: 'HDG', sequenceNumber: 4, revision: 3, status: 'DISPATCHING' })
  })
  it('does not issue HTTP for a disabled shortcut', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(api.instruction('a', 'HDG 120', 'DISABLED', 1)).rejects.toThrow('禁用')
    expect(fetch).not.toHaveBeenCalled(); vi.unstubAllGlobals()
  })
})
