<template>
  <div class="content-panel full">
    <div class="panel-header">
      <h2>会话摘要</h2>
      <el-button type="primary" @click="loadCompressionStats">刷新</el-button>
    </div>

    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-number">{{ compressionStats.totalSessions }}</div>
            <div class="stat-label">总会话数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-number">{{ compressionStats.compressedSessions }}</div>
            <div class="stat-label">已压缩</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-number">{{ compressionStats.pendingSessions }}</div>
            <div class="stat-label">待压缩</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-number">{{ compressionStats.totalMessages }}</div>
            <div class="stat-label">压缩消息数</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="setting-card">
      <template #header>
        <div class="card-header">
          <span>压缩配置</span>
        </div>
      </template>
      <el-form :model="compressionConfig" label-width="120px">
        <el-form-item label="自动压缩">
          <el-switch v-model="compressionConfig.autoCompress" />
        </el-form-item>
        <el-form-item label="滑动窗口大小">
          <el-input-number v-model="compressionConfig.windowSize" :min="10" :max="500" />
        </el-form-item>
        <el-form-item label="摘要阈值">
          <el-input-number v-model="compressionConfig.summaryThreshold" :min="50" :max="1000" />
        </el-form-item>
        <el-form-item label="压缩方式">
          <el-radio-group v-model="compressionConfig.compressionType">
            <el-radio label="SLIDING_WINDOW">滑动窗口</el-radio>
            <el-radio label="SUMMARIZE">LLM摘要</el-radio>
            <el-radio label="HYBRID">混合</el-radio>
            <el-radio label="SEMANTIC">语义聚类</el-radio>
            <el-radio label="MULTI_LEVEL">多级摘要</el-radio>
            <el-radio label="INCREMENTAL">增量压缩</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- LLM 配置（仅在选择 LLM 相关压缩方式时显示） -->
        <template v-if="compressionConfig.compressionType !== 'SLIDING_WINDOW'">
          <el-divider content-position="left">LLM 配置</el-divider>

          <el-form-item label="使用 Provider">
            <el-select v-model="compressionConfig.llmProvider" placeholder="选择 LLM Provider" style="width: 250px" @change="onLLMProviderSelect">
              <el-option v-for="p in llmProviders.filter(x => x.enabled)" :key="p.id" :label="`${p.displayName || p.providerName} (${p.model})`" :value="p.providerName" />
            </el-select>
            <el-button type="primary" size="small" style="margin-left: 10px" @click="showAddLLMProvider = true">新建</el-button>
            <el-button v-if="compressionConfig.llmProvider" type="success" size="small" style="margin-left: 5px" @click="testLLMProvider(compressionConfig.llmProvider)" :loading="testingLLMConnection">
              测试连接
            </el-button>
          </el-form-item>

          <el-form-item v-if="compressionConfig.llmProvider && getLLMProvider(compressionConfig.llmProvider)" label="">
            <el-tag type="success" v-if="llmConnectionTestResult === true">✓ 连接正常</el-tag>
            <el-tag type="danger" v-else-if="llmConnectionTestResult === false">✗ 连接失败</el-tag>
            <span style="margin-left: 10px; color: #606266;">
              模型: <el-tag size="small" type="info">{{ getLLMProvider(compressionConfig.llmProvider)?.model }}</el-tag>
              <span style="margin-left: 10px;">{{ getLLMProvider(compressionConfig.llmProvider)?.baseUrl }}</span>
            </span>
          </el-form-item>
        </template>

        <el-form-item>
          <el-button type="primary" @click="saveCompressionConfig">保存配置</el-button>
          <el-button type="success" @click="triggerCompression">手动触发压缩</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- LLM Provider 配置 -->
    <el-card class="setting-card" style="margin-top: 20px">
      <template #header>
        <div class="card-header">
          <span>LLM Provider（用于会话压缩）</span>
          <el-button type="primary" size="small" @click="showAddLLMProvider = true">添加</el-button>
        </div>
      </template>
      <el-table :data="llmProviders" stripe>
        <el-table-column prop="displayName" label="名称" width="150" />
        <el-table-column prop="providerName" label="类型" width="100" />
        <el-table-column prop="baseUrl" label="Base URL" width="200" show-overflow-tooltip />
        <el-table-column prop="model" label="模型" width="150" />
        <el-table-column prop="enabled" label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="isDefault" label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" type="warning">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="danger" size="small" @click="deleteLLMProvider(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加 LLM Provider 对话框 -->
    <el-dialog v-model="showAddLLMProvider" title="添加 LLM Provider（用于会话压缩）" width="550px">
      <el-form :model="newLLMProvider" label-width="120px">
        <el-form-item label="API 提供商">
          <el-select v-model="newLLMProvider.providerName" @change="onLLMProviderChange" style="width: 200px">
            <el-option label="OpenAI" value="openai" />
            <el-option label="智谱 AI" value="zhipu" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="Ollama" value="ollama" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>

        <el-form-item label="API Base URL">
          <el-input v-model="newLLMProvider.baseUrl" placeholder="https://api.openai.com/v1" style="width: 400px" />
        </el-form-item>

        <el-form-item v-if="newLLMProvider.providerName !== 'ollama'" label="API Key">
          <el-input v-model="newLLMProvider.apiKey" type="password" placeholder="sk-..." show-password style="width: 400px" />
        </el-form-item>

        <el-form-item label="模型名称">
          <el-input v-model="newLLMProvider.model" :placeholder="getModelPlaceholder()" style="width: 300px" />
        </el-form-item>

        <el-form-item v-if="newLLMProvider.providerName === 'ollama'" label="思考模式">
          <el-switch v-model="newLLMProvider.thinkMode" />
          <span style="margin-left: 10px; font-size: 12px;">当前是「{{ newLLMProvider.thinkMode ? '思考' : '直接输出' }}」模式</span>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="newLLMProvider.displayName" placeholder="如：我的 OpenAI" style="width: 200px" />
        </el-form-item>

        <el-form-item label="设为默认">
          <el-switch v-model="newLLMProvider.isDefault" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddLLMProvider = false">取消</el-button>
        <el-button type="success" @click="testNewLLMProvider" :loading="testingLLMConnection">测试连接</el-button>
        <el-button type="primary" @click="addLLMProvider">保存</el-button>
      </template>
    </el-dialog>

    <el-card class="setting-card" style="margin-top: 20px">
      <template #header>
        <div class="card-header">
          <span>会话摘要列表</span>
        </div>
      </template>
      <el-table :data="sessionSummaries" stripe @row-click="openSummaryDetail">
        <el-table-column prop="sessionId" label="会话ID" width="200" show-overflow-tooltip />
        <el-table-column prop="summary" label="摘要内容" min-width="300" show-overflow-tooltip />
        <el-table-column prop="compressionType" label="压缩类型" width="120" />
        <el-table-column prop="messageCount" label="原消息数" width="100" />
        <el-table-column prop="compressedAt" label="压缩时间" width="180" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click.stop="deleteMessages(row)">删除原消息</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 摘要详情对话框 -->
    <el-dialog v-model="summaryDetail.visible" title="会话摘要详情" width="600px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="会话ID">{{ summaryDetail.data.sessionId }}</el-descriptions-item>
        <el-descriptions-item label="压缩类型">
          <el-tag size="small">{{ summaryDetail.data.compressionType }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="原消息数">{{ summaryDetail.data.messageCount }}</el-descriptions-item>
        <el-descriptions-item label="压缩时间">{{ summaryDetail.data.compressedAt }}</el-descriptions-item>
        <el-descriptions-item label="摘要内容">
          <div style="white-space: pre-wrap; word-break: break-word;">{{ summaryDetail.data.summary }}</div>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="summaryDetail.visible = false">关闭</el-button>
        <el-button type="primary" @click="viewSessionMessages(summaryDetail.data.sessionId)">查看原消息</el-button>
      </template>
    </el-dialog>

    <!-- 会话消息查看对话框 -->
    <el-dialog v-model="sessionMessages.visible" :title="`会话消息 - ${sessionMessages.sessionId.slice(0, 8)}...`" width="800px">
      <el-table :data="sessionMessages.messages" stripe max-height="500">
        <el-table-column prop="role" label="角色" width="80">
          <template #default="{ row }">
            <el-tag :type="row.role === 'user' ? 'primary' : 'success'" size="small">
              {{ row.role === 'user' ? '用户' : '助手' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" show-overflow-tooltip>
          <template #default="{ row }">
            <div style="max-height: 60px; overflow: hidden; text-overflow: ellipsis;">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="时间" width="160">
          <template #default="{ row }">{{ row.timestamp ? new Date(row.timestamp).toLocaleString('zh-CN') : '' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="sessionMessages.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiService } from '../services/api'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

// ===== 压缩统计 =====
const compressionStats = ref({
  totalSessions: 0,
  compressedSessions: 0,
  pendingSessions: 0,
  totalMessages: 0
})

const compressionConfig = ref({
  autoCompress: true,
  windowSize: 50,
  summaryThreshold: 100,
  compressionType: 'SLIDING_WINDOW',
  llmProvider: ''
})

const sessionSummaries = ref<any[]>([])

// ===== 摘要详情对话框 =====
const summaryDetail = reactive({
  visible: false,
  data: {} as { sessionId: string; summary: string; compressionType: string; messageCount: number; compressedAt: string }
})

const openSummaryDetail = (row: any) => {
  summaryDetail.data = row
  summaryDetail.visible = true
}

// 删除会话原消息（软删除，摘要保留）
const deleteMessages = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      '删除后原对话不可恢复（压缩摘要保留），确定删除？',
      '删除原消息',
      { type: 'warning' }
    )
    await apiService.deleteSessionMessages(row.sessionId)
    ElMessage.success('原消息已删除，摘要保留')
  } catch (error) {
    if (error !== 'cancel') {}
  }
}

const viewSessionMessages = async (sessionId: string) => {
  summaryDetail.visible = false
  try {
    const res = await axios.get(`${API_BASE}/messages/${sessionId}`)
    sessionMessages.messages = res.data || []
    sessionMessages.sessionId = sessionId
    sessionMessages.visible = true
  } catch (e: any) {
    ElMessage.error('加载消息失败: ' + e.message)
  }
}

const sessionMessages = reactive({
  visible: false,
  sessionId: '',
  messages: [] as any[]
})

// ===== LLM Provider =====
const llmProviders = ref<any[]>([])
const showAddLLMProvider = ref(false)
const newLLMProvider = ref({
  providerName: 'openai',
  displayName: '',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  model: 'gpt-4o-mini',
  enabled: true,
  isDefault: false,
  thinkMode: false
})

const loadLLMProviders = async () => {
  try {
    const res = await axios.get(`${API_BASE}/llm-providers`)
    llmProviders.value = res.data
    if (res.data.length > 0 && !compressionConfig.value.llmProvider) {
      const defaultProvider = res.data.find((p: any) => p.isDefault) || res.data[0]
      compressionConfig.value.llmProvider = defaultProvider.providerName
    }
  } catch (e: any) {
    console.error('加载 LLM Provider 失败', e)
  }
}

const getLLMProvider = (providerName: string) => {
  return llmProviders.value.find(p => p.providerName === providerName)
}

const addLLMProvider = async () => {
  try {
    const data = {
      providerName: newLLMProvider.value.providerName,
      displayName: newLLMProvider.value.displayName || newLLMProvider.value.providerName,
      baseUrl: newLLMProvider.value.baseUrl,
      apiKey: newLLMProvider.value.apiKey,
      model: newLLMProvider.value.model,
      enabled: newLLMProvider.value.enabled,
      isDefault: newLLMProvider.value.isDefault,
      thinkMode: newLLMProvider.value.thinkMode
    }
    await axios.post(`${API_BASE}/llm-providers`, data)
    showAddLLMProvider.value = false
    newLLMProvider.value = {
      providerName: 'openai',
      displayName: '',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: '',
      model: 'gpt-4o-mini',
      enabled: true,
      isDefault: false,
      thinkMode: false
    }
    loadLLMProviders()
    ElMessage.success('LLM Provider 已保存')
  } catch (e: any) {
    console.error('添加 LLM Provider 失败', e)
    ElMessage.error('保存失败: ' + e.message)
  }
}

const deleteLLMProvider = async (id: number) => {
  try {
    await axios.delete(`${API_BASE}/llm-providers/${id}`)
    loadLLMProviders()
  } catch (e: any) {
    console.error('删除 LLM Provider 失败', e)
  }
}

const getModelPlaceholder = () => {
  const provider = newLLMProvider.value.providerName
  if (provider === 'openai') return 'gpt-4o-mini'
  if (provider === 'deepseek') return 'deepseek-chat'
  if (provider === 'zhipu') return 'glm-4-flash'
  if (provider === 'ollama') return 'qwen3:0.6b'
  return 'model-name'
}

const onLLMProviderChange = () => {
  const p = newLLMProvider.value
  if (p.providerName === 'openai') {
    if (!p.baseUrl) p.baseUrl = 'https://api.openai.com/v1'
    if (!p.model) p.model = 'gpt-4o-mini'
  } else if (p.providerName === 'deepseek') {
    if (!p.baseUrl) p.baseUrl = 'https://api.deepseek.com/v1'
    if (!p.model) p.model = 'deepseek-chat'
  } else if (p.providerName === 'zhipu') {
    if (!p.baseUrl) p.baseUrl = 'https://open.bigmodel.cn/api/paas/v4'
    if (!p.model) p.model = 'glm-4-flash'
  } else if (p.providerName === 'ollama') {
    if (!p.baseUrl) p.baseUrl = 'http://localhost:11434'
    if (!p.model) p.model = 'qwen3:0.6b'
  }
}

// ===== 压缩操作 =====
const loadCompressionStats = async () => {
  try {
    const res = await axios.get(`${API_BASE}/compression`)
    compressionStats.value = res.data.stats
    compressionConfig.value = res.data.config
    sessionSummaries.value = res.data.summaries || []
  } catch (e: any) {
    console.error('加载压缩统计失败', e)
  }
  loadLLMProviders()
}

const saveCompressionConfig = async () => {
  try {
    await axios.post(`${API_BASE}/compression`, compressionConfig.value)
    ElMessage.success('配置已保存')
  } catch (e: any) {
    ElMessage.error('保存失败: ' + e.message)
  }
}

const triggerCompression = async () => {
  try {
    await axios.put(`${API_BASE}/compression`)
    ElMessage.success('压缩任务已触发')
  } catch (e: any) {
    ElMessage.error('触发失败: ' + e.message)
  }
}

// ===== LLM 连接测试 =====
const testingLLMConnection = ref(false)
const llmConnectionTestResult = ref<boolean | null>(null)

const onLLMProviderSelect = () => {
  llmConnectionTestResult.value = null
}

const testLLMProvider = async (providerName: string) => {
  testingLLMConnection.value = true
  llmConnectionTestResult.value = null
  try {
    const provider = getLLMProvider(providerName)
    if (!provider) {
      ElMessage.error('未找到 Provider')
      return
    }
    const res = await axios.post(`${API_BASE}/compression/test-llm`, {
      providerName: provider.providerName,
      baseUrl: provider.baseUrl,
      model: provider.model
    })
    if (res.data.success) {
      llmConnectionTestResult.value = true
      ElMessage.success('LLM 连接测试成功')
    } else {
      llmConnectionTestResult.value = false
      ElMessage.error('连接失败: ' + res.data.error)
    }
  } catch (e: any) {
    llmConnectionTestResult.value = false
    ElMessage.error('测试失败: ' + e.message)
  } finally {
    testingLLMConnection.value = false
  }
}

const testNewLLMProvider = async () => {
  if (!newLLMProvider.value.baseUrl || !newLLMProvider.value.model) {
    ElMessage.warning('请先填写 Base URL 和模型名称')
    return
  }
  testingLLMConnection.value = true
  try {
    const res = await axios.post(`${API_BASE}/compression/test-llm`, {
      providerName: newLLMProvider.value.providerName,
      baseUrl: newLLMProvider.value.baseUrl,
      model: newLLMProvider.value.model
    })
    if (res.data.success) {
      ElMessage.success('LLM 连接测试成功')
    } else {
      ElMessage.error('连接失败: ' + res.data.error)
    }
  } catch (e: any) {
    ElMessage.error('测试失败: ' + e.message)
  } finally {
    testingLLMConnection.value = false
  }
}

onMounted(() => {
  loadCompressionStats()
})
</script>

<style scoped>
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f2f5;
}

.panel-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
}

.stat-card {
  border: none;
  border-radius: 12px;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-number {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  line-height: 1.2;
}

.stat-label {
  font-size: 14px;
  color: #606266;
  margin-top: 4px;
}

.setting-card {
  margin-bottom: 20px;
  border-radius: 12px;
  border: 1px solid #f0f2f5;
}

.setting-card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f2f5;
  font-weight: 600;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
