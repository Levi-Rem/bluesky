import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import DisplaySettingsDialog from '../src/DisplaySettingsDialog.vue'
import type { DisplaySettings } from '../src/types'
import { DEFAULT_DISPLAY_SETTINGS } from '../src/displaySettings'

const saved: DisplaySettings = {
  trackColor: '#111111', selectedTrackColor: '#222222', mapWaypointColor: '#333333',
  mapAirwayColor: '#444444', mapSectorColor: '#555555', mapSectorFillColor: '#666666',
  mapWeatherColor: '#777777', mapWeatherFillColor: '#888888'
}
const defaults: DisplaySettings = { ...DEFAULT_DISPLAY_SETTINGS }

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

  it('does not close from the backdrop while a save is in progress', async () => {
    const wrapper = mount(DisplaySettingsDialog, {
      props: { open: true, saved, defaults, busy: true, error: '' }
    })

    await wrapper.get('.display-settings-mask').trigger('pointerdown')

    expect(wrapper.emitted('close')).toBeUndefined()
    expect(wrapper.emitted('preview')).toBeUndefined()
  })

  it('moves focus into the dialog and traps tab navigation', async () => {
    const wrapper = mount(DisplaySettingsDialog, {
      attachTo: document.body,
      props: { open: false, saved, defaults, busy: false, error: '' }
    })
    await wrapper.setProps({ open: true })
    await nextTick()

    const focusable = wrapper.findAll('button:not([disabled]), input:not([disabled])')
    expect(document.activeElement).toBe(focusable[0].element)

    const last = focusable[focusable.length - 1].element as HTMLElement
    last.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(focusable[0].element)
    wrapper.unmount()
  })
})
