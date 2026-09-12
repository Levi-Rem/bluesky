// P09/P10：v2 指令 API（详细设计 2.2 §9.2/9.4）
export interface V2ApiErrorBody {
  code?: string
  message?: string
  fields?: string[]
  warnings?: string[]
  requestId?: string
}

export class V2ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly fields: string[] = [],
    readonly requestId?: string
  ) {
    super(message)
    this.name = 'V2ApiError'
  }
}

export interface InstructionDto {
  id: string
  status: string
  instruction_type?: string
  control_channel?: string
  conflict_key?: string
  scheduling?: string
  blockingReasons?: string[]
  guidanceTargets?: Array<{ id: string; state: string; channel: string }>
  replayed?: boolean
  [key: string]: unknown
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as V2ApiErrorBody
    throw new V2ApiError(
      body.message ?? `请求失败: ${response.status}`,
      response.status, body.code, body.fields ?? [], body.requestId
    )
  }
  return response.json() as Promise<T>
}

export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `key-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export const instructionsApi = {
  // 幂等键走 Idempotency-Key 请求头（openapi-v2.yaml：in:header；详细设计 9.1），
  // 请求体不得携带 idempotencyKey（评审 P0-13：原实现放 body 违反契约）
  submit: (aircraftId: string, payload: {
    aircraftRevision: number
    scheduling: 'REPLACE' | 'AFTER_COMPLETION'
    text?: string | null
    command?: { type: string; parameters?: Record<string, unknown> } | null
  }, idempotencyKey: string): Promise<InstructionDto> =>
    request<InstructionDto>(`/api/v2/aircraft/${aircraftId}/instructions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(payload)
    }),

  list: (aircraftId: string): Promise<{ items: InstructionDto[] }> =>
    request<{ items: InstructionDto[] }>(`/api/v2/aircraft/${aircraftId}/instructions`),

  get: (instructionId: string): Promise<InstructionDto> =>
    request<InstructionDto>(`/api/v2/instructions/${instructionId}`),

  cancel: (instructionId: string, instructionRevision: number,
    idempotencyKey: string): Promise<InstructionDto> =>
    request<InstructionDto>(`/api/v2/instructions/${instructionId}/actions/cancel`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ instructionRevision })
    })
}
