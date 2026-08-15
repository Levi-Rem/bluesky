import { describe, expect, it } from 'vitest'
import { arrangeInstructionQueue } from '../src/instructionQueue'
import type { Instruction } from '../src/types'

const item = (id: string, status: Instruction['status'], sequenceNumber: number): Instruction => ({
  id, aircraftId: 'aircraft-a', text: id, type: 'HDG', insertion: 'AFTER_CURRENT', status, sequenceNumber
})

describe('compact instruction queue arrangement', () => {
  it('shows pending above current and completed below current', () => {
    const result = arrangeInstructionQueue([
      item('done-1', 'COMPLETED', 1),
      item('current', 'EXECUTING', 2),
      item('next-1', 'PENDING', 3),
      item('next-2', 'PENDING', 4),
      item('done-0', 'CANCELLED', 0)
    ])

    expect(result.map(entry => entry.id)).toEqual([
      'next-2', 'next-1', 'current', 'done-1', 'done-0'
    ])
  })
})
