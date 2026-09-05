import { describe, expect, it } from 'vitest'
import { isMenuKey, normalizeMenu } from './navigation'

describe('navigation menu state', () => {
  it('recognizes every primary menu key', () => {
    expect(['dashboard', 'experiences', 'profiles', 'projects', 'skills', 'agents', 'compression', 'settings'].every(isMenuKey)).toBe(true)
  })

  it('falls back to dashboard for an unknown persisted menu', () => {
    expect(normalizeMenu('profiles')).toBe('profiles')
    expect(normalizeMenu('unknown-menu')).toBe('dashboard')
    expect(normalizeMenu(null)).toBe('dashboard')
  })
})
