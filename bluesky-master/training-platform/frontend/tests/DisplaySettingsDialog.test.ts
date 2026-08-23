import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DisplaySettingsDialog from '../src/DisplaySettingsDialog.vue'
import type { DisplaySettings } from '../src/types'

const saved: DisplaySettings = {
  trackColor: '#111111', selectedTrackColor: '#222222', mapWaypointColor: '#333333',
  mapAirwayColor: '#444444', mapSectorColor: '#555555', mapSectorFillColor: '#666666',
  mapWeatherColor: '#777777', mapWeatherFillColor: '#888888'
}
const defaults: DisplaySettings = {
  trackColor: '#3FAE6D', selectedTrackColor: '#27E58D', mapWaypointColor: '#7FD3FF',
  mapAirwayColor: '#4AA8D8', mapSectorColor: '#D6A7FF', mapSectorFillColor: '#7B4DB3',
  mapWeatherColor: '#FFCF66', mapWeatherFillColor: '#D9822B'
}

describe('DisplaySettingsDialog', () => {
  it('renders eight settings and previews restored defaults before saving', async () => {
    const wrapper = mount(DisplaySettingsDialog, {
      props: { open: true, saved, defaults, busy: false, error: '' }
    })

    expect(wrapper.findAll('input[type="color"]')).toHaveLength(8)
    await wrapper.get('.display-settings-actions button').trigger('click')

    expect(wrapper.emitted('preview')?.[0]).toEqual([defaults])
    expect(wrapper.emitted('save')).toBeUndefined()
  })

  it('restores saved preview when cancelled', async () => {
    const wrapper = mount(DisplaySettingsDialog, {
      props: { open: true, saved, defaults, busy: false, error: '' }
    })

    const buttons = wrapper.findAll('.display-settings-actions button')
    await buttons[1].trigger('click')

    expect(wrapper.emitted('preview')?.[0]).toEqual([saved])
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
