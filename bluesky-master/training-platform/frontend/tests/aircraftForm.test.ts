import { describe, expect, it } from 'vitest'
import { buildAircraftPayload, validateAircraftForm, validateAircraftPosition } from '../src/aircraftForm'

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

  it('normalizes cleared coordinates and accepts an initial waypoint instead', () => {
    expect(validateAircraftPosition('', '', '')).toContain('初始航路点')
    expect(validateAircraftPosition('', '', 'CEN')).toBe('')
    expect(validateAircraftPosition(31, '', 'CEN')).toBe('')

    const payload = buildAircraftPayload({
      callsign: 'CCA3582', aircraftType: 'A320', wakeCategory: 'M', transponderCode: '',
      origin: 'ZSSS', destination: 'ZBAA', appearanceOffset: '0000',
      latitude: '', longitude: '', initialWaypoint: 'cen', headingDegrees: 90,
      altitudeFeet: 9000, speedKnots: 250, route: ''
    })
    expect(payload.latitude).toBeNull()
    expect(payload.longitude).toBeNull()
    expect(payload.initialWaypoint).toBe('CEN')
    expect(payload.route).toEqual(['ZBAA'])
  })
})
