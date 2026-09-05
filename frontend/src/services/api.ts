import axios from 'axios'
import { ElMessage } from 'element-plus'

// 使用 Vite 环境变量，支持运行时配置
const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const EMBED_BASE = import.meta.env.VITE_EMBED_BASE || '/api'

axios.defaults.timeout = 30000

/**
 * API 服务类
 * 统一管理所有 API 调用
 */
export class ApiService {
  /**
   * 格式化错误信息，方便排查：
   * - 服务器有响应(4xx/5xx)：显示 HTTP 状态码 + 后端错误消息
   * - 无响应：区分超时 / 连接失败 / CORS 拦截，并附上完整 URL
   */
  private formatError(method: string, endpoint: string, error: any): string {
    const url = `${API_BASE}${endpoint}`
    if (error.response) {
      // 服务器返回了响应（4xx / 5xx / 代理 502）
      const status = error.response.status
      const data = error.response.data
      let detail = ''
      if (data && typeof data === 'object') {
        detail = data.message || data.error || ''
      } else if (typeof data === 'string' && data.trim()) {
        detail = data.slice(0, 150)
      }
      return `[${method} ${endpoint}] HTTP ${status}${detail ? `: ${detail}` : ''}`
    }
    if (error.code === 'ECONNABORTED') {
      return `[${method} ${endpoint}] 请求超时(30s): ${url}`
    }
    // 无任何响应：连接被拒 / CORS 拦截 / 代理不可达
    return `[${method} ${endpoint}] 网络错误: ${error.message || 'Network Error'} | URL=${url} | 排查: 后端8082是否运行 / Vite代理 / CORS`
  }

  /**
   * 通用 GET 请求
   */
  async get<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.get<T>(`${baseUrl}${endpoint}`)
      return res.data
    } catch (error: any) {
      const msg = this.formatError('GET', endpoint, error)
      error.displayMessage = msg
      console.error(`[api] GET ${baseUrl}${endpoint} 失败:`, error)
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 通用 POST 请求
   */
  async post<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.post<T>(`${baseUrl}${endpoint}`, data)
      return res.data
    } catch (error: any) {
      const msg = this.formatError('POST', endpoint, error)
      error.displayMessage = msg
      console.error(`[api] POST ${baseUrl}${endpoint} 失败:`, error)
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 通用 PUT 请求
   */
  async put<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.put<T>(`${baseUrl}${endpoint}`, data)
      return res.data
    } catch (error: any) {
      const msg = this.formatError('PUT', endpoint, error)
      error.displayMessage = msg
      console.error(`[api] PUT ${baseUrl}${endpoint} 失败:`, error)
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 通用 DELETE 请求
   */
  async delete<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.delete<T>(`${baseUrl}${endpoint}`)
      return res.data
    } catch (error: any) {
      const msg = error.response?.data?.message || error.response?.data?.error || error.message || '请求失败'
      ElMessage.error(msg)
      throw error
    }
  }

  // ============== 记忆库 API ==============

  // ============== 实践经验（合并端点） ==============

  /**
   * 获取实践经验列表（支持类型过滤）
   */
  async getExperiences(type?: string) {
    const q = type ? `?type=${type}` : ''
    return this.get<any[]>(`/experiences${q}`)
  }

  /**
   * 创建实践经验
   */
  async createExperience(data: any) {
    return this.post('/experiences', data)
  }

  /**
   * 更新实践经验
   */
  async updateExperience(id: string, data: any) {
    return this.put(`/experiences/${id}`, data)
  }

  /**
   * 删除实践经验
   */
  async deleteExperience(id: string) {
    return this.delete(`/experiences/${id}`)
  }

  // ============== 记忆同步 ==============

  /**
   * 手动触发全量记忆同步（画像 + SQLite 会话导入）
   */
  async syncAll() {
    return this.post('/sync', {})
  }

  /**
   * 同步单个 agent
   */
  async syncAgent(agent: string) {
    return this.post('/agents/sync', { agent })
  }

  // ============== 会话标题与删除 ==============

  /**
   * 会话标题懒生成
   */
  async getSessionTitle(sessionId: string) {
    return this.get<{ title: string }>(`/sessions/${sessionId}/title`)
  }

  /**
   * 删除会话原消息（软删除）
   */
  async deleteSessionMessages(sessionId: string) {
    return this.delete(`/sessions/${sessionId}/messages`)
  }

  /**
   * 获取用户画像列表
   */
  async getProfiles() {
    return this.get<any[]>('/profiles')
  }

  /**
   * 创建用户画像
   */
  async createProfile(data: any) {
    return this.post('/profiles', data)
  }

  /**
   * 更新用户画像
   */
  async updateProfile(id: string, data: any) {
    return this.put(`/profiles/${id}`, data)
  }

  /**
   * 删除用户画像
   */
  async deleteProfile(id: string) {
    return this.delete(`/profiles/${id}`)
  }


  /**
   * 获取技能沉淀列表
   */
  async getSkills() {
    return this.get<any[]>('/skills')
  }

  /**
   * 获取技能列表（按状态过滤）
   */
  async getSkillsByStatus(status: string) {
    return this.get<any[]>(`/skills?status=${status}`)
  }

  /**
   * 待确认技能候选数
   */
  async getPendingSkillCount() {
    return this.get<{ count: number }>('/skills/pending-count')
  }

  /**
   * 确认技能候选
   */
  async approveSkill(id: string) {
    return this.post(`/skills/${id}/approve`, {})
  }

  /**
   * 忽略技能候选
   */
  async rejectSkill(id: string) {
    return this.post(`/skills/${id}/reject`, {})
  }

  /**
   * 创建技能沉淀
   */
  async createSkill(data: any) {
    return this.post('/skills', data)
  }

  /**
   * 更新技能沉淀
   */
  async updateSkill(id: string, data: any) {
    return this.put(`/skills/${id}`, data)
  }

  /**
   * 删除技能沉淀
   */
  async deleteSkill(id: string) {
    return this.delete(`/skills/${id}`)
  }

  // ============== 系统 API ==============

  /**
   * 获取统计数据
   */
  async getStats() {
    return this.get<any>('/stats')
  }

  /**
   * 获取 Agent 列表
   */
  async getAgents() {
    return this.get<any[]>('/agents')
  }

  /**
   * 获取会话列表
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

  // ============== 初始化 API ==============

  /**
   * 获取初始化状态
   */
  async getSetupStatus() {
    return this.get<any>('/setup/status')
  }

  /**
   * 设置 Embedding 模型
   */
  async setEmbeddingModel(modelId: string) {
    return this.post('/setup/model', { modelId })
  }

  /**
   * 完成初始化
   */
  async completeSetup(modelId: string) {
    return this.post('/setup/complete', { modelId })
  }

  /**
   * 获取可用的 Agents 列表
   */
  async getSetupAgents() {
    return this.get<any[]>('/setup/agents')
  }

  /**
   * 从 Agents 导入会话
   */
  async importFromAgents(data: { agentTypes: string[], since: string }) {
    return this.post('/setup/import', data)
  }

  /**
   * 批量导入会话
   */
  async importSessions(sessions: any[]) {
    return this.post('/import', { sessions })
  }

  /**
   * 从文件导入会话
   */
  async importSessionsFromFile(data: any) {
    return this.post('/import/file', data)
  }
}

// 单例实例
export const apiService = new ApiService()

// 导出变量供其他模块使用
export const API_BASE_URL = API_BASE
export const EMBED_BASE_URL = EMBED_BASE
