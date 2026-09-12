import type { Bootstrap, DisplaySettings, ExerciseGroup, Instruction, MapLayersResponse, ReferenceItem } from './types'
import type { InsertionMode } from './commandKeys'
import { copyDisplaySettings, DEFAULT_DISPLAY_SETTINGS } from './displaySettings'
import { instructionFromV2 } from './api/v2Mapping'

export interface ApiFieldError {
  field: string
  message: string
}

export class ApiClientError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly fieldErrors: ApiFieldError[] = [],
    readonly requestId?: string
  ) {
    super(message)
    this.name = 'ApiClientError'
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText })) as {
      code?: string; message?: string; fieldErrors?: ApiFieldError[]; requestId?: string
    }
    const fieldErrors = Array.isArray(body.fieldErrors) ? body.fieldErrors : []
    const detail = fieldErrors.map(item => `${item.field}: ${item.message}`).join('；')
    throw new ApiClientError(
      detail || body.message || `请求失败: ${response.status}`,
      response.status, body.code, fieldErrors, body.requestId
    )
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

let context: Bootstrap | null = null
const key = () => globalThis.crypto?.randomUUID?.() ?? `key-${Date.now()}-${Math.random().toString(16).slice(2)}`
const post = <T>(path: string, body: unknown) => json<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key() }, body: JSON.stringify(body) })
async function bootstrap(): Promise<Bootstrap> {
  const native = await json<Record<string, any>>('/api/v2/workstations/current/bootstrap')
  if (!native.nativeAdapter) return (context = await json<Bootstrap>('/api/v1/workstation/bootstrap'))
  context = {
    ...native,
    terminal: native.terminal, exerciseGroup: native.exerciseGroup,
    engine: { connected: native.engine?.state === 'CONNECTED', status: native.engine?.state ?? 'NOT_STARTED', performanceModel: 'BlueSky', message: native.engine?.state ?? '等待开训' },
    referenceData: { ready: Boolean(native.referenceSnapshot), status: native.referenceSnapshot ? 'READY' : 'SOURCE_UNAVAILABLE', pointCount: 0, counts: {}, message: native.referenceSnapshot ? '训练组固定快照' : '尚未固定参考快照' },
    aircraft: native.aircraft.map((a: Record<string, unknown>) => ({ ...a, route: a.route ?? [], activeInstruction: a.activeInstructionText, appearanceOffsetMinutes: 0 })),
    instructions: native.instructions.map(instructionFromV2),
    uiParameters: { ...DEFAULT_DISPLAY_SETTINGS, ...profileSettings(native.displayProfiles), theme: 'dark' }, uiParameterDefaults: { ...DEFAULT_DISPLAY_SETTINGS }
  } as Bootstrap
  return context
}
function profileSettings(profiles: Record<string, any>[] = []): Partial<DisplaySettings> {
  const chosen = profiles.find?.(p => p.name === '工作台显示设置') ?? profiles.find?.(p => p.is_default)
  if (!chosen) return {}
  try { return JSON.parse(chosen.content_json ?? chosen.contentJson ?? '{}') } catch { return {} }
}
async function lifecycle(action: 'start' | 'pause' | 'resume'): Promise<ExerciseGroup> {
  if (!context?.nativeAdapter) return json<ExerciseGroup>(`/api/v1/exercise-groups/GROUP-DEFAULT/${action}`, { method: 'POST' })
  const result = await post<{ state: ExerciseGroup['state']; revision: number }>(`/api/v2/exercise-groups/${context.exerciseGroup.id}/actions/${action}`, { groupRevision: context.exerciseGroup.revision })
  context.exerciseGroup = { ...context.exerciseGroup, ...result }
  return context.exerciseGroup
}

export const api = {
  bootstrap,
  deferAircraftTypeValidation: () => Boolean(context?.nativeAdapter && !context.engine.connected),
  mapLayers: () => json<MapLayersResponse>('/api/v1/workstation/map-layers'),
  saveDisplaySettings: async (payload: DisplaySettings) => {
    if (context?.nativeAdapter) {
      await post(`/api/v2/workstations/${context.terminal.id}/display-profiles`, { name: '工作台显示设置', content: copyDisplaySettings(payload) })
      return copyDisplaySettings(payload)
    }
    return json<DisplaySettings>('/api/v1/workstation/display-settings', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(copyDisplaySettings(payload))
    })
  },
  start: () => lifecycle('start'),
  pause: () => lifecycle('pause'),
  resume: () => lifecycle('resume'),
  instructions: async (aircraftId: string) => (await json<{ items: Record<string, unknown>[] }>(`/api/v2/aircraft/${aircraftId}/instructions`)).items.map(instructionFromV2),
  // v1 写入口已 410：指令提交统一走 v2（幂等键 + aircraftRevision + scheduling）
  instruction: (aircraftId: string, text: string, insertion: InsertionMode,
                aircraftRevision: number) => {
    if (insertion === 'DISABLED') return Promise.reject(new Error('此快捷键已禁用'))
    return json<Record<string, unknown>>(`/api/v2/aircraft/${aircraftId}/instructions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': (globalThis.crypto?.randomUUID
          ? globalThis.crypto.randomUUID()
          : `key-${Date.now()}-${Math.random().toString(16).slice(2)}`)
      },
      body: JSON.stringify({
        aircraftRevision,
        scheduling: insertion === 'AFTER_COMPLETION' ? 'AFTER_COMPLETION' : 'REPLACE',
        text
      })
    }).then(instructionFromV2)
  },
  createAircraft: async (payload: Record<string, unknown>) => {
    if (context?.nativeAdapter) {
      let latitude = payload.latitude, longitude = payload.longitude
      if (payload.initialWaypoint) {
        const points = await api.reference('waypoints', String(payload.initialWaypoint))
        const point = points.find(p => p.code === payload.initialWaypoint)
        if (!point) throw new Error('初始航路点不存在')
        latitude = point.latitude; longitude = point.longitude
      }
      return post(`/api/v2/exercise-groups/${context.exerciseGroup.id}/aircraft`, {
        aircraft: { callsign: payload.callsign, aircraftType: payload.aircraftType, wakeCategory: payload.wakeCategory },
        flightPlan: { origin: payload.origin, destination: payload.destination, plannedSquawk: payload.transponderCode || null, ssrMode: 'C', route: payload.route },
        initialState: { latitudeDeg: latitude, longitudeDeg: longitude, trueHeadingDeg: payload.headingDegrees, altitudeFtMsl: payload.altitudeFeet, indicatedAirspeedKt: payload.speedKnots },
        appearanceDelaySeconds: Number(payload.appearanceOffsetMinutes) * 60
      })
    }
    return json('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
  },
  deleteAircraft: async (aircraftId: string) => {
    if (!context?.nativeAdapter) return json<void>(`/api/v1/aircraft/${aircraftId}`, { method: 'DELETE' })
    const aircraft = await json<{ revision: number }>(`/api/v2/aircraft/${aircraftId}`)
    const preview = await post<{ confirmationToken: string }>(`/api/v2/aircraft/${aircraftId}/deletion-preview`, { aircraftRevision: aircraft.revision })
    return json(`/api/v2/aircraft/${aircraftId}`, { method: 'DELETE', headers: { 'Idempotency-Key': key(), 'X-Confirmation-Token': preview.confirmationToken } })
  },
  reference: async (kind: 'airports' | 'waypoints' | 'aircraft-types', query: string): Promise<ReferenceItem[]> => {
    if (context?.nativeAdapter && kind === 'aircraft-types')
      return json<ReferenceItem[]>(`/api/v2/exercise-groups/${context.exerciseGroup.id}/reference/aircraft-types?query=${encodeURIComponent(query)}`)
    if (context?.nativeAdapter && kind !== 'aircraft-types') {
      const data = await json<ReferenceItem[] | { items: ReferenceItem[] }>(`/api/v2/exercise-groups/${context.exerciseGroup.id}/reference-snapshot/${kind === 'waypoints' ? 'navaids' : kind}`)
      return (Array.isArray(data) ? data : data.items).filter(p => `${p.code} ${p.name ?? ''}`.toUpperCase().includes(query.toUpperCase())).slice(0, 50)
    }
    return json<ReferenceItem[]>(`/api/v1/reference/${kind}?query=${encodeURIComponent(query)}`)
  }
}
