export const parseProfileContent = (itemsValue: unknown): string => {
  let value: unknown = itemsValue

  if (typeof value === 'string' && value.trim()) {
    try {
      value = JSON.parse(value)
    } catch {
      return ''
    }
  }

  const items = Array.isArray(value) ? value : value == null ? [] : [value]

  return items
    .map((item: unknown) => {
      if (typeof item === 'string') return item
      if (item && typeof item === 'object' && 'content' in item) {
        return String(item.content ?? '')
      }
      try {
        return JSON.stringify(item)
      } catch {
        return ''
      }
    })
    .filter((text: string) => text.trim())
    .join('\n')
}
