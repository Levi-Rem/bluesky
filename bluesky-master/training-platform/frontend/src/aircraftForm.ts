export function validateAircraftForm(
  callsign: string, appearanceOffset: string, transponderCode = ''
): string {
  if (!/^[A-Za-z0-9]{2,7}$/.test(callsign.trim())) {
    return '呼号必须是 2 至 7 位英文字母或数字'
  }
  if (!/^\d{4}$/.test(appearanceOffset)) {
    return '出现时间必须是四位数字，例如 0010'
  }
  if (transponderCode && (!/^[0-7]{4}$/.test(transponderCode) || transponderCode === '0000')) {
    return '二次代码必须是四位八进制数且不能为 0000'
  }
  return ''
}
