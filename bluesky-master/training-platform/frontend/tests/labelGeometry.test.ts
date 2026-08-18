import { describe, expect, it } from 'vitest'
import {
  MIN_DIST,
  maxDist,
  clampDistance,
  labelCenterOffset,
  nearestEdgeMidpoint,
  symbolRotation,
  defaultLayout,
} from '../src/labelGeometry'

describe('labelGeometry', () => {
  describe('maxDist', () => {
    it('is 4x label short side', () => {
      expect(maxDist(66)).toBe(264)
      expect(maxDist(50)).toBe(200)
    })
  })

  describe('clampDistance', () => {
    it('clamps below to MIN_DIST', () => {
      expect(clampDistance(0, 66)).toBe(MIN_DIST)
      expect(clampDistance(-5, 66)).toBe(MIN_DIST)
    })
    it('clamps above to 4x short side', () => {
      expect(clampDistance(999, 66)).toBe(264)
    })
    it('passes through in range', () => {
      expect(clampDistance(120, 66)).toBe(120)
      expect(clampDistance(MIN_DIST, 66)).toBe(MIN_DIST)
      expect(clampDistance(264, 66)).toBe(264)
    })
  })

  describe('labelCenterOffset', () => {
    it('returns exact offsets for 45deg up-right (screen -45)', () => {
      const { dx, dy } = labelCenterOffset(-45, 120)
      expect(dx).toBeCloseTo(84.85, 2)
      expect(dy).toBeCloseTo(-84.85, 2)
    })
    it('right for 0deg', () => {
      const { dx, dy } = labelCenterOffset(0, 100)
      expect(dx).toBeCloseTo(100, 6)
      expect(dy).toBeCloseTo(0, 6)
    })
    it('down for 90deg (screen y-down)', () => {
      const { dx, dy } = labelCenterOffset(90, 50)
      expect(dx).toBeCloseTo(0, 6)
      expect(dy).toBeCloseTo(50, 6)
    })
  })

  describe('nearestEdgeMidpoint', () => {
    const size = { w: 168, h: 66 }
    it('label up-right -> left edge midpoint', () => {
      const m = nearestEdgeMidpoint({ dx: 100, dy: -80 }, size)
      expect(m.x).toBe(100 - size.w / 2)
      expect(m.y).toBe(-80)
    })
    it('label left-down -> right edge midpoint', () => {
      const m = nearestEdgeMidpoint({ dx: -100, dy: 80 }, size)
      expect(m.x).toBe(-100 + size.w / 2)
      expect(m.y).toBe(80)
    })
    it('label below -> top edge midpoint', () => {
      const m = nearestEdgeMidpoint({ dx: 0, dy: 90 }, size)
      expect(m.x).toBe(0)
      expect(m.y).toBe(90 - size.h / 2)
    })
    it('label above -> bottom edge midpoint', () => {
      const m = nearestEdgeMidpoint({ dx: 0, dy: -90 }, size)
      expect(m.x).toBe(0)
      expect(m.y).toBe(-90 + size.h / 2)
    })
  })

  describe('symbolRotation', () => {
    it('converts degrees to radians', () => {
      expect(symbolRotation(90)).toBeCloseTo(Math.PI / 2, 6)
      expect(symbolRotation(360)).toBeCloseTo(Math.PI * 2, 6)
    })
  })

  describe('defaultLayout', () => {
    it('is 45deg up-right at 120', () => {
      expect(defaultLayout()).toEqual({ angle: -45, dist: 120 })
    })
  })
})
