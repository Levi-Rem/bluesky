export function displayLabel(feature: { code: string | null; name: string | null }) {
  return feature.code?.trim() || feature.name?.trim() || ''
}

export function hexWithAlpha(color: string) {
  const normalized = color.trim().replace(/^#/, '')
  const value = Number.parseInt(normalized, 16)
  const red = (value >> 16) & 255
  const green = (value >> 8) & 255
  const blue = value & 255
  return `rgba(${red}, ${green}, ${blue}, 0.2)`
}
