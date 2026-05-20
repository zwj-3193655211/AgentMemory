import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '../types'

// 浣跨敤 Vite 鐜鍙橀噺锛屾敮鎸佽繍琛屾椂閰嶇疆
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'
const EMBED_BASE = import.meta.env.VITE_EMBED_BASE || 'http://localhost:8100'

axios.defaults.timeout = 30000

/**
 * API 鏈嶅姟绫? * 缁熶竴绠＄悊鎵€鏈?API 璋冪敤
 */
export class ApiService {
  /**
   * 閫氱敤 GET 璇锋眰
   */
  async get<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.get<ApiResponse<T>>(`${baseUrl}${endpoint}`)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '璇锋眰澶辫触'
      ElMessage.error(msg)
      console.error(`GET ${endpoint} 澶辫触:`, msg)
      throw error
    }
  }

  /**
   * 閫氱敤 POST 璇锋眰
   */
  async post<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.post<ApiResponse<T>>(`${baseUrl}${endpoint}`, data)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '璇锋眰澶辫触'
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 閫氱敤 PUT 璇锋眰
   */
  async put<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.put<ApiResponse<T>>(`${baseUrl}${endpoint}`, data)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '璇锋眰澶辫触'
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 閫氱敤 DELETE 璇锋眰
   */
  async delete<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.delete<ApiResponse<T>>(`${baseUrl}${endpoint}`)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '璇锋眰澶辫触'
      ElMessage.error(msg)
      throw error
    }
  }

  // ============== 璁板繂搴?API ==============

  /**
   * 鑾峰彇閿欒绾犳鍒楄〃
   */
  async getErrors() {
    return this.get<any[]>('/errors')
  }

  /**
   * 鍒涘缓閿欒绾犳
   */
  async createError(data: any) {
    return this.post('/errors', data)
  }

  /**
   * 鏇存柊閿欒绾犳
   */
  async updateError(id: string, data: any) {
    return this.put(`/errors/${id}`, data)
  }

  /**
   * 鍒犻櫎閿欒绾犳
   */
  async deleteError(id: string) {
    return this.delete(`/errors/${id}`)
  }

  /**
   * 鑾峰彇鐢ㄦ埛鐢诲儚鍒楄〃
   */
  async getProfiles() {
    return this.get<any[]>('/profiles')
  }

  /**
   * 鍒涘缓鐢ㄦ埛鐢诲儚
   */
  async createProfile(data: any) {
    return this.post('/profiles', data)
  }

  /**
   * 鏇存柊鐢ㄦ埛鐢诲儚
   */
  async updateProfile(id: string, data: any) {
    return this.put(`/profiles/${id}`, data)
  }

  /**
   * 鍒犻櫎鐢ㄦ埛鐢诲儚
   */
  async deleteProfile(id: string) {
    return this.delete(`/profiles/${id}`)
  }

  /**
   * 鑾峰彇瀹炶返缁忛獙鍒楄〃
   */
  async getPractices() {
    return this.get<any[]>('/practices')
  }

  /**
   * 鍒涘缓瀹炶返缁忛獙
   */
  async createPractice(data: any) {
    return this.post('/practices', data)
  }

  /**
   * 鏇存柊瀹炶返缁忛獙
   */
  async updatePractice(id: string, data: any) {
    return this.put(`/practices/${id}`, data)
  }

  /**
   * 鍒犻櫎瀹炶返缁忛獙
   */
  async deletePractice(id: string) {
    return this.delete(`/practices/${id}`)
  }

  /**
   * 鑾峰彇椤圭洰涓婁笅鏂囧垪琛?   */
  async getContexts() {
    return this.get<any[]>('/contexts')
  }

  /**
   * 鍒涘缓椤圭洰涓婁笅鏂?   */
  async createContext(data: any) {
    return this.post('/contexts', data)
  }

  /**
   * 鏇存柊椤圭洰涓婁笅鏂?   */
  async updateContext(id: string, data: any) {
    return this.put(`/contexts/${id}`, data)
  }

  /**
   * 鍒犻櫎椤圭洰涓婁笅鏂?   */
  async deleteContext(id: string) {
    return this.delete(`/contexts/${id}`)
  }

  /**
   * 鑾峰彇鎶€鑳芥矇娣€鍒楄〃
   */
  async getSkills() {
    return this.get<any[]>('/skills')
  }

  /**
   * 鍒涘缓鎶€鑳芥矇娣€
   */
  async createSkill(data: any) {
    return this.post('/skills', data)
  }

  /**
   * 鏇存柊鎶€鑳芥矇娣€
   */
  async updateSkill(id: string, data: any) {
    return this.put(`/skills/${id}`, data)
  }

  /**
   * 鍒犻櫎鎶€鑳芥矇娣€
   */
  async deleteSkill(id: string) {
    return this.delete(`/skills/${id}`)
  }

  // ============== 绯荤粺 API ==============

  /**
   * 鑾峰彇缁熻鏁版嵁
   */
  async getStats() {
    return this.get<any>('/stats')
  }

  /**
   * 鑾峰彇 Agent 鍒楄〃
   */
  async getAgents() {
    return this.get<any[]>('/agents')
  }

  /**
   * 鑾峰彇浼氳瘽鍒楄〃
   */
  async getSessions() {
    return this.get<any[]>('/sessions')
  }

  // ============== Embedding API ==============

  /**
   * Get Embedding models list (proxied via backend)
   */
  async getEmbeddingModels() {
    return this.get<any>('/embedding/models')
  }

  /**
   * Download Embedding model (proxied via backend)
   */
  async downloadEmbeddingModel(modelId: string) {
    return this.post('/embedding/model/download', { model_id: modelId })
  }

  /**
   * Get Embedding model download status (proxied via backend)
   */
  async getEmbeddingModelDownloadStatus() {
    return this.get<any>('/embedding/model/download/status')
  }

  /**
   * Switch Embedding model (proxied via backend)
   */
  async switchEmbeddingModel(modelId: string) {
    return this.post('/embedding/model', { model_id: modelId })
  }

  // ============== 鍒濆鍖?API ==============

  /**
   * 鑾峰彇鍒濆鍖栫姸鎬?   */
  async getSetupStatus() {
    return this.get<any>('/setup/status')
  }

  /**
   * 璁剧疆 Embedding 妯″瀷
   */
  async setEmbeddingModel(modelId: string) {
    return this.post('/setup/model', { modelId })
  }

  /**
   * 瀹屾垚鍒濆鍖?   */
  async completeSetup(modelId: string) {
    return this.post('/setup/complete', { modelId })
  }

  /**
   * 鑾峰彇鍙敤鐨?Agents 鍒楄〃
   */
  async getSetupAgents() {
    return this.get<any[]>('/setup/agents')
  }

  /**
   * 浠?Agents 瀵煎叆浼氳瘽
   */
  async importFromAgents(data: { agentTypes: string[], since: string }) {
    return this.post('/setup/import', data)
  }

  /**
   * 鎵归噺瀵煎叆浼氳瘽
   */
  async importSessions(sessions: any[]) {
    return this.post('/import', { sessions })
  }

  /**
   * 浠庢枃浠跺鍏ヤ細璇?   */
  async importSessionsFromFile(data: any) {
    return this.post('/import/file', data)
  }
}

// 单例实例
export const apiService = new ApiService()

// 导出变量供其他模块使用
export const API_BASE_URL = API_BASE
export const EMBED_BASE_URL = EMBED_BASE
