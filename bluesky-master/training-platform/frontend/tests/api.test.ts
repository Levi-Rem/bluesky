import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiClientError } from '../src/api'
import type { DisplaySettings } from '../src/types'

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

  it('sends only display color fields when bootstrap settings also contain theme', async () => {
    const colors: DisplaySettings = {
      trackColor: '#3FAE6D',
      selectedTrackColor: '#27E58D',
      mapWaypointColor: '#7FD3FF',
      mapAirwayColor: '#4AA8D8',
      mapSectorColor: '#D6A7FF',
      mapSectorFillColor: '#7B4DB3',
      mapWeatherColor: '#FFCF66',
      mapWeatherFillColor: '#D9822B'
    }
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => colors
    })
    vi.stubGlobal('fetch', fetchMock)

    const bootstrapSettings: DisplaySettings & { theme: string } = {
      ...colors,
      theme: 'DEFAULT_DARK'
    }

    await api.saveDisplaySettings(bootstrapSettings)

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual(colors)
  })
})
