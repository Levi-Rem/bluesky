import { describe, expect, it } from 'vitest'
import { validateAircraftForm } from '../src/aircraftForm'

describe('aircraft creation form validation', () => {
  it('requires a two-to-seven character alphanumeric callsign', () => {
    expect(validateAircraftForm('A', '0010')).toContain('2 至 7')
    expect(validateAircraftForm('CCA-1', '0010')).toContain('2 至 7')
    expect(validateAircraftForm('CCA3582', '0010')).toBe('')
  })

  it('requires appearance offset to retain exactly four digits', () => {
    expect(validateAircraftForm('CCA3582', '12')).toContain('四位')
    expect(validateAircraftForm('CCA3582', '0010')).toBe('')
  })

  it('allows an empty optional transponder but rejects invalid entered codes', () => {
    expect(validateAircraftForm('CCA3582', '0010', '')).toBe('')
    expect(validateAircraftForm('CCA3582', '0010', '0000')).toContain('不能为 0000')
    expect(validateAircraftForm('CCA3582', '0010', '1288')).toContain('八进制')
    expect(validateAircraftForm('CCA3582', '0010', '1234')).toBe('')
  })
})
