<template>
  <div class="setup-container">
    <div class="setup-card">
      <!-- 步骤条 -->
      <div class="steps-wrapper">
        <el-steps :active="currentStep" finish-status="success" align-center>
          <el-step title="欢迎" />
          <el-step title="导入数据" />
          <el-step title="选择模型" />
          <el-step title="完成" />
        </el-steps>
      </div>

      <!-- 步骤 1: 欢迎 -->
      <div v-if="currentStep === 0" class="step-content">
        <div class="welcome-content">
          <el-icon :size="64" color="#409EFF"><Promotion /></el-icon>
          <h2>欢迎使用 AgentMemory</h2>
          <p class="intro">
            AgentMemory 可以从你本地安装的 Claude Code、Crush CLI 等工具自动导入会话历史，并提供语义搜索功能。
          </p>
          <div class="features">
            <div class="feature-item">
              <el-icon><ChatDotRound /></el-icon>
              <span>自动导入</span>
            </div>
            <div class="feature-item">
              <el-icon><Search /></el-icon>
              <span>语义搜索</span>
            </div>
            <div class="feature-item">
              <el-icon><Collection /></el-icon>
              <span>记忆沉淀</span>
            </div>
          </div>
          <el-button type="primary" size="large" @click="nextStep">
            开始设置 <el-icon><Right /></el-icon>
          </el-button>
        </div>
      </div>

      <!-- 步骤 2: 导入数据 -->
      <div v-else-if="currentStep === 1" class="step-content">
        <h3>选择要导入的 Agent</h3>
        <p class="intro">
          选择从哪个 Agent 导入会话历史，以及从哪个日期开始导入。
        </p>

        <div v-if="loadingAgents" v-loading="loadingAgents" style="min-height: 200px"></div>

        <div v-else-if="agentLoadError" class="agent-error">
          <el-alert type="error" :title="'加载 Agent 列表失败: ' + agentLoadError" show-icon :closable="false" />
          <el-button size="small" style="margin-top: 10px" @click="loadAgents">重试</el-button>
        </div>

        <div v-else class="agent-selection">
          <el-checkbox-group v-model="selectedAgents">
            <el-checkbox v-for="agent in availableAgents" :key="agent.type" :value="agent.type" border>
              <div class="agent-item">
                <div class="agent-name">{{ agent.name }}</div>
                <div class="agent-path">{{ agent.logPath }}</div>
              </div>
            </el-checkbox>
          </el-checkbox-group>
        </div>

        <div class="date-selection">
          <el-date-picker
            v-model="sinceDate"
            type="date"
            placeholder="选择起始日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledFutureDate"
          />
          <span class="date-hint">只导入此日期之后的会话</span>
        </div>

        <div v-if="importResult" class="import-result">
          <el-alert :type="importResult.imported > 0 ? 'success' : 'info'" show-icon>
            成功导入 {{ importResult.imported }} 个会话
          </el-alert>
        </div>

        <div class="step-actions">
          <el-button @click="skipImport">跳过</el-button>
          <el-button type="primary" :loading="importing" :disabled="selectedAgents.length === 0" @click="handleImport">
            开始导入
          </el-button>
        </div>
      </div>

      <!-- 步骤 3: 模型选择 -->
      <div v-else-if="currentStep === 2" class="step-content">
        <h3>选择 Embedding 模型</h3>
        <p class="intro">
          选择用于语义搜索的 Embedding 模型。模型用于将文本转换为向量，以便进行相似度搜索。
        </p>

        <div v-if="loadingModels" v-loading="loadingModels" style="min-height: 200px"></div>

        <div v-else-if="modelLoadError" class="empty-models">
          <el-alert type="warning" :title="modelLoadError" show-icon :closable="false" />
          <div style="margin-top: 16px; display: flex; gap: 12px; justify-content: center;">
            <el-button size="small" @click="loadModels">重试</el-button>
            <el-button size="small" type="warning" @click="handleComplete('')">跳过，稍后在设置中配置</el-button>
          </div>
        </div>

        <div v-else class="model-list">
          <el-radio-group v-model="selectedModel">
            <el-radio v-for="model in modelList" :key="model.id" :value="model.id" border>
              <div class="model-item">
                <div class="model-name">{{ model.name }}</div>
                <div class="model-desc">{{ model.description || '默认模型' }}</div>
                <el-tag v-if="model.downloaded" type="success" size="small">已下载</el-tag>
                <el-tag v-else type="info" size="small">未下载</el-tag>
              </div>
            </el-radio>
          </el-radio-group>
        </div>

        <div v-if="!loadingModels && !modelLoadError && modelList.length === 0" class="empty-models">
          <el-alert type="info">
            暂无可用模型，请确保 Embedding 服务已启动
          </el-alert>
        </div>

        <div class="step-actions">
          <el-button @click="currentStep = 1">上一步</el-button>
          <el-button type="primary" :disabled="!selectedModel && !modelLoadError" @click="handleComplete(selectedModel)">
            完成设置
          </el-button>
        </div>
      </div>

      <!-- 步骤 4: 完成 -->
      <div v-else-if="currentStep === 3" class="step-content">
        <div class="complete-content">
          <el-icon :size="64" color="#67C23A"><CircleCheckFilled /></el-icon>
          <h2>设置完成</h2>
          <p class="intro">
            您已成功完成初始设置，现在可以开始使用 AgentMemory。
          </p>
          <div class="stats">
            <div class="stat-item">
              <span class="stat-value">{{ stats.sessions || 0 }}</span>
              <span class="stat-label">会话</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ stats.messages || 0 }}</span>
              <span class="stat-label">消息</span>
            </div>
          </div>
          <el-button type="primary" size="large" @click="goToApp">
            进入主界面 <el-icon><Right /></el-icon>
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Right,
  Promotion,
  ChatDotRound,
  Search,
  Collection,
  CircleCheckFilled
} from '@element-plus/icons-vue'
import { apiService } from '../services/api'

// 事件：完成初始化
const emit = defineEmits<{
  complete: []
}>()

// 当前步骤
const currentStep = ref(0)

// Agents 选择
const loadingAgents = ref(false)
const agentLoadError = ref('')
const availableAgents = ref<any[]>([])
const selectedAgents = ref<string[]>([])
const sinceDate = ref('')
const importing = ref(false)
const importResult = ref<{ imported: number } | null>(null)

// 模型选择
const loadingModels = ref(false)
const modelLoadError = ref('')
const modelList = ref<any[]>([])
const selectedModel = ref('')

// 统计数据
const stats = ref({ sessions: 0, messages: 0 })

// 下一步
const nextStep = () => {
  currentStep.value++
  if (currentStep.value === 1) {
    loadAgents()
  } else if (currentStep.value === 2) {
    loadModels()
  }
}

// 跳过导入
const skipImport = () => {
  currentStep.value = 2
  loadModels()
}

// 加载可用的 Agents
const loadAgents = async () => {
  loadingAgents.value = true
  agentLoadError.value = ''
  try {
    console.log('正在加载 Agents...')
    const agents = await apiService.getSetupAgents()
    console.log('Agents 加载成功:', agents)
    availableAgents.value = agents || []
  } catch (error: any) {
    const msg = error?.response?.data?.error || error?.message || '网络连接失败，请确认后端服务已启动（端口 8082）'
    console.error('加载 Agents 失败:', error)
    agentLoadError.value = msg
    ElMessage.error('加载失败: ' + msg)
  } finally {
    loadingAgents.value = false
  }
}

// 导入选中的 Agents
const handleImport = async () => {
  if (selectedAgents.value.length === 0) {
    ElMessage.warning('请选择至少一个 Agent')
    return
  }

  importing.value = true
  try {
    const result = await apiService.importFromAgents({
      agentTypes: selectedAgents.value,
      since: sinceDate.value
    })
    importResult.value = result
    ElMessage.success(`成功导入 ${result.imported || 0} 个会话`)
    currentStep.value = 2
    loadModels()
  } catch (error: any) {
    ElMessage.error('导入失败: ' + (error.message || '未知错误'))
  } finally {
    importing.value = false
  }
}

// 加载模型列表
const loadModels = async () => {
  loadingModels.value = true
  modelLoadError.value = ''
  try {
    const result = await apiService.getEmbeddingModels()
    if (result?.models) {
      modelList.value = result.models
      const downloaded = modelList.value.find((m: any) => m.downloaded)
      selectedModel.value = downloaded?.id || modelList.value[0]?.id || ''
    } else {
      modelLoadError.value = '模型列表为空，请确保 Embedding 服务已启动（端口 8100）'
    }
  } catch (error: any) {
    const msg = error?.message || ''
    if (msg.includes('Network Error') || msg.includes('CORS') || msg.includes('timeout')) {
      modelLoadError.value = '无法连接 Embedding 服务（端口 8100），请检查服务是否已启动'
    } else {
      modelLoadError.value = '加载模型失败: ' + msg
    }
    console.error('加载模型失败:', error)
  } finally {
    loadingModels.value = false
  }
}

// 禁用未来日期
const disabledFutureDate = (date: Date) => {
  return date > new Date()
}

// 完成设置
const handleComplete = async (modelId?: string) => {
  const mid = modelId || selectedModel.value || ''
  try {
    await apiService.completeSetup(mid)
    currentStep.value = 3
    const status = await apiService.getSetupStatus()
    stats.value = status
    emit('complete')
  } catch (error: any) {
    const msg = error?.response?.data?.error || error?.message || '未知错误'
    ElMessage.error('加载失败: ' + msg)
  }
}

// 进入主界面
const goToApp = () => {
  emit('complete')
}

// 初始化
onMounted(async () => {
  try {
    const status = await apiService.getSetupStatus()
    if (status.initialized) {
      currentStep.value = 3
      // 从 /api/stats 获取真实统计数据
      try {
        const statsData = await apiService.getStats()
        stats.value = { sessions: statsData.totalSessions || statsData.sessions || 0, messages: statsData.totalMessages || statsData.messages || 0 }
      } catch {
        // fallback 到 setup status
        stats.value = { sessions: status.sessionCount || 0, messages: status.messageCount || 0 }
      }
    }
  } catch (error) {
    console.error('检查状态失败:', error)
  }
})
</script>

<style scoped>
.setup-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.setup-card {
  background: white;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  width: 100%;
  max-width: 600px;
  padding: 40px;
}

.steps-wrapper {
  margin-bottom: 40px;
}

.step-content {
  min-height: 300px;
}

.welcome-content,
.complete-content {
  text-align: center;
  padding: 20px 0;
}

.welcome-content h2,
.complete-content h2,
.step-content h3 {
  margin: 20px 0 10px;
  color: #303133;
}

.intro {
  color: #909399;
  margin-bottom: 30px;
  line-height: 1.6;
}

.features {
  display: flex;
  justify-content: center;
  gap: 30px;
  margin-bottom: 40px;
}

.feature-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: #606266;
}

.feature-item .el-icon {
  font-size: 32px;
  color: #409EFF;
}

.agent-selection {
  margin-bottom: 20px;
}

.agent-selection .el-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.agent-selection .el-checkbox {
  width: 100%;
  padding: 12px;
  margin-right: 0;
}

.agent-item {
  display: flex;
  flex-direction: column;
}

.agent-name {
  font-weight: 500;
  color: #303133;
}

.agent-path {
  color: #909399;
  font-size: 12px;
}

.date-selection {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.date-hint {
  color: #909399;
  font-size: 12px;
}

.import-result {
  margin-bottom: 20px;
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 30px;
}

.model-list .el-radio-group {
  width: 100%;
}

.model-list .el-radio {
  width: 100%;
  padding: 12px;
  margin-right: 0;
}

.model-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.model-name {
  font-weight: 500;
  color: #303133;
}

.model-desc {
  color: #909399;
  font-size: 12px;
  flex: 1;
}

.empty-models {
  margin-bottom: 30px;
}

.step-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 30px;
}

.stats {
  display: flex;
  justify-content: center;
  gap: 60px;
  margin: 30px 0 40px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-value {
  font-size: 36px;
  font-weight: 600;
  color: #409EFF;
}

.stat-label {
  color: #909399;
  font-size: 14px;
}
</style>