// P10（TDD 计划 15.2）：Enter=REPLACE；Shift+Enter=AFTER_COMPLETION；Ctrl+Enter 走可配置档（默认 DISABLED）。
// 旧 v1 的 APPEND 语义已删除（详细设计 6.2 不提供第三种调度语义）。
export type InsertionMode = 'REPLACE' | 'AFTER_COMPLETION' | 'DISABLED'

export interface EnterModifiers {
  ctrlKey: boolean
  shiftKey: boolean
}

export function insertionForEnter(modifiers: EnterModifiers): InsertionMode {
  if (modifiers.ctrlKey) return 'DISABLED'
  if (modifiers.shiftKey) return 'AFTER_COMPLETION'
  return 'REPLACE'
}
