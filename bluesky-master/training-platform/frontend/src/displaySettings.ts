import type { DisplaySettings } from './types'

export const DEFAULT_DISPLAY_SETTINGS: DisplaySettings = {
  trackColor: '#3FAE6D',
  selectedTrackColor: '#27E58D',
  mapWaypointColor: '#7FD3FF',
  mapAirwayColor: '#4AA8D8',
  mapSectorColor: '#D6A7FF',
  mapSectorFillColor: '#7B4DB3',
  mapWeatherColor: '#FFCF66',
  mapWeatherFillColor: '#D9822B'
}

export function copyDisplaySettings(source: DisplaySettings): DisplaySettings {
  return {
    trackColor: source.trackColor,
    selectedTrackColor: source.selectedTrackColor,
    mapWaypointColor: source.mapWaypointColor,
    mapAirwayColor: source.mapAirwayColor,
    mapSectorColor: source.mapSectorColor,
    mapSectorFillColor: source.mapSectorFillColor,
    mapWeatherColor: source.mapWeatherColor,
    mapWeatherFillColor: source.mapWeatherFillColor
  }
}
