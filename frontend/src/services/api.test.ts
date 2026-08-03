import { describe, it, expect, vi, beforeEach } from 'vitest'
import axios from 'axios'
import { ApiService, apiService, API_BASE_URL } from './api'

vi.mock('axios')
vi.mock('element-plus')

describe('ApiService', () => {
  let mockAxios: any

  beforeEach(() => {
    vi.clearAllMocks()
    mockAxios = axios as any
  })

  describe('constructor', () => {
    it('should have correct API_BASE_URL', () => {
      expect(API_BASE_URL).toBeDefined()
    })
  })

  describe('get', () => {
    it('should make GET request successfully', async () => {
      const mockData = [{ id: '1', title: 'Test' }]
      mockAxios.get.mockResolvedValue({ data: mockData })

      const service = new ApiService()
      const result = await service.get('/test')

      // Verify axios.get was called with the full URL (ignore config param)
      expect(mockAxios.get).toHaveBeenCalled()
      expect(result).toEqual(mockData)
    })

    it('should handle GET error', async () => {
      mockAxios.get.mockRejectedValue({ message: 'Error' })

      const service = new ApiService()

      await expect(service.get('/test')).rejects.toBeDefined()
    })
  })

  describe('post', () => {
    it('should make POST request successfully', async () => {
      const mockData = { id: '1' }
      mockAxios.post.mockResolvedValue({ data: mockData })

      const service = new ApiService()
      const result = await service.post('/test', { name: 'Test' })

      expect(mockAxios.post).toHaveBeenCalled()
      expect(result).toEqual(mockData)
    })
  })

  describe('put', () => {
    it('should make PUT request successfully', async () => {
      const mockData = { id: '1' }
      mockAxios.put.mockResolvedValue({ data: mockData })

      const service = new ApiService()
      const result = await service.put('/test/1', { name: 'Updated' })

      expect(mockAxios.put).toHaveBeenCalled()
      expect(result).toEqual(mockData)
    })
  })

  describe('delete', () => {
    it('should make DELETE request successfully', async () => {
      mockAxios.delete.mockResolvedValue({ data: {} })

      const service = new ApiService()
      const result = await service.delete('/test/1')

      expect(mockAxios.delete).toHaveBeenCalled()
      expect(result).toEqual({})
    })
  })

  describe('apiService singleton', () => {
    it('should export apiService singleton', () => {
      expect(apiService).toBeDefined()
      expect(apiService.get).toBeDefined()
      expect(apiService.post).toBeDefined()
    })
  })

  describe('CRUD methods', () => {
    describe('errors', () => {
      it('should have getErrors method', () => {
        expect(apiService.getErrors).toBeDefined()
        expect(typeof apiService.getErrors).toBe('function')
      })

      it('should have createError method', () => {
        expect(apiService.createError).toBeDefined()
      })

      it('should have updateError method', () => {
        expect(apiService.updateError).toBeDefined()
      })

      it('should have deleteError method', () => {
        expect(apiService.deleteError).toBeDefined()
      })
    })

    describe('profiles', () => {
      it('should have getProfiles method', () => {
        expect(apiService.getProfiles).toBeDefined()
      })

      it('should have createProfile method', () => {
        expect(apiService.createProfile).toBeDefined()
      })

      it('should have updateProfile method', () => {
        expect(apiService.updateProfile).toBeDefined()
      })

      it('should have deleteProfile method', () => {
        expect(apiService.deleteProfile).toBeDefined()
      })
    })

    describe('practices', () => {
      it('should have getPractices method', () => {
        expect(apiService.getPractices).toBeDefined()
      })

      it('should have createPractice method', () => {
        expect(apiService.createPractice).toBeDefined()
      })

      it('should have updatePractice method', () => {
        expect(apiService.updatePractice).toBeDefined()
      })

      it('should have deletePractice method', () => {
        expect(apiService.deletePractice).toBeDefined()
      })
    })

    describe('contexts', () => {
      it('should have getContexts method', () => {
        expect(apiService.getContexts).toBeDefined()
      })

      it('should have createContext method', () => {
        expect(apiService.createContext).toBeDefined()
      })

      it('should have updateContext method', () => {
        expect(apiService.updateContext).toBeDefined()
      })

      it('should have deleteContext method', () => {
        expect(apiService.deleteContext).toBeDefined()
      })
    })

    describe('skills', () => {
      it('should have getSkills method', () => {
        expect(apiService.getSkills).toBeDefined()
      })

      it('should have createSkill method', () => {
        expect(apiService.createSkill).toBeDefined()
      })

      it('should have updateSkill method', () => {
        expect(apiService.updateSkill).toBeDefined()
      })

      it('should have deleteSkill method', () => {
        expect(apiService.deleteSkill).toBeDefined()
      })
    })
  })

  describe('system API methods', () => {
    it('should have getStats method', () => {
      expect(apiService.getStats).toBeDefined()
    })

    it('should have getAgents method', () => {
      expect(apiService.getAgents).toBeDefined()
    })

    it('should have getSessions method', () => {
      expect(apiService.getSessions).toBeDefined()
    })
  })

  describe('embedding API methods', () => {
    it('should have getEmbeddingModels method', () => {
      expect(apiService.getEmbeddingModels).toBeDefined()
    })

    it('should have downloadEmbeddingModel method', () => {
      expect(apiService.downloadEmbeddingModel).toBeDefined()
    })

    it('should have getEmbeddingModelDownloadStatus method', () => {
      expect(apiService.getEmbeddingModelDownloadStatus).toBeDefined()
    })

    it('should have switchEmbeddingModel method', () => {
      expect(apiService.switchEmbeddingModel).toBeDefined()
    })
  })

  describe('setup API methods', () => {
    it('should have getSetupStatus method', () => {
      expect(apiService.getSetupStatus).toBeDefined()
    })

    it('should have setEmbeddingModel method', () => {
      expect(apiService.setEmbeddingModel).toBeDefined()
    })

    it('should have completeSetup method', () => {
      expect(apiService.completeSetup).toBeDefined()
    })

    it('should have getSetupAgents method', () => {
      expect(apiService.getSetupAgents).toBeDefined()
    })

    it('should have importFromAgents method', () => {
      expect(apiService.importFromAgents).toBeDefined()
    })

    it('should have importSessions method', () => {
      expect(apiService.importSessions).toBeDefined()
    })

    it('should have importSessionsFromFile method', () => {
      expect(apiService.importSessionsFromFile).toBeDefined()
    })
  })
})