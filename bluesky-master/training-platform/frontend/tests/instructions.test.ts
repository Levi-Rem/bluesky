// P09/P10：v2 指令 API 与 store（幂等键、事件去重、缺口不乱序应用）
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useInstructionStore } from '../src/stores/instructions'
import { instructionsApi, newIdempotencyKey, V2ApiError } from '../src/api/instructions'

describe('instructions api', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('newIdempotencyKey 每次生成唯一键', () => {
    const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()))
    expect(keys.size).toBe(100)
  })

  it('submit 幂等键走 Idempotency-Key 头且 body 不携带；错误信封解析 code/fields/requestId', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 202,
      json: async () => ({ id: 'ins-1', status: 'DISPATCHING' })
    })
    const created = await instructionsApi.submit('ac-1', {
      aircraftRevision: 7, scheduling: 'REPLACE', command: { type: 'HDG' }
    }, 'key-1')
    expect(created.id).toBe('ins-1')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toBe('key-1')
    const body = JSON.parse(String(init.body))
    expect(body.aircraftRevision).toBe(7)
    expect('idempotencyKey' in body).toBe(false)

    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 422,
      json: async () => ({
        code: 'PERFORMANCE_LIMIT_EXCEEDED', message: '超出包线',
        fields: ['altitudeFtMsl'], requestId: 'req-9'
      })
    })
    const failure = await instructionsApi
      .submit('ac-1', {
        aircraftRevision: 7, scheduling: 'REPLACE', command: { type: 'ALT' }
      }, 'key-2')
      .catch((error) => error)
    expect(failure).toBeInstanceOf(V2ApiError)
    expect(failure.status).toBe(422)
    expect(failure.code).toBe('PERFORMANCE_LIMIT_EXCEEDED')
    expect(failure.fields).toEqual(['altitudeFtMsl'])
    expect(failure.requestId).toBe('req-9')
  })

  it('cancel 幂等键走 Idempotency-Key 头', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ id: 'ins-9', status: 'CANCELLED' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await instructionsApi.cancel('ins-9', 4, 'cancel-key')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toBe('cancel-key')
    const body = JSON.parse(String(init.body))
    expect('idempotencyKey' in body).toBe(false)
  })
})

describe('instruction store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('事件按 eventId 幂等应用：重复 eventId 不重复应用', () => {
    const store = useInstructionStore()
    const event = {
      eventId: 'PP-01:stream-1:10',
      eventType: 'instruction.updated',
      entityId: 'ins-1',
      payload: { id: 'ins-1', status: 'EXECUTING' }
    }
    expect(store.applyInstructionEvent(event)).toBe(true)
    expect(store.applyInstructionEvent(event)).toBe(false)
    expect(store.byId['ins-1'].status).toBe('EXECUTING')
  })

  it('事件合并保留 payload 全部字段（blockingReasons/revision）', () => {
    const store = useInstructionStore()
    store.upsert({
      id: 'ins-3', exercise_aircraft_id: 'ac-1', status: 'BLOCKED'
    } as never)
    store.applyInstructionEvent({
      eventId: 'e-1', eventType: 'instruction.updated', entityId: 'ins-3',
      payload: { id: 'ins-3', status: 'DISPATCHING', blockingReasons: [] }
    })
    expect(store.byId['ins-3'].blockingReasons).toEqual([])
  })

  it('waitingByAircraft 只统计未终态指令', () => {
    const store = useInstructionStore()
    store.upsert({ id: 'a', exercise_aircraft_id: 'ac-1', status: 'BLOCKED' } as never)
    store.upsert({ id: 'b', exercise_aircraft_id: 'ac-1', status: 'COMPLETED' } as never)
    store.upsert({ id: 'c', exercise_aircraft_id: 'ac-2', status: 'BLOCKED' } as never)
    expect(store.waitingByAircraft('ac-1').map((item) => item.id)).toEqual(['a'])
  })

  it('submit 幂等键走请求头并 upsert 结果', async () => {
    const store = useInstructionStore()
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 202,
      json: async () => ({ id: 'ins-2', status: 'DISPATCHING', exercise_aircraft_id: 'ac-1' })
    })
    vi.stubGlobal('fetch', fetchMock)

    const created = await store.submit('ac-1', 3, {
      scheduling: 'REPLACE', text: 'HDG 090'
    })
    expect(created.id).toBe('ins-2')
    expect(store.byId['ins-2'].status).toBe('DISPATCHING')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers['Idempotency-Key']).toBeTruthy()
    const body = JSON.parse(String(init.body))
    expect('idempotencyKey' in body).toBe(false)
  })

  it('网络失败重试复用同一幂等键（评审 P0-13）', async () => {
    const store = useInstructionStore()
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce({
        ok: true, status: 202,
        json: async () => ({ id: 'ins-4', status: 'DISPATCHING' })
      })
    vi.stubGlobal('fetch', fetchMock)

    const created = await store.submit('ac-1', 3, {
      scheduling: 'REPLACE', text: 'HDG 090'
    })
    expect(created.id).toBe('ins-4')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const first = fetchMock.mock.calls[0][1] as RequestInit
    const second = fetchMock.mock.calls[1][1] as RequestInit
    const firstKey = (first.headers as Record<string, string>)['Idempotency-Key']
    const secondKey = (second.headers as Record<string, string>)['Idempotency-Key']
    expect(firstKey).toBeTruthy()
    expect(secondKey).toBe(firstKey)
  })

  it('业务错误（V2ApiError）不重试', async () => {
    const store = useInstructionStore()
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false, status: 422,
      json: async () => ({ code: 'PERFORMANCE_LIMIT_EXCEEDED', message: '超出包线' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(store.submit('ac-1', 3, {
      scheduling: 'REPLACE', text: 'ALT 999'
    })).rejects.toBeInstanceOf(V2ApiError)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
