import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '../types'

// 使用 Vite 环境变量，支持运行时配置
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080/api'
const EMBED_BASE = import.meta.env.VITE_EMBED_BASE || 'http://localhost:8100'

/**
 * API 服务类
 * 统一管理所有 API 调用
 */
export class ApiService {
  /**
   * 通用 GET 请求
   */
  async get<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.get<ApiResponse<T>>(`${baseUrl}${endpoint}`)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '请求失败'
      console.error(`GET ${endpoint} 失败:`, msg)
      throw error
    }
  }

  /**
   * 通用 POST 请求
   */
  async post<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.post<ApiResponse<T>>(`${baseUrl}${endpoint}`, data)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '请求失败'
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 通用 PUT 请求
   */
  async put<T = any>(endpoint: string, data: any, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.put<ApiResponse<T>>(`${baseUrl}${endpoint}`, data)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '请求失败'
      ElMessage.error(msg)
      throw error
    }
  }

  /**
   * 通用 DELETE 请求
   */
  async delete<T = any>(endpoint: string, baseUrl: string = API_BASE): Promise<T> {
    try {
      const res = await axios.delete<ApiResponse<T>>(`${baseUrl}${endpoint}`)
      return res.data as T
    } catch (error: any) {
      const msg = error.response?.data?.error || error.message || '请求失败'
      ElMessage.error(msg)
      throw error
    }
  }

  // ============== 记忆库 API ==============

  /**
   * 获取错误纠正列表
   */
  async getErrors() {
    return this.get<any[]>('/errors')
  }

  /**
   * 创建错误纠正
   */
  async createError(data: any) {
    return this.post('/errors', data)
  }

  /**
   * 更新错误纠正
   */
  async updateError(id: string, data: any) {
    return this.put(`/errors/${id}`, data)
  }

  /**
   * 删除错误纠正
   */
  async deleteError(id: string) {
    return this.delete(`/errors/${id}`)
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
   * 获取实践经验列表
   */
  async getPractices() {
    return this.get<any[]>('/practices')
  }

  /**
   * 创建实践经验
   */
  async createPractice(data: any) {
    return this.post('/practices', data)
  }

  /**
   * 更新实践经验
   */
  async updatePractice(id: string, data: any) {
    return this.put(`/practices/${id}`, data)
  }

  /**
   * 删除实践经验
   */
  async deletePractice(id: string) {
    return this.delete(`/practices/${id}`)
  }

  /**
   * 获取项目上下文列表
   */
  async getContexts() {
    return this.get<any[]>('/contexts')
  }

  /**
   * 创建项目上下文
   */
  async createContext(data: any) {
    return this.post('/contexts', data)
  }

  /**
   * 更新项目上下文
   */
  async updateContext(id: string, data: any) {
    return this.put(`/contexts/${id}`, data)
  }

  /**
   * 删除项目上下文
   */
  async deleteContext(id: string) {
    return this.delete(`/contexts/${id}`)
  }

  /**
   * 获取技能沉淀列表
   */
  async getSkills() {
    return this.get<any[]>('/skills')
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
   * 获取 Embedding 模型列表
   */
  async getEmbeddingModels() {
    return this.get<any>('/embedding/models', EMBED_BASE)
  }

  /**
   * 下载 Embedding 模型
   */
  async downloadEmbeddingModel(modelId: string) {
    return this.post('/embedding/model/download', { model_id: modelId }, EMBED_BASE)
  }

  /**
   * 获取 Embedding 模型下载状态
   */
  async getEmbeddingModelDownloadStatus() {
    return this.get<any>('/embedding/model/download/status', EMBED_BASE)
  }

  /**
   * 切换 Embedding 模型
   */
  async switchEmbeddingModel(modelId: string) {
    return this.post('/embedding/model', { model_id: modelId }, EMBED_BASE)
  }
}

// 单例实例
export const apiService = new ApiService()

// 导出常量供其他模块使用
export const API_BASE_URL = API_BASE
export const EMBED_BASE_URL = EMBED_BASE