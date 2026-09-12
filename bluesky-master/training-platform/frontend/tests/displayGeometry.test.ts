// P18：显示域纯函数 + 标牌自动避让（详细设计 2.2 §8.6/§8.7）
import { describe, expect, it } from 'vitest'
import { distanceNm, bearingTrue, bearingMagnetic, wrap360, formatAltitude, formatSpeed, formatDistance, pruneTrackHistory, projectVelocityVector, buildRangeRings, validateRingSpacing } from '../src/features/display/displayGeometry'
import { resolveCollisions, withoutAnchor, respectPinnedLayout, type LabelAnchor } from '../src/features/display/labelCollision'

describe('displayGeometry 测距与方位', () => {
  it('正北方向：方位 0、距离按纬差计算', () => {
    const from = { lat: 23.0, lon: 113.0 }
    const to = { lat: 23.5, lon: 113.0 }
    expect(bearingTrue(from, to)).toBeCloseTo(0, 5)
    expect(distanceNm(from, to)).toBeCloseTo(30, 0)
  })

  it('正东方向：真方位 90，距离含纬度收敛', () => {
    const from = { lat: 23.0, lon: 113.0 }
    const to = { lat: 23.0, lon: 113.5 }
    const cos23 = Math.cos((23 * Math.PI) / 180)
    expect(bearingTrue(from, to)).toBeCloseTo(90, 5)
    expect(distanceNm(from, to)).toBeCloseTo(30 * cos23, 0)
  })

  it('磁方位 = 真方位 − 磁差并回绕 0–360', () => {
    expect(bearingMagnetic(10, 3)).toBe(7)
    expect(bearingMagnetic(2, 5)).toBe(357)
    expect(bearingMagnetic(350, 10)).toBe(340)
    expect(wrap360(365)).toBe(5)
    expect(wrap360(-5)).toBe(355)
    expect(wrap360(0)).toBe(0)
  })
})

describe('三单位显示只改变格式不改变规范值（§4.3）', () => {
  it('高度：IMP/MIX 用 ft、MET 用 m', () => {
    expect(formatAltitude(30000, 'IMP')).toBe('30000ft')
    expect(formatAltitude(30000, 'MIX')).toBe('30000ft')
    expect(formatAltitude(30000, 'MET')).toBe('9144m')
  })

  it('速度：MET 用 km/h、IMP/MIX 用 kt', () => {
    expect(formatSpeed(280, 'MET')).toBe('519km/h')
    expect(formatSpeed(280, 'IMP')).toBe('280kt')
    expect(formatSpeed(280, 'MIX')).toBe('280kt')
  })

  it('距离：MET 用 km、IMP/MIX 用 NM', () => {
    expect(formatDistance(10, 'MET')).toBe('18.5km')
    expect(formatDistance(10, 'IMP')).toBe('10.0NM')
  })
})

describe('历史点与速度矢量', () => {
  it('只保留最近 N 个历史点且按时间升序', () => {
    const history = Array.from({ length: 10 }, (_, i) => ({
      lat: 23 + i * 0.01,
      lon: 113,
      atSeconds: i
    }))
    const pruned = pruneTrackHistory(history, 4)
    expect(pruned.length).toBe(4)
    expect(pruned[0].atSeconds).toBe(6)
    expect(pruned[3].atSeconds).toBe(9)
  })

  it('速度矢量按真航向投影，60 秒 1 分钟位置', () => {
    const end = projectVelocityVector({ lat: 23, lon: 113 }, 0, 600, 60)
    expect(end.lat).toBeCloseTo(23 + 10 / 60, 6)
    expect(end.lon).toBeCloseTo(113, 9)
    const east = projectVelocityVector({ lat: 23, lon: 113 }, 90, 600, 60)
    const cos23 = Math.cos((23 * Math.PI) / 180)
    expect(east.lat).toBeCloseTo(23, 9)
    expect(east.lon).toBeCloseTo(113 + 10 / 60 / cos23, 4)
  })
})

describe('距离环', () => {
  it('按间距构建环并生成标签', () => {
    const rings = buildRangeRings({ lat: 23, lon: 113 }, 10, 3)
    expect(rings.map((r) => r.label)).toEqual(['10NM', '20NM', '30NM'])
  })
  it('非法间距拒绝', () => {
    expect(validateRingSpacing(0)).toBe(false)
    expect(validateRingSpacing(101)).toBe(false)
    expect(validateRingSpacing(10)).toBe(true)
    expect(() => buildRangeRings({ lat: 23, lon: 113 }, 0, 3)).toThrow()
  })
})

describe('标牌自动避让（§8.6.4）', () => {
  const positions = {
    AC1: { x: 100, y: 100 },
    AC2: { x: 100, y: 100 }
  }

  it('重叠标牌被移到不冲突角度，固定标牌不动', () => {
    const anchors: LabelAnchor[] = [
      { aircraftId: 'AC1', angleDeg: 35, distancePx: 100, pinned: true },
      { aircraftId: 'AC2', angleDeg: 35, distancePx: 100 }
    ]
    const resolved = resolveCollisions(anchors, positions)
    const pinned = resolved.find((a) => a.aircraftId === 'AC1')!
    expect(pinned.angleDeg).toBe(35)
    const moved = resolved.find((a) => a.aircraftId === 'AC2')!
    expect(moved.angleDeg).not.toBe(35)
  })

  it('无重叠时不移动', () => {
    const anchors: LabelAnchor[] = [
      { aircraftId: 'AC1', angleDeg: 35, distancePx: 100 },
      { aircraftId: 'AC2', angleDeg: 215, distancePx: 100 }
    ]
    const resolved = resolveCollisions(anchors, positions)
    expect(resolved.every((a, i) => a.angleDeg === anchors[i].angleDeg)).toBe(true)
  })

  it('删除布局恢复自动布局：移除锚点；固定集合只含 pinned', () => {
    const anchors: LabelAnchor[] = [
      { aircraftId: 'AC1', angleDeg: 35, distancePx: 20, pinned: true },
      { aircraftId: 'AC2', angleDeg: 90, distancePx: 20 }
    ]
    expect(withoutAnchor(anchors, 'AC2').length).toBe(1)
    expect(respectPinnedLayout(anchors).map((a) => a.aircraftId)).toEqual(['AC1'])
  })
})
