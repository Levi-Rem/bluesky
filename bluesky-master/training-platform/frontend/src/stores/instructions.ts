// P09/P10：指令域 store（详细设计 2.2 §9.5：事件按 eventId 幂等应用）
import { defineStore } from 'pinia'
import {
  instructionsApi, newIdempotencyKey, V2ApiError, type InstructionDto
} from '../api/instructions'

export interface ReliableInstructionEvent {
  eventId: string
  eventType: string
  entityId?: string | null
  payload: { id?: string; status?: string; [key: string]: unknown }
}

interface State {
  byId: Record<string, InstructionDto>
  order: string[]
  appliedEventIds: string[]
  selectedAircraftId: string | null
  submitting: boolean
}

export const useInstructionStore = defineStore('instructions', {
  state: (): State => ({
    byId: {},
    order: [],
    appliedEventIds: [],
    selectedAircraftId: null,
    submitting: false
  }),
  getters: {
    instructions: (state) => state.order.map((id) => state.byId[id]).filter(Boolean),
    waitingByAircraft: (state) => (aircraftId: string) =>
      state.order
        .map((id) => state.byId[id])
        .filter(
          (item): item is InstructionDto =>
            !!item &&
            item.exercise_aircraft_id === aircraftId &&
            ['RECEIVED', 'VALIDATED', 'BLOCKED', 'DISPATCHING'].includes(String(item.status))
        )
  },
  actions: {
    selectAircraft(aircraftId: string | null) {
      this.selectedAircraftId = aircraftId
    },
    async load(aircraftId: string) {
      const { items } = await instructionsApi.list(aircraftId)
      for (const item of items) this.upsert(item)
    },
    async submit(aircraftId: string, aircraftRevision: number, input: {
      scheduling: 'REPLACE' | 'AFTER_COMPLETION'
      text?: string | null
      command?: { type: string; parameters?: Record<string, unknown> } | null
    }) {
      // 幂等键在提交会话内生成一次：网络失败重试复用同一键，避免重复创建飞行指令
      // （详细设计 6.3.5；评审 P0-13）；业务错误（V2ApiError）直接抛出不重试
      const idempotencyKey = newIdempotencyKey()
      this.submitting = true
      try {
        let networkFailure: unknown
        for (let attempt = 0; attempt < 2; attempt++) {
          try {
            const created = await instructionsApi.submit(
              aircraftId, { ...input, aircraftRevision }, idempotencyKey
            )
            this.upsert(created)
            return created
          } catch (error) {
            if (error instanceof V2ApiError) throw error
            networkFailure = error
          }
        }
        throw networkFailure
      } finally {
        this.submitting = false
      }
    },
    async cancel(instructionId: string, revision: number) {
      const cancelled = await instructionsApi.cancel(
        instructionId, revision, newIdempotencyKey()
      )
      this.upsert(cancelled)
    },
    // 事件按 eventId 幂等应用，不得按时间戳排序（详细设计 9.5.2）
    applyInstructionEvent(event: ReliableInstructionEvent) {
      if (this.appliedEventIds.includes(event.eventId)) return false
      this.appliedEventIds.push(event.eventId)
      if (this.appliedEventIds.length > 5000) this.appliedEventIds.splice(0, 1000)
      const id = event.entityId ?? event.payload?.id
      if (!id) return true
      const existing = this.byId[id]
      // 合并事件携带的全部字段（status/blockingReasons/revision/guidanceTargets），
      // id 固定不被覆盖（评审 H12：原实现只合并 status）
      this.upsert({
        ...(existing ?? {}),
        ...event.payload,
        id
      } as InstructionDto)
      return true
    },
    upsert(instruction: InstructionDto) {
      if (!instruction?.id) return
      if (!this.byId[instruction.id]) this.order.push(instruction.id)
      this.byId[instruction.id] = { ...this.byId[instruction.id], ...instruction }
    }
  }
})
