import { describe, expect, it, vi } from 'vitest'
import { validateAircraftReferences } from '../src/aircraftReferenceValidation'

describe('aircraft reference validation', () => {
  it('accepts exact BlueSky type, airport and route matches', async () => {
    const search = vi.fn(async (kind: string, query: string) => [
      { code: query, name: `${kind}-${query}` }
    ])

    await expect(validateAircraftReferences(
      'a320', 'zsss', 'zbaa', 'CEN ZBAA', search
    )).resolves.toBe('')
  })

  it('returns the first unknown reference without creating aircraft', async () => {
    const search = vi.fn(async (kind: string, query: string) =>
      kind === 'aircraft-types' && query === 'XXXX' ? [] : [{ code: query, name: query }])

    await expect(validateAircraftReferences(
      'XXXX', 'ZSSS', 'ZBAA', 'CEN ZBAA', search
    )).resolves.toBe('未知机型: XXXX')
  })
})
