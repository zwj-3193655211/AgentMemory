// ============== 系统状态类型 ==============

export interface Stats {
  sessions: number
  messages: number
  errors: number
  profiles: number
  practices: number
  contexts: number
  skills: number
}

// ============== 记忆库类型 ==============

// 错误纠正
export interface ErrorCorrection {
  id?: string
  title: string
  problem: string
  cause: string
  solution: string
  example?: string
  tags?: string[]
  agentType?: string
  sessionId?: string
  createdAt?: string
}

// 用户画像
export interface UserProfile {
  id?: string
  title: string
  category: 'preference' | 'behavior' | 'techstack' | 'workhabit' | 'other'
  items: any[]  // JSON数组
  createdAt?: string
  updatedAt?: string
}

// 实践经验
export interface BestPractice {
  id?: string
  title: string
  scenario: string
  practice: string
  rationale?: string
  tags?: string[]
  sourceSession?: string
  createdAt?: string
  expiresAt?: string
}

// 项目上下文
export interface ProjectContext {
  id?: string
  title: string
  projectPath?: string
  techStack?: string
  keyDecisions?: string
  structure?: string
  createdAt?: string
  updatedAt?: string
}

// 技能沉淀 - 基于 Claude Code 9类 Skill 能力地图
// 按研发流程组织：认知 → 生产 → 验证 → 交付

// Skill 类型枚举
export type SkillType = 
  // 认知环节 - 帮助 AI 快速获取领域知识
  | 'library-api'      // 库与 API 参考（内部专属知识/踩坑点）
  | 'data-analysis'    // 数据获取与分析（数据源/查询脚本）
  | 'troubleshooting'  // 故障排查手册（Runbook，结构化排障）
  // 生产环节 - 提升开发效率
  | 'scaffold'         // 代码脚手架与模板（标准化项目骨架）
  | 'automation'       // 业务流程与团队自动化（高频重复工作自动化）
  // 验证环节 - 确保代码质量
  | 'code-review'      // 代码质量与审查（含对抗式审查，规避模型偏差）
  | 'product-verify'   // 产品验证（工具配合+录制测试/步骤断言）
  // 交付环节 - 实现自动化部署
  | 'cicd'             // CI/CD 与部署（全链路交付自动化）
  | 'infra-ops'        // 基础设施运维（带安全护栏的高风险运维操作）

// Skill 类型标签映射
export const SKILL_TYPE_LABELS: Record<SkillType, { label: string; stage: string; desc: string }> = {
  // 认知环节
  'library-api': { label: '库与API参考', stage: '认知', desc: '内部专属知识、踩坑点、API用法' },
  'data-analysis': { label: '数据分析', stage: '认知', desc: '数据源配置、查询脚本、分析方法' },
  'troubleshooting': { label: '故障排查', stage: '认知', desc: 'Runbook、结构化排障流程' },
  // 生产环节
  'scaffold': { label: '代码脚手架', stage: '生产', desc: '标准化项目骨架、模板文件' },
  'automation': { label: '流程自动化', stage: '生产', desc: '高频重复工作自动化脚本' },
  // 验证环节
  'code-review': { label: '代码审查', stage: '验证', desc: '代码质量标准、对抗式审查规则' },
  'product-verify': { label: '产品验证', stage: '验证', desc: '测试工具配置、步骤断言' },
  // 交付环节
  'cicd': { label: 'CI/CD', stage: '交付', desc: '流水线配置、部署脚本' },
  'infra-ops': { label: '基础设施运维', stage: '交付', desc: '运维操作手册、安全护栏' }
}

// 研发阶段枚举
export type SkillStage = '认知' | '生产' | '验证' | '交付'

export interface Skill {
  id?: string
  title: string
  skillType: SkillType
  description: string
  steps?: string           // 详细步骤说明
  gotchas?: string         // 反常识/踩坑点（Claude 无法推导的知识）
  files?: string[]         // 关联的脚本、模板、配置文件路径
  triggers?: string[]      // 触发条件（何时使用此 Skill）
  safetyGuardrails?: string  // 安全护栏（高风险 Skill 必须）
  tags?: string[]
  createdAt?: string
  updatedAt?: string
}

// ============== LLM 类型 ==============

export interface LLMConfig {
  mode: 'disabled' | 'api' | 'local'
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  localModel: string
}

export interface LLMProvider {
  id: number
  providerName: string
  displayName: string
  baseUrl: string
  apiKey?: string
  model: string
  enabled: boolean
  isDefault: boolean
  thinkMode: boolean
}

// ============== 会话压缩类型 ==============

export interface CompressionConfig {
  autoCompress: boolean
  windowSize: number
  summaryThreshold: number
  compressionType: 'SLIDING_WINDOW' | 'SUMMARIZE' | 'HYBRID'
  llmProvider: string
}

export interface CompressionStats {
  totalSessions: number
  compressedSessions: number
  pendingSessions: number
  totalMessages: number
}

// ============== 表单状态类型 ==============

export interface DialogState<T> {
  visible: boolean
  isEdit: boolean
  formData: Partial<T>
}

export interface FormRules {
  [key: string]: any[]
}

// ============== Agent 类型 ==============

export interface Agent {
  id: number
  name: string
  displayName: string
  logBasePath: string
  cliPath?: string
  version?: string
  enabled: boolean
}

// ============== 会话类型 ==============

export interface Session {
  id: string
  agentType: string
  projectName?: string
  messageCount: number
  startTime: string
  lastActivity: string
  status: 'active' | 'archived' | 'deleted'
}

// ============== Embedding 模型类型 ==============

export interface EmbeddingModel {
  id: string
  name: string
  dimension: number
  size: string
  description: string
  download_size_mb: number
  is_current: boolean
  downloaded: boolean
  status: 'ready' | 'downloading' | 'error' | 'not_downloaded'
}

export interface DownloadProgress {
  downloaded_mb: number
  total_mb: number
  percent: number
  speed_mbps: number
  current_file: string
  files_done: number
  files_total: number
  error: string | null
  start_time: number
  eta_seconds?: number
}

// ============== API 响应类型 ==============

export interface ApiResponse<T = any> {
  data?: T
  message?: string
  error?: string
  models?: T
  current?: string
  status?: string
  progress?: DownloadProgress
}
