import { describe, expect, it } from 'vitest'
import { parseProfileContent } from './profileContent'

describe('parseProfileContent', () => {
  it('renders a JSON object item without throwing', () => {
    expect(parseProfileContent('{"content":"用户偏好中文回复"}')).toBe('用户偏好中文回复')
  })

  it('renders JSON array items and ignores malformed values', () => {
    expect(parseProfileContent('[{"content":"第一条"},"第二条",{"key":"ignored"}]')).toBe('第一条\n第二条\n{"key":"ignored"}')
    expect(parseProfileContent('not-json')).toBe('')
  })
})
