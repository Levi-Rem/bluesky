import type { Instruction } from './types'

export function arrangeInstructionQueue(items: Instruction[]) {
  const waiting = (item: Instruction) => ['PENDING', 'RECEIVED', 'VALIDATED', 'BLOCKED', 'DISPATCHING'].includes(item.status)
  const pending = items
    .filter(waiting)
    .sort((left, right) => right.sequenceNumber - left.sequenceNumber)
  const current = items
    .filter(item => item.status === 'EXECUTING')
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)
  const history = items
    .filter(item => !waiting(item) && item.status !== 'EXECUTING')
    .sort((left, right) => right.sequenceNumber - left.sequenceNumber)
  return [...pending, ...current, ...history]
}
