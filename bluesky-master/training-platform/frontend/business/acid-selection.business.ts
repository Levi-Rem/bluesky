// @ts-nocheck -- standalone Node/Vitest business harness; excluded from product tsconfig.
import { afterAll, beforeEach, expect, test } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { useWorkstationStore } from '../src/store'

type AcidOutcome = { selected: string | null; candidates: string[] }
type AcidStore = ReturnType<typeof useWorkstationStore> & {
  selectAircraftBySuffix?: (suffix: string) => AcidOutcome
}

const evidence: Record<string, unknown> = {
  schemaVersion: 'workbench-client-command-evidence/1',
  ACID: {},
}

function aircraft(id: string, callsign: string) {
  return { id, callsign }
}

function prepare(entries: Array<{ id: string; callsign: string }>, selected = 'base') {
  const store = useWorkstationStore() as AcidStore
  store.bootstrap = {
    aircraft: entries,
    exerciseGroup: { id: 'group-acid-business', state: 'RUNNING' },
    instructions: [],
  } as never
  store.selectedAircraftId = selected
  return store
}

function acidCase(caseId: string, body: () => void) {
  test(caseId, () => {
    try {
      body()
      ;(evidence.ACID as Record<string, string>)[caseId] = 'PASS'
    } catch (error) {
      ;(evidence.ACID as Record<string, string>)[caseId] = 'FAIL'
      throw error
    }
  })
}

beforeEach(() => setActivePinia(createPinia()))

acidCase('unique-three-char', () => {
  const store = prepare([aircraft('base', 'CSN0001'), aircraft('target', 'CCA7582')])
  expect(store.selectAircraftBySuffix).toBeTypeOf('function')
  const result = store.selectAircraftBySuffix!('582')
  expect(result).toEqual({ selected: 'CCA7582', candidates: ['CCA7582'] })
  expect(store.selectedAircraftId).toBe('target')
})

acidCase('unique-four-char', () => {
  const store = prepare([aircraft('base', 'CSN0001'), aircraft('target', 'CSN3582')])
  expect(store.selectAircraftBySuffix).toBeTypeOf('function')
  const result = store.selectAircraftBySuffix!('3582')
  expect(result).toEqual({ selected: 'CSN3582', candidates: ['CSN3582'] })
  expect(store.selectedAircraftId).toBe('target')
})

acidCase('ambiguous-keeps-selection', () => {
  const store = prepare([
    aircraft('base', 'CSN0001'), aircraft('one', 'CSN3582'), aircraft('two', 'CCA7582'),
  ])
  expect(store.selectAircraftBySuffix).toBeTypeOf('function')
  const result = store.selectAircraftBySuffix!('582')
  expect(result.selected).toBeNull()
  expect(result.candidates).toEqual(['CSN3582', 'CCA7582'])
  expect(store.selectedAircraftId).toBe('base')
})

acidCase('not-found-keeps-selection', () => {
  const store = prepare([aircraft('base', 'CSN0001'), aircraft('target', 'CSN3582')])
  expect(store.selectAircraftBySuffix).toBeTypeOf('function')
  const result = store.selectAircraftBySuffix!('ZZZZ')
  expect(result).toEqual({ selected: null, candidates: [] })
  expect(store.selectedAircraftId).toBe('base')
})

acidCase('two-char-rejected', () => {
  const store = prepare([aircraft('base', 'CSN0001'), aircraft('target', 'CSN3582')])
  expect(store.selectAircraftBySuffix).toBeTypeOf('function')
  expect(() => store.selectAircraftBySuffix!('82')).toThrow()
  expect(store.selectedAircraftId).toBe('base')
})

afterAll(() => {
  const output = resolve(process.env.BS_CLIENT_EVIDENCE_PATH
    || '../../business-tests/artifacts/client-command-evidence.json')
  mkdirSync(dirname(output), { recursive: true })
  writeFileSync(output, JSON.stringify(evidence, null, 2), 'utf8')
})
