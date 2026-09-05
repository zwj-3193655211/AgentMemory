export const MENU_KEYS = [
  'dashboard',
  'experiences',
  'profiles',
  'projects',
  'skills',
  'agents',
  'compression',
  'settings',
  'search'
] as const

export type MenuKey = typeof MENU_KEYS[number]

export const isMenuKey = (value: unknown): value is MenuKey => {
  return typeof value === 'string' && (MENU_KEYS as readonly string[]).includes(value)
}

export const normalizeMenu = (value: unknown): MenuKey => {
  return isMenuKey(value) ? value : 'dashboard'
}
