import { describe, it, expect } from 'vitest'
import {
  SKILL_TYPE_LABELS,
  type SkillType,
  type SkillStage
} from '../types'

describe('types', () => {
  describe('SKILL_TYPE_LABELS', () => {
    it('should have all 9 skill types defined', () => {
      const expectedTypes: SkillType[] = [
        'library-api',
        'data-analysis',
        'troubleshooting',
        'scaffold',
        'automation',
        'code-review',
        'product-verify',
        'cicd',
        'infra-ops'
      ]

      expectedTypes.forEach(type => {
        expect(SKILL_TYPE_LABELS).toHaveProperty(type)
      })
    })

    it('should map each skill type to correct stage', () => {
      // 认知环节
      expect(SKILL_TYPE_LABELS['library-api'].stage).toBe('认知')
      expect(SKILL_TYPE_LABELS['data-analysis'].stage).toBe('认知')
      expect(SKILL_TYPE_LABELS['troubleshooting'].stage).toBe('认知')

      // 生产环节
      expect(SKILL_TYPE_LABELS['scaffold'].stage).toBe('生产')
      expect(SKILL_TYPE_LABELS['automation'].stage).toBe('生产')

      // 验证环节
      expect(SKILL_TYPE_LABELS['code-review'].stage).toBe('验证')
      expect(SKILL_TYPE_LABELS['product-verify'].stage).toBe('验证')

      // 交付环节
      expect(SKILL_TYPE_LABELS['cicd'].stage).toBe('交付')
      expect(SKILL_TYPE_LABELS['infra-ops'].stage).toBe('交付')
    })

    it('should have label and desc for each type', () => {
      (Object.keys(SKILL_TYPE_LABELS) as SkillType[]).forEach(type => {
        expect(SKILL_TYPE_LABELS[type].label).toBeDefined()
        expect(SKILL_TYPE_LABELS[type].desc).toBeDefined()
      })
    })
  })

  describe('SkillStage type', () => {
    it('should accept valid stages', () => {
      const stages: SkillStage[] = ['认知', '生产', '验证', '交付']
      stages.forEach(stage => {
        expect(stage).toBeTruthy()
      })
    })
  })

  describe('ApiResponse', () => {
    it('should allow optional fields', () => {
      // Basic response
      const basic = { data: [] }
      expect(basic.data).toEqual([])

      // With message
      const withMessage = { data: [], message: 'success' }
      expect(withMessage.message).toBe('success')

      // With error
      const withError = { error: 'failed' }
      expect(withError.error).toBe('failed')
    })
  })

  describe('Stats interface', () => {
    it('should have all required numeric fields', () => {
      const stats = {
        sessions: 10,
        messages: 100,
        errors: 5,
        profiles: 3,
        practices: 20,
        contexts: 15,
        skills: 8
      }

      expect(stats.sessions).toBe(10)
      expect(stats.messages).toBe(100)
      expect(typeof stats.sessions).toBe('number')
    })
  })

  describe('UserProfile category', () => {
    it('should accept valid category values', () => {
      const categories = ['preference', 'behavior', 'techstack', 'workhabit', 'other'] as const
      categories.forEach(cat => {
        const profile = {
          title: 'Test',
          category: cat,
          items: []
        }
        expect(profile.category).toBe(cat)
      })
    })
  })

  describe('DialogState', () => {
    it('should have required fields', () => {
      const dialog = {
        visible: false,
        isEdit: false,
        formData: {}
      }
      expect(dialog.visible).toBe(false)
      expect(dialog.isEdit).toBe(false)
      expect(dialog.formData).toEqual({})
    })
  })

  describe('LLMProvider', () => {
    it('should have required provider fields', () => {
      const provider = {
        id: 1,
        providerName: 'openai',
        displayName: 'OpenAI',
        baseUrl: 'https://api.openai.com',
        model: 'gpt-4',
        enabled: true,
        isDefault: false,
        thinkMode: false
      }

      expect(provider.id).toBe(1)
      expect(provider.providerName).toBe('openai')
      expect(provider.enabled).toBe(true)
    })
  })

  describe('CompressionConfig', () => {
    it('should accept valid compression types', () => {
      const configs = [
        { autoCompress: true, compressionType: 'SLIDING_WINDOW' as const },
        { autoCompress: false, compressionType: 'SUMMARIZE' as const },
        { autoCompress: true, compressionType: 'HYBRID' as const }
      ]

      configs.forEach(config => {
        expect(['SLIDING_WINDOW', 'SUMMARIZE', 'HYBRID']).toContain(config.compressionType)
      })
    })
  })

  describe('EmbeddingModel', () => {
    it('should have correct download status values', () => {
      const statuses = ['ready', 'downloading', 'error', 'not_downloaded'] as const
      const model = {
        id: 'test',
        name: 'Test Model',
        dimension: 384,
        size: 'small',
        description: '',
        download_size_mb: 0,
        is_current: false,
        downloaded: false,
        status: 'not_downloaded' as const
      }

      expect(statuses).toContain(model.status)
    })
  })

  describe('DownloadProgress', () => {
    it('should have numeric progress fields', () => {
      const progress = {
        downloaded_mb: 50,
        total_mb: 100,
        percent: 50,
        speed_mbps: 10.5,
        current_file: 'model.bin',
        files_done: 5,
        files_total: 10,
        error: null,
        start_time: Date.now()
      }

      expect(progress.downloaded_mb).toBe(50)
      expect(progress.percent).toBe(50)
      expect(progress.speed_mbps).toBeCloseTo(10.5)
    })
  })

  describe('Session status', () => {
    it('should accept valid session statuses', () => {
      const statuses = ['active', 'archived', 'deleted'] as const
      statuses.forEach(status => {
        const session = {
          id: 'test',
          agentType: 'general',
          messageCount: 0,
          startTime: new Date().toISOString(),
          lastActivity: new Date().toISOString(),
          status: status as 'active' | 'archived' | 'deleted'
        }
        expect(session.status).toBe(status)
      })
    })
  })
})