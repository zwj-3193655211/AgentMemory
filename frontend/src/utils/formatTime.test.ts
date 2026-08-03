import { describe, it, expect } from 'vitest'
import { formatTime } from '../utils'

describe('formatTime', () => {
  describe('valid timestamps', () => {
    it('should format ISO string timestamp', () => {
      const result = formatTime('2024-01-15T10:30:00.000Z')
      expect(result).not.toBe('-')
      expect(result).toContain('2024')
    })

    it('should format date string with time', () => {
      const result = formatTime('2024/05/20 14:30:00')
      expect(result).not.toBe('-')
    })

    it('should handle Chinese locale format', () => {
      const result = formatTime('2024年01月15日 10:30:00')
      expect(result).not.toBe('-')
    })
  })

  describe('edge cases', () => {
    it('should return "-" for null', () => {
      expect(formatTime(null)).toBe('-')
    })

    it('should return "-" for undefined', () => {
      expect(formatTime(undefined)).toBe('-')
    })

    it('should return "-" for empty string', () => {
      expect(formatTime('')).toBe('-')
    })
  })

  describe('invalid input', () => {
    it('should return input for invalid timestamp', () => {
      const invalid = 'not-a-valid-date'
      const result = formatTime(invalid)
      expect(result).toBe(invalid)
    })

    it('should return input for malformed date', () => {
      const malformed = '2024-99-99T99:99:99'
      const result = formatTime(malformed)
      expect(result).toBe(malformed)
    })
  })
})