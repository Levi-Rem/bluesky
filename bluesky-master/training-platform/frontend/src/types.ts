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
  state: 'READY' | 'STARTING' | 'RUNNING' | 'PAUSING' | 'PAUSED' | 'RESUMING' | 'RECOVERING' | 'RECOVERY_FAILED' | 'ENDING' | 'ENDED'
  revision?: number
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
  revision?: number
}

export interface FakeTarget {
  id: string
  callsign: string
  state: string
  target_kind: string
  latitude_deg: number
  longitude_deg: number
  true_heading_deg: number
  ground_speed_kt: number
  altitude_ft_msl: number
}

export interface Instruction {
  id: string
  aircraftId: string
  text: string
  type: string
  insertion: InsertionMode
  status: 'PENDING' | 'RECEIVED' | 'VALIDATED' | 'BLOCKED' | 'DISPATCHING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'REPLACED'
  revision?: number
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

export interface ReferenceDataState {
  ready: boolean
  status: 'LOADING' | 'SOURCE_UNAVAILABLE' | 'VALIDATION_FAILED' | 'SYNC_PENDING' | 'READY' | 'SYNC_FAILED'
  pointCount: number
  counts: Record<string, number>
  message: string
}

export type MapLayerCategory = 'WAYPOINT' | 'AIRWAY' | 'PHYSICAL_SECTOR' | 'WEATHER'
export type MapFeatureType = MapLayerCategory | 'WIND_FIELD_POINT' | 'SIGNIFICANT_WEATHER_AREA'

export interface GeoJsonGeometry {
  type: 'Point' | 'LineString' | 'Polygon' | 'MultiPolygon'
  coordinates: unknown
}

export interface RuntimeMapFeature {
  featureId: string
  featureType: MapFeatureType
  code: string | null
  name: string | null
  geometry: GeoJsonGeometry
}

export interface RuntimeMapLayer {
  category: MapLayerCategory
  name: string
  count: number
  features: RuntimeMapFeature[]
}

export interface MapLayersResponse {
  available: boolean
  layers: RuntimeMapLayer[]
}

export interface DisplaySettings {
  trackColor: string
  selectedTrackColor: string
  mapWaypointColor: string
  mapAirwayColor: string
  mapSectorColor: string
  mapSectorFillColor: string
  mapWeatherColor: string
  mapWeatherFillColor: string
}

export type MapLayerVisibility = Record<MapLayerCategory, boolean>

export interface Bootstrap {
  fakeTargets?: FakeTarget[]
  nativeAdapter?: boolean
  streamEpoch?: string
  snapshotSequence?: number
  terminal: { id: string; name: string }
  exerciseGroup: ExerciseGroup
  engine: EngineState
  referenceData: ReferenceDataState
  aircraft: Aircraft[]
  instructions: Instruction[]
  uiParameters: DisplaySettings & {
    theme: string
  }
  uiParameterDefaults: DisplaySettings
}
