import type { Bootstrap, ExerciseGroup, Instruction, ReferenceItem } from './types'
import type { InsertionMode } from './commandKeys'

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }))
    throw new Error(body.message ?? `请求失败: ${response.status}`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  bootstrap: () => json<Bootstrap>('/api/v1/workstation/bootstrap'),
  start: () => json<ExerciseGroup>('/api/v1/exercise-groups/GROUP-DEFAULT/start', { method: 'POST' }),
  pause: () => json<ExerciseGroup>('/api/v1/exercise-groups/GROUP-DEFAULT/pause', { method: 'POST' }),
  resume: () => json<ExerciseGroup>('/api/v1/exercise-groups/GROUP-DEFAULT/resume', { method: 'POST' }),
  instructions: (aircraftId: string) => json<Instruction[]>(`/api/v1/aircraft/${aircraftId}/instructions`),
  instruction: (aircraftId: string, text: string, insertion: InsertionMode) =>
    json<Instruction>(`/api/v1/aircraft/${aircraftId}/instructions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, insertion })
    }),
  createAircraft: (payload: Record<string, unknown>) =>
    json('/api/v1/exercise-groups/GROUP-DEFAULT/aircraft', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    }),
  deleteAircraft: (aircraftId: string) =>
    json<void>(`/api/v1/aircraft/${aircraftId}`, { method: 'DELETE' }),
  reference: (kind: 'airports' | 'waypoints' | 'aircraft-types', query: string) =>
    json<ReferenceItem[]>(`/api/v1/reference/${kind}?query=${encodeURIComponent(query)}`)
}
