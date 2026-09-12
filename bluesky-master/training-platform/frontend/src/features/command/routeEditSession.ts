// P11：RER 候选航路编辑会话（详细设计 2.2 §7.2）
// 候选在临时对象中完成存在性/连续性/已飞越点校验；确认后只提交规范化 RTE，
// 不创建独立业务指令、不进入控制通道；取消时不产生后端写操作。
export interface RouteCandidate {
  start: string
  middle: string[]
  rejoin: string
}

export interface CandidateValidation {
  ok: boolean
  errors: string[]
  normalizedRoute: string[]
}

export function createCandidate(
  current: string[],
  start: string,
  middle: string[],
  rejoin: string
): RouteCandidate | null {
  const upper = (value: string) => value.trim().toUpperCase()
  const s = upper(start)
  const r = upper(rejoin)
  if (!s || !r) return null
  if (!current.includes(s) || !current.includes(r)) return null
  if (current.indexOf(r) <= current.indexOf(s)) return null
  return { start: s, middle: middle.map(upper).filter(Boolean), rejoin: r }
}

export function validateCandidate(
  candidate: RouteCandidate,
  knownPoints: string[],
  alreadyFlown: string[]
): CandidateValidation {
  const errors: string[] = []
  const route = [candidate.start, ...candidate.middle, candidate.rejoin]
  for (const point of route) {
    if (!knownPoints.includes(point)) errors.push(`未知航路点: ${point}`)
  }
  for (const point of route) {
    if (alreadyFlown.includes(point)) errors.push(`已飞越点不可再用: ${point}`)
  }
  for (let i = 1; i < route.length; i++) {
    if (route[i] === route[i - 1]) errors.push(`连续重复点: ${route[i]}`)
  }
  return { ok: errors.length === 0, errors, normalizedRoute: errors.length === 0 ? route : [] }
}

export function confirmAsRte(candidate: RouteCandidate): string {
  return ['RTE', candidate.start, ...candidate.middle, candidate.rejoin].join(' ')
}
