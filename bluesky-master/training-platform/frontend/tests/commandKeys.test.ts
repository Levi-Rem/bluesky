import { describe, expect, it } from 'vitest'
import { insertionForEnter } from '../src/commandKeys'

describe('command input keyboard contract', () => {
  it('Enter=REPLACE', () => {
    expect(insertionForEnter({ ctrlKey: false, shiftKey: false })).toBe('REPLACE')
  })

  it('Ctrl+Enter 默认 DISABLED（可配置档）', () => {
    expect(insertionForEnter({ ctrlKey: true, shiftKey: false })).toBe('DISABLED')
  })

  it('Shift+Enter=AFTER_COMPLETION', () => {
    expect(insertionForEnter({ ctrlKey: false, shiftKey: true })).toBe('AFTER_COMPLETION')
  })
})
