import { describe, expect, it } from 'vitest'
import { insertionForEnter } from '../src/commandKeys'

describe('command input keyboard contract', () => {
  it('maps plain Enter to after-current insertion', () => {
    expect(insertionForEnter({ ctrlKey: false, shiftKey: false })).toBe('AFTER_CURRENT')
  })

  it('maps Ctrl+Enter to immediate execution', () => {
    expect(insertionForEnter({ ctrlKey: true, shiftKey: false })).toBe('IMMEDIATE')
  })

  it('maps Shift+Enter to append', () => {
    expect(insertionForEnter({ ctrlKey: false, shiftKey: true })).toBe('APPEND')
  })
})
