import type { Instruction } from '../types'

export function instructionFromV2(row: Record<string, unknown>): Instruction {
  return {
    id: String(row.id),
    aircraftId: String(row.aircraftId ?? row.exercise_aircraft_id),
    text: String(row.text ?? row.rawText ?? row.raw_text ?? ''),
    type: String(row.type ?? row.instructionType ?? row.instruction_type ?? ''),
    insertion: (row.scheduling ?? row.insertion ?? 'REPLACE') as Instruction['insertion'],
    status: row.status as Instruction['status'],
    sequenceNumber: Number(row.sequenceNumber ?? row.sequence_number ?? 0),
    revision: Number(row.revision ?? 1),
    failureCode: (row.failureCode ?? row.failure_code) as string | undefined,
    failureMessage: (row.failureMessage ?? row.failure_message) as string | undefined
  }
}
