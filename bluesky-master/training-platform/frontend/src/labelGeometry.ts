/**
 * 航迹标牌几何 —— 纯函数模块（无 OpenLayers 依赖）
 *
 * 约定：角度为屏幕坐标角度（deg，0=右，90=下，-45=右上 45°），
 * 与航向（0=北、顺时针）区分；标牌中心相对航迹符号中心的偏移量单位为像素。
 */

/** 标牌中心与符号中心的最小距离（px） */
export const MIN_DIST = 10

/** 最大距离 = 标牌短边 × 4 */
export function maxDist(labelShortSide: number): number {
  return labelShortSide * 4
}

/** 中心距钳制到 [MIN_DIST, 标牌短边×4] */
export function clampDistance(d: number, labelShortSide: number): number {
  return Math.min(Math.max(d, MIN_DIST), maxDist(labelShortSide))
}

/** 标牌中心相对符号中心的像素偏移（屏幕系 y 向下） */
export function labelCenterOffset(angleDeg: number, dist: number): { dx: number; dy: number } {
  const rad = (angleDeg * Math.PI) / 180
  return { dx: dist * Math.cos(rad), dy: dist * Math.sin(rad) }
}

/**
 * 标杆线端点：标牌四条边中点中，离符号中心（坐标原点）最近的一个。
 * center 为标牌中心偏移；size 为标牌宽高。
 */
export function nearestEdgeMidpoint(
  center: { dx: number; dy: number },
  size: { w: number; h: number }
): { x: number; y: number } {
  const { dx, dy } = center
  const candidates = [
    { x: dx, y: dy - size.h / 2 },
    { x: dx, y: dy + size.h / 2 },
    { x: dx - size.w / 2, y: dy },
    { x: dx + size.w / 2, y: dy },
  ]
  let best = candidates[0]
  let bestDist = Infinity
  for (const c of candidates) {
    const d = c.x * c.x + c.y * c.y
    if (d < bestDist) {
      bestDist = d
      best = c
    }
  }
  return best
}

/** 航向角（deg）→ ol Icon 旋转（rad）；符号默认朝上 */
export function symbolRotation(headingDegrees: number): number {
  return (headingDegrees * Math.PI) / 180
}

/** 新航空器标牌默认布局：右上 45°，中心距 120px */
export function defaultLayout(): { angle: number; dist: number } {
  return { angle: -45, dist: 120 }
}
