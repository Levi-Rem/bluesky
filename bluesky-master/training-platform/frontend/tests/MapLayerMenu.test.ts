import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MapLayerMenu from '../src/MapLayerMenu.vue'
import type { MapLayerVisibility, RuntimeMapLayer } from '../src/types'

const visibility: MapLayerVisibility = {
  WAYPOINT: false, AIRWAY: false, PHYSICAL_SECTOR: false, WEATHER: false
}
const layers: RuntimeMapLayer[] = [
  { category: 'WAYPOINT', name: '航路点', count: 2, features: [] },
  { category: 'AIRWAY', name: '航线', count: 1, features: [] },
  { category: 'PHYSICAL_SECTOR', name: '扇区', count: 0, features: [] },
  { category: 'WEATHER', name: '天气', count: 3, features: [] }
]

describe('MapLayerMenu', () => {
  it('shows four independent layer switches and keeps the menu open after toggling', async () => {
    const wrapper = mount(MapLayerMenu, { props: { available: true, layers, visibility } })
    await wrapper.get('.map-layer-trigger').trigger('click')

    expect(wrapper.findAll('.map-layer-option')).toHaveLength(4)
    const inputs = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(inputs.every(input => !input.element.checked)).toBe(true)
    expect(inputs[2].element.disabled).toBe(true)
    await inputs[0].setValue(true)

    expect(wrapper.emitted('toggle')?.[0]).toEqual(['WAYPOINT', true])
    expect(wrapper.find('.map-layer-menu').exists()).toBe(true)
  })

  it('disables the map button when the snapshot is unavailable', () => {
    const wrapper = mount(MapLayerMenu, { props: { available: false, layers: [], visibility } })
    expect(wrapper.get<HTMLButtonElement>('.map-layer-trigger').element.disabled).toBe(true)
    expect(wrapper.text()).not.toContain('不可用')
  })
})
