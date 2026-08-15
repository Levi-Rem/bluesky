import type { InsertionMode } from './commandKeys'

export interface EngineState {
  connected: boolean
  status: string
  performanceModel: string
  message: string
}

export interface ExerciseGroup {
  id: string
  name: string
  state: 'READY' | 'RUNNING' | 'PAUSED'
  simulationTimeSeconds: number
}

export interface Aircraft {
  id: string
  assignedTerminalId: string
  callsign: string
  aircraftType: string
  wakeCategory: string
  transponderCode: string | null
  origin: string
  destination: string
  appearanceOffsetMinutes: number
  latitude: number | null
  longitude: number | null
  headingDegrees: number
  altitudeFeet: number
  speedKnots: number
  verticalSpeedFeetPerMinute: number
  route: string[]
  activeInstruction?: string | null
}

export interface Instruction {
  id: string
  aircraftId: string
  text: string
  type: string
  insertion: InsertionMode
  status: 'PENDING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  sequenceNumber: number
  failureCode?: string | null
  failureMessage?: string | null
}

export interface ReferenceItem {
  code: string
  name: string
  latitude?: number | null
  longitude?: number | null
}

export interface Bootstrap {
  terminal: { id: string; name: string }
  exerciseGroup: ExerciseGroup
  engine: EngineState
  aircraft: Aircraft[]
  instructions: Instruction[]
  uiParameters: {
    theme: string
    trackColor: string
    selectedTrackColor: string
  }
}
