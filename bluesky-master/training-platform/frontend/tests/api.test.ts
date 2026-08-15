import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiClientError } from '../src/api'

describe('API error details', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('preserves field errors, code and request id from the backend', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      json: async () => ({
        code: 'FIELD_VALIDATION_FAILED',
        message: '请求字段无效',
        fieldErrors: [{ field: 'position', message: '必须填写经纬度或初始航路点' }],
        requestId: 'request-1'
      })
    }))

    const error = await api.createAircraft({}).catch(reason => reason) as ApiClientError

    expect(error).toBeInstanceOf(ApiClientError)
    expect(error.message).toContain('position')
    expect(error.fieldErrors).toEqual([
      { field: 'position', message: '必须填写经纬度或初始航路点' }
    ])
    expect(error.code).toBe('FIELD_VALIDATION_FAILED')
    expect(error.requestId).toBe('request-1')
  })
})
