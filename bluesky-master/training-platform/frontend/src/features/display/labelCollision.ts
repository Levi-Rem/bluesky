// P18：标牌自动避让（详细设计 2.2 §8.6.4：人工拖动后自动避让不得覆盖手工位置）

export interface LabelAnchor {
  aircraftId: string
  angleDeg: number
  distancePx: number
  pinned?: boolean
}

export interface LabelBox {
  centerX: number
  centerY: number
  halfWidth: number
  halfHeight: number
}

function boxOf(anchor: LabelAnchor, symbolX: number, symbolY: number): LabelBox {
  const rad = (anchor.angleDeg * Math.PI) / 180
  return {
    centerX: symbolX + Math.cos(rad) * anchor.distancePx,
    centerY: symbolY - Math.sin(rad) * anchor.distancePx,
    halfWidth: 40,
    halfHeight: 16
  }
}

function overlaps(a: LabelBox, b: LabelBox): boolean {
  return (
    Math.abs(a.centerX - b.centerX) < a.halfWidth + b.halfWidth &&
    Math.abs(a.centerY - b.centerY) < a.halfHeight + b.halfHeight
  )
}

/** 候选角度（8 方位）避让：固定标牌（pinned）不动，其余避让他人 */
export const CANDIDATE_ANGLES = [35, 55, 90, 125, 145, 215, 270, 325]

export function resolveCollisions(
  anchors: LabelAnchor[],
  positions: Record<string, { x: number; y: number }>
): LabelAnchor[] {
  const resolved: LabelAnchor[] = anchors.map((a) => ({ ...a }))
  // 先处理固定标牌：占据初始角度
  const occupied: LabelBox[] = []
  for (const anchor of resolved) {
    if (anchor.pinned) {
      const p = positions[anchor.aircraftId] ?? { x: 0, y: 0 }
      occupied.push(boxOf(anchor, p.x, p.y))
    }
  }
  for (const anchor of resolved) {
    if (anchor.pinned) continue
    const p = positions[anchor.aircraftId] ?? { x: 0, y: 0 }
    let chosen = anchor
    let chosenBox = boxOf(anchor, p.x, p.y)
    if (occupied.some((box) => overlaps(chosenBox, box))) {
      const candidate = CANDIDATE_ANGLES.find((angle) => {
        const trial = { ...anchor, angleDeg: angle }
        const trialBox = boxOf(trial, p.x, p.y)
        return !occupied.some((box) => overlaps(trialBox, box))
      })
      if (candidate !== undefined) {
        chosen = { ...anchor, angleDeg: candidate }
        chosenBox = boxOf(chosen, p.x, p.y)
      }
    }
    occupied.push(chosenBox)
    const index = resolved.findIndex((r) => r.aircraftId === anchor.aircraftId)
    resolved[index] = chosen
  }
  return resolved
}

/** 删除布局后恢复自动布局 = 移除该航空器锚点 */
export function withoutAnchor(anchors: LabelAnchor[], aircraftId: string): LabelAnchor[] {
  return anchors.filter((a) => a.aircraftId !== aircraftId)
}

/** 保存的手工位置是否被自动避让尊重 */
export function respectPinnedLayout(anchors: LabelAnchor[]): LabelAnchor[] {
  return anchors.filter((a) => a.pinned)
}
