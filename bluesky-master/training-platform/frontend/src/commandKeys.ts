export type InsertionMode = 'AFTER_CURRENT' | 'IMMEDIATE' | 'APPEND'

export interface EnterModifiers {
  ctrlKey: boolean
  shiftKey: boolean
}

export function insertionForEnter(modifiers: EnterModifiers): InsertionMode {
  if (modifiers.ctrlKey) return 'IMMEDIATE'
  if (modifiers.shiftKey) return 'APPEND'
  return 'AFTER_CURRENT'
}
