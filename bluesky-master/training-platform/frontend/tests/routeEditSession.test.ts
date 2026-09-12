// P11：RER 候选编辑——不落业务计划、不产生指令；确认只提交规范化 RTE
import { describe, expect, it } from 'vitest'
import { createCandidate, validateCandidate, confirmAsRte } from '../src/features/command/routeEditSession'

const CURRENT = ['ZGGG', 'LMN', 'P47', 'ZBAA']
const KNOWN = ['ZGGG', 'LMN', 'P47', 'CSN01', 'ZBAA']
const FLOWN = ['ZGGG']

describe('routeEditSession', () => {
  it('创建候选要求起止点在未飞航路且顺序正确', () => {
    const candidate = createCandidate(CURRENT, 'lmn', ['csn01'], 'p47')
    expect(candidate).toEqual({ start: 'LMN', middle: ['CSN01'], rejoin: 'P47' })

    expect(createCandidate(CURRENT, 'XXX', [], 'P47')).toBeNull()
    expect(createCandidate(CURRENT, 'P47', [], 'LMN')).toBeNull()
  })

  it('校验候选：未知点/已飞越点全部拒绝且给出完整错误清单', () => {
    const candidate = createCandidate(CURRENT, 'LMN', ['NOPE', 'ZGGG'], 'P47')!
    const validation = validateCandidate(candidate, KNOWN, FLOWN)
    expect(validation.ok).toBe(false)
    expect(validation.errors).toContain('未知航路点: NOPE')
    expect(validation.errors).toContain('已飞越点不可再用: ZGGG')
    expect(validation.normalizedRoute).toEqual([])
  })

  it('确认后生成规范化 RTE 文本；取消不产生任何后端调用（纯前端对象）', () => {
    const candidate = createCandidate(CURRENT, 'LMN', ['CSN01'], 'P47')!
    const validation = validateCandidate(candidate, KNOWN, FLOWN)
    expect(validation.ok).toBe(true)
    expect(confirmAsRte(candidate)).toBe('RTE LMN CSN01 P47')
  })
})
