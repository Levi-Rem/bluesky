import type { Instruction } from './types'

export function arrangeInstructionQueue(items: Instruction[]) {
  const pending = items
    .filter(item => item.status === 'PENDING')
    .sort((left, right) => right.sequenceNumber - left.sequenceNumber)
  const current = items
    .filter(item => item.status === 'EXECUTING')
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)
  const history = items
    .filter(item => item.status !== 'PENDING' && item.status !== 'EXECUTING')
    .sort((left, right) => right.sequenceNumber - left.sequenceNumber)
  return [...pending, ...current, ...history]
}
