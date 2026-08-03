<template>
  <div class="content-panel full">
    <div class="panel-header">
      <h2>系统设置</h2>
    </div>

    <el-card class="setting-card">
      <template #header>
        <div class="card-header">
          <span>LLM 配置</span>
          <el-tag :type="llmConfig.mode === 'disabled' ? 'info' : 'success'">
            {{ llmConfig.mode === 'disabled' ? '规则模式' : llmConfig.mode === 'api' ? '其他模型' : '内置模型' }}
          </el-tag>
        </div>
      </template>

      <!-- 已保存的配置预设 -->
      <div v-if="llmPresets.length > 0" class="preset-section">
        <div class="preset-header">
          <span class="preset-title">已保存的配置</span>
          <span class="preset-tip">点击即可切换使用</span>
        </div>
        <div class="preset-list">
          <div v-for="preset in llmPresets" :key="preset.id" class="preset-item" @click="applyPreset(preset)">
            <div class="preset-info">
              <span class="preset-name">{{ preset.name }}</span>
              <el-tag size="small" :type="preset.mode === 'api' ? 'primary' : 'success'">
                {{ preset.mode === 'api' ? preset.provider : '内置' }}
              </el-tag>
              <span class="preset-model">{{ preset.mode === 'api' ? preset.model : preset.localModel }}</span>
            </div>
            <el-button type="danger" size="small" text @click.stop="deletePreset(preset.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
      </div>

      <el-form label-width="120px" @submit.prevent>
        <el-form-item label="LLM 模式">
          <el-radio-group v-model="llmConfig.mode" @change="updateLLMConfig">
            <el-radio-button value="disabled">禁用（规则模式）</el-radio-button>
            <el-radio-button value="api">其他模型</el-radio-button>
            <el-radio-button value="local">
              内置模型
              <el-tooltip content="首次使用将自动下载约1.6GB的模型文件（embedding模型92MB + LLM模型1.5GB）到 ~/.agentmemory/models/" placement="top">
                <el-icon style="margin-left: 4px; vertical-align: middle; color: #E6A23C; cursor: help;">
                  <InfoFilled />
                </el-icon>
              </el-tooltip>
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="llmConfig.mode === 'api'">
          <el-form-item label="API 提供商">
            <el-select v-model="llmConfig.provider" @change="onProviderChange" style="width: 200px">
              <el-option label="OpenAI" value="openai" />
              <el-option label="智谱 AI" value="zhipu" />
              <el-option label="DeepSeek" value="deepseek" />
              <el-option label="Ollama" value="ollama" />
              <el-option label="自定义" value="custom" />
            </el-select>
          </el-form-item>

          <el-form-item label="API Base URL">
            <el-input v-model="llmConfig.baseUrl" placeholder="https://api.openai.com/v1" style="width: 400px" />
          </el-form-item>

          <el-form-item v-if="llmConfig.provider !== 'ollama'" label="API Key">
            <el-input v-model="llmConfig.apiKey" type="password" placeholder="sk-..." show-password style="width: 400px" @input="connectionTestSuccess = false" />
          </el-form-item>

          <el-form-item label="模型名称">
            <el-input v-model="llmConfig.model" placeholder="gpt-4o-mini" style="width: 300px" @input="connectionTestSuccess = false" />
          </el-form-item>

          <el-form-item v-if="llmConfig.provider === 'ollama'" label="思考模式">
            <el-switch v-model="llmConfig.thinkMode" />
            <span style="margin-left: 10px; font-size: 12px;">当前是「{{ llmConfig.thinkMode ? '思考' : '直接输出' }}」模式</span>
          </el-form-item>

          <el-form-item>
            <el-button type="warning" @click="testLLMConnection" :loading="testingConnection" :disabled="llmConfig.provider !== 'ollama' && !llmConfig.apiKey">
              测试连接
            </el-button>
            <el-button type="primary" @click="saveLLMConfig" :loading="savingConfig" :disabled="!connectionTestSuccess && llmConfig.provider !== 'ollama'">
              保存配置
            </el-button>
            <el-button type="success" @click="showSavePresetDialog = true" :disabled="llmConfig.provider !== 'ollama' && !llmConfig.apiKey">
              保存为预设
            </el-button>
            <span v-if="!connectionTestSuccess && llmConfig.apiKey && llmConfig.provider !== 'ollama'" class="form-tip" style="color: #e6a23c; margin-left: 8px;">请先测试连接</span>
          </el-form-item>
        </template>

        <template v-if="llmConfig.mode === 'local'">
          <el-form-item label="内置模型">
            <el-input v-model="llmConfig.localModel" disabled style="width: 400px" />
            <div class="form-tip">系统内置模型，无需配置</div>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" @click="saveLLMConfig" :loading="savingConfig">保存配置</el-button>
            <el-button type="success" @click="showSavePresetDialog = true">保存为预设</el-button>
          </el-form-item>
        </template>
      </el-form>

      <el-alert v-if="connectionTestResult" :title="connectionTestResult" :type="connectionTestSuccess ? 'success' : 'error'" show-icon closable @close="connectionTestResult = ''" />
    </el-card>

    <el-card class="setting-card">
      <template #header>
        <span>数据管理</span>
      </template>
      <el-form label-width="120px" @submit.prevent>
        <el-form-item label="自动清理">
          <el-switch v-model="autoCleanup" />
          <span class="form-tip" style="margin-left: 8px;">启用后自动清理过期对话记录</span>
        </el-form-item>
        <el-form-item label="保留天数">
          <el-input-number v-model="cleanupDays" :min="1" :max="365" :disabled="!autoCleanup" />
          <span class="form-tip" style="margin-left: 8px;">超过此天数的对话记录将被自动清理</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveCleanupConfig">保存</el-button>
          <el-button type="danger" @click="cleanupNow" :loading="cleaningUp">立即清理</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Embedding 模型选择 -->
    <el-card class="setting-card">
      <template #header>
        <div class="card-header">
          <span>Embedding 模型</span>
          <el-tag :type="embeddingStatus.status === 'ok' ? 'success' : 'info'">
            {{ embeddingStatus.embedding_model_name || '加载中...' }}
          </el-tag>
        </div>
      </template>
      <el-form label-width="120px" @submit.prevent>
        <el-form-item label="当前模型">
          <div class="embedding-model-info">
            <span class="model-name">{{ currentEmbeddingModelName }}</span>
            <el-tag size="small" type="info">{{ embeddingStatus.dimension || '-' }} 维</el-tag>
            <el-tag v-if="loadingEmbeddingModels" size="small" type="info">检测中...</el-tag>
            <el-tag v-else-if="currentEmbeddingModelDownloaded" size="small" type="success">已下载</el-tag>
            <el-tag v-else size="small" type="warning">未下载</el-tag>
          </div>
        </el-form-item>

        <el-form-item label="可用模型">
          <div class="embedding-models-list">
            <div
              v-for="model in embeddingModels"
              :key="model.id"
              class="embedding-model-item"
              :class="{ 'is-current': model.is_current }"
            >
              <div class="model-main-info">
                <span class="model-name">{{ model.name }}</span>
                <el-tag size="small" type="info">{{ model.dimension }}维</el-tag>
                <el-tag size="small" type="info">{{ model.size }}</el-tag>
                <el-tag v-if="model.status === 'downloading'" size="small" type="warning">
                  下载中 {{ downloadProgress[model.id]?.percent || 0 }}%
                </el-tag>
                <el-tag v-else-if="model.downloaded" size="small" type="success">已下载</el-tag>
                <el-tag v-else size="small" type="info">未下载</el-tag>
              </div>
              <div class="model-desc">{{ model.description }}</div>

              <div v-if="model.status === 'downloading'" class="download-progress">
                <el-progress
                  :percentage="downloadProgress[model.id]?.percent || 0"
                  :stroke-width="10"
                  :show-text="true"
                />
                <div class="progress-info">
                  <span v-if="downloadProgress[model.id]?.downloaded_mb">
                    {{ downloadProgress[model.id].downloaded_mb?.toFixed(1) }} /
                    {{ downloadProgress[model.id].total_mb?.toFixed(1) }} MB
                  </span>
                  <span v-if="downloadProgress[model.id]?.speed_mbps > 0" class="speed">
                    {{ downloadProgress[model.id].speed_mbps?.toFixed(2) }} MB/s
                  </span>
                  <span v-if="downloadProgress[model.id]?.eta_seconds" class="eta">
                    剩余 {{ formatEta(downloadProgress[model.id].eta_seconds) }}
                  </span>
                  <span v-if="downloadProgress[model.id]?.current_file" class="file">
                    {{ downloadProgress[model.id].current_file }}
                  </span>
                </div>
              </div>

              <div class="model-actions">
                <el-button
                  v-if="!model.downloaded && model.status !== 'downloading'"
                  type="primary"
                  size="small"
                  :loading="downloadingModel === model.id"
                  @click="downloadModel(model.id)"
                >
                  下载 ({{ model.download_size_mb }}MB)
                </el-button>
                <el-button
                  v-if="model.status === 'downloading'"
                  type="info"
                  size="small"
                  disabled
                >
                  下载中...
                </el-button>
                <el-button
                  v-if="model.downloaded && !model.is_current"
                  type="success"
                  size="small"
                  @click="selectAndSwitchModel(model.id)"
                >
                  使用此模型
                </el-button>
                <el-tag v-if="model.is_current" type="success" size="small">当前使用</el-tag>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item>
          <div class="embedding-tip">
            <el-icon><InfoFilled /></el-icon>
            <span>切换模型后需要重新生成向量索引，历史数据可能需要重新导入</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="setting-card">
      <template #header>
        <span>服务状态</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="Embedding 服务">{{ embeddingStatus.status || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="Embedding 模型">{{ embeddingStatus.embedding_model || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="向量维度">{{ embeddingStatus.dimension || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="LLM 状态">{{
          embeddingStatus.llm?.mode === 'local' ? '内置模型' :
          embeddingStatus.llm?.mode === 'api' ? 'API 模型' :
          embeddingStatus.llm?.mode === 'disabled' ? '规则模式' : '未知'
        }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 保存预设对话框 -->
    <el-dialog v-model="showSavePresetDialog" title="保存配置预设" width="400px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="newPresetName" placeholder="例如：我的DeepSeek" @keyup.enter="addPreset" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSavePresetDialog = false">取消</el-button>
        <el-button type="primary" @click="addPreset">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Delete, InfoFilled } from '@element-plus/icons-vue'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'

// ===== LLM 配置 =====
const llmConfig = ref({
  mode: 'disabled',
  provider: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  model: 'gpt-4o-mini',
  localModel: 'Qwen/Qwen3-0.6B',
  thinkMode: false
})

// LLM 配置预设（持久化到 localStorage）
interface LLMPreset {
  id: string
  name: string
  mode: 'api' | 'local'
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  localModel: string
}
const llmPresets = ref<LLMPreset[]>([])
const showSavePresetDialog = ref(false)
const newPresetName = ref('')

const savingConfig = ref(false)
const testingConnection = ref(false)
const connectionTestResult = ref('')
const connectionTestSuccess = ref(false)

const embeddingStatus = ref<any>({})
const embeddingModels = ref<any[]>([])
const selectedEmbeddingModel = ref('')
const loadingEmbeddingModels = ref(false)
const switchingEmbeddingModel = ref(false)
const downloadingModel = ref('')
const downloadPollingInterval = ref<any>(null)
const downloadProgress = ref<any>({})

// 提供商预设
const providerPresets: Record<string, { baseUrl: string; model: string }> = {
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'qwen3:0.6b' }
}

// ===== 清理配置 =====
const autoCleanup = ref(false)
const cleanupDays = ref(30)
const cleaningUp = ref(false)

// ===== 预设管理 =====
const loadPresets = async () => {
  try {
    const res = await axios.get(`${API_BASE}/llm-providers`)
    llmPresets.value = res.data.map((p: any) => ({
      id: p.id.toString(),
      name: p.displayName || p.providerName,
      mode: p.providerName === 'local' ? 'local' : 'api',
      provider: p.providerName,
      baseUrl: p.baseUrl || '',
      apiKey: p.apiKey || '',
      model: p.model || '',
      localModel: p.model || ''
    }))
  } catch (e) {
    console.error('加载预设失败', e)
  }
}

const savePresets = () => {
  localStorage.setItem('llmPresets', JSON.stringify(llmPresets.value))
}

const addPreset = () => {
  if (!newPresetName.value.trim()) return

  const preset: LLMPreset = {
    id: Date.now().toString(),
    name: newPresetName.value,
    mode: llmConfig.value.mode as 'api' | 'local',
    provider: llmConfig.value.provider,
    baseUrl: llmConfig.value.baseUrl,
    apiKey: llmConfig.value.apiKey,
    model: llmConfig.value.model,
    localModel: llmConfig.value.localModel
  }

  llmPresets.value.push(preset)
  savePresets()
  showSavePresetDialog.value = false
  newPresetName.value = ''
}

const applyPreset = async (preset: LLMPreset) => {
  llmConfig.value.mode = preset.mode
  llmConfig.value.provider = preset.provider
  llmConfig.value.baseUrl = preset.baseUrl
  llmConfig.value.apiKey = preset.apiKey
  llmConfig.value.model = preset.model
  llmConfig.value.localModel = preset.localModel
  connectionTestSuccess.value = false
  connectionTestResult.value = ''

  await saveLLMConfig()
}

const deletePreset = (id: string) => {
  llmPresets.value = llmPresets.value.filter(p => p.id !== id)
  savePresets()
}

// ===== LLM 配置操作 =====
const onProviderChange = (provider: string) => {
  const preset = providerPresets[provider]
  if (preset) {
    llmConfig.value.baseUrl = preset.baseUrl
    llmConfig.value.model = preset.model
  }
  if (provider === 'ollama') {
    llmConfig.value.apiKey = ''
    connectionTestSuccess.value = true
  } else {
    connectionTestSuccess.value = false
  }
}

const saveLLMConfig = async () => {
  savingConfig.value = true
  try {
    let providerName = 'custom'
    let baseUrl = ''
    let model = ''

    if (llmConfig.value.mode === 'local') {
      providerName = 'local'
      model = llmConfig.value.localModel
      baseUrl = 'http://localhost:11434'
    } else if (llmConfig.value.mode === 'api') {
      providerName = llmConfig.value.provider
      baseUrl = llmConfig.value.baseUrl
      model = llmConfig.value.model
    }

    await axios.post(`${API_BASE}/embedding/config`, {
      llm_mode: llmConfig.value.mode,
      llm_api_provider: providerName,
      llm_api_base: baseUrl,
      llm_api_key: llmConfig.value.apiKey,
      llm_api_model: model,
      llm_local_model: llmConfig.value.localModel
    })

    await axios.post(`${API_BASE}/llm-providers`, {
      providerName: providerName,
      displayName: `${providerName} (设置)`,
      baseUrl: baseUrl,
      apiKey: llmConfig.value.apiKey,
      model: model,
      thinkMode: llmConfig.value.thinkMode,
      enabled: true,
      isDefault: true
    })

    await loadPresets()
    await loadEmbeddingStatus()
    connectionTestResult.value = '配置已保存'
    connectionTestSuccess.value = true
  } catch (e: any) {
    console.error('保存配置失败', e)
    connectionTestResult.value = '保存失败: ' + (e.response?.data?.message || e.message)
    connectionTestSuccess.value = false
  } finally {
    savingConfig.value = false
  }
}

const testLLMConnection = async () => {
  testingConnection.value = true
  connectionTestResult.value = ''

  try {
    const res = await axios.post(`${API_BASE}/compression/test-llm`, {
      providerName: llmConfig.value.provider,
      baseUrl: llmConfig.value.baseUrl,
      model: llmConfig.value.model
    })

    if (res.data.success) {
      connectionTestResult.value = `✓ ${res.data.message}`
      connectionTestSuccess.value = true
    } else {
      connectionTestResult.value = `✗ ${res.data.error}`
      connectionTestSuccess.value = false
    }
  } catch (e: any) {
    const detail = e.response?.data?.error || e.message || '未知错误'
    connectionTestResult.value = `连接失败: ${detail}`
    connectionTestSuccess.value = false
  } finally {
    testingConnection.value = false
  }
}

const updateLLMConfig = () => {
  connectionTestSuccess.value = false
  connectionTestResult.value = ''
}

// ===== 清理配置 =====
const saveCleanupConfig = async () => {
  try {
    localStorage.setItem('autoCleanup', String(autoCleanup.value))
    localStorage.setItem('cleanupDays', String(cleanupDays.value))
    connectionTestResult.value = '清理配置已保存'
    connectionTestSuccess.value = true
  } catch (e) {
    connectionTestResult.value = '保存清理配置失败'
    connectionTestSuccess.value = false
  }
}

const cleanupNow = async () => {
  cleaningUp.value = true
  try {
    const res = await axios.post(`${API_BASE}/cleanup`, { days: cleanupDays.value })
    connectionTestResult.value = `清理完成，删除了 ${res.data.deleted || 0} 条记录`
    connectionTestSuccess.value = true
  } catch (e: any) {
    connectionTestResult.value = `清理失败: ${e.response?.data?.error || e.message || '未知错误'}`
    connectionTestSuccess.value = false
  } finally {
    cleaningUp.value = false
  }
}

// ===== Embedding 状态 =====
const loadEmbeddingStatus = async () => {
  try {
    const res = await axios.get(`${API_BASE}/embedding/health`)
    embeddingStatus.value = res.data

    if (res.data.llm) {
      llmConfig.value.mode = res.data.llm.mode || 'disabled'
      if (res.data.llm.mode === 'api') {
        llmConfig.value.provider = res.data.llm.provider || 'openai'
        llmConfig.value.model = res.data.llm.model || 'gpt-4o-mini'
        llmConfig.value.baseUrl = res.data.llm.base || ''
        llmConfig.value.apiKey = ''
      } else if (res.data.llm.mode === 'local') {
        llmConfig.value.localModel = res.data.llm.model || 'Qwen/Qwen3-0.6B'
      }
    }
  } catch (e) {
    console.error('获取 Embedding 服务状态失败', e)
  }
}

const loadEmbeddingModels = async () => {
  loadingEmbeddingModels.value = true
  try {
    const res = await axios.get(`${API_BASE}/embedding/models`)
    embeddingModels.value = res.data.models || []
    selectedEmbeddingModel.value = res.data.current || ''
  } catch (e) {
    console.error('加载 Embedding 模型列表失败', e)
  } finally {
    loadingEmbeddingModels.value = false
  }
}

const currentEmbeddingModelDownloaded = computed(() => {
  if (embeddingModels.value.length === 0) {
    return false
  }
  const current = embeddingModels.value.find(m => m.id === embeddingStatus.value.embedding_model)
  return current?.downloaded ?? false
})

const currentEmbeddingModelName = computed(() => {
  const current = embeddingModels.value.find(m => m.id === embeddingStatus.value.embedding_model)
  return current?.name || embeddingStatus.value.embedding_model_name || '加载中...'
})

const downloadModel = async (modelId: string) => {
  downloadingModel.value = modelId
  try {
    const res = await axios.post(`${API_BASE}/embedding/model/download`, {
      model_id: modelId
    })
    ElMessage.info(res.data.message || '开始下载模型...')
    startDownloadPolling()
  } catch (e: any) {
    console.error('下载模型失败', e)
    ElMessage.error(e.response?.data?.error || '下载模型失败')
    downloadingModel.value = ''
  }
}

const startDownloadPolling = () => {
  if (downloadPollingInterval.value) {
    clearInterval(downloadPollingInterval.value)
  }

  downloadPollingInterval.value = setInterval(async () => {
    try {
      const res = await axios.get(`${API_BASE}/embedding/model/download/status`)
      const statuses = res.data.models

      let hasDownloading = false
      for (const [modelId, status] of Object.entries(statuses)) {
        const statusObj = status as any

        if (statusObj.progress) {
          downloadProgress.value[modelId] = statusObj.progress
        }

        if (statusObj.status === 'downloading') {
          hasDownloading = true
        }
        if (statusObj.status === 'ready' && downloadingModel.value === modelId) {
          ElMessage.success('模型下载完成！')
          downloadingModel.value = ''
          delete downloadProgress.value[modelId]
        }
        if (statusObj.status === 'error' && downloadingModel.value === modelId) {
          const errorMsg = statusObj.progress?.error || '请检查网络连接'
          ElMessage.error(`模型下载失败: ${errorMsg}`)
          downloadingModel.value = ''
          delete downloadProgress.value[modelId]
        }
      }

      await loadEmbeddingModels()

      if (!hasDownloading) {
        clearInterval(downloadPollingInterval.value)
        downloadPollingInterval.value = null
      }
    } catch (e) {
      console.error('检查下载状态失败', e)
    }
  }, 2000)
}

const formatEta = (seconds: number): string => {
  if (!seconds || seconds <= 0) return ''
  if (seconds < 60) return `${seconds}秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分${seconds % 60}秒`
  return `${Math.floor(seconds / 3600)}时${Math.floor((seconds % 3600) / 60)}分`
}

const selectAndSwitchModel = async (modelId: string) => {
  switchingEmbeddingModel.value = true
  try {
    const res = await axios.post(`${API_BASE}/embedding/model`, {
      model_id: modelId
    })

    embeddingStatus.value.embedding_model = modelId
    embeddingStatus.value.embedding_model_name = embeddingModels.value.find(m => m.id === modelId)?.name
    embeddingStatus.value.dimension = res.data.dimension

    await loadEmbeddingModels()
    ElMessage.success(res.data.message || '模型切换成功')
  } catch (e: any) {
    console.error('切换 Embedding 模型失败', e)
    ElMessage.error(e.response?.data?.error || '切换模型失败')
  } finally {
    switchingEmbeddingModel.value = false
  }
}

onMounted(() => {
  loadEmbeddingStatus()
  loadEmbeddingModels()
  loadPresets()

  const savedAutoCleanup = localStorage.getItem('autoCleanup')
  const savedCleanupDays = localStorage.getItem('cleanupDays')
  if (savedAutoCleanup) autoCleanup.value = savedAutoCleanup === 'true'
  if (savedCleanupDays) cleanupDays.value = parseInt(savedCleanupDays) || 30
})

onUnmounted(() => {
  if (downloadPollingInterval.value) {
    clearInterval(downloadPollingInterval.value)
    downloadPollingInterval.value = null
  }
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

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.preset-section {
  margin-bottom: 20px;
}

.preset-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.preset-title {
  font-size: 13px;
  font-weight: 500;
  color: #606266;
}

.preset-tip {
  font-size: 12px;
  color: #909399;
}

.preset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preset-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-item:hover {
  border-color: #409eff;
  background: #f5f9ff;
}

.preset-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.preset-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.preset-model {
  font-size: 12px;
  color: #909399;
}

.embedding-model-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.embedding-models-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.embedding-model-item {
  padding: 12px;
  border: 1px solid #f0f2f5;
  border-radius: 8px;
}

.embedding-model-item.is-current {
  border-color: #67c23a;
  background: #f0f9eb;
}

.model-main-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.model-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.model-desc {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.download-progress {
  margin: 8px 0;
}

.progress-info {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.progress-info .speed {
  color: #67c23a;
}

.progress-info .eta {
  color: #e6a23c;
}

.progress-info .file {
  color: #409eff;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.embedding-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #e6a23c;
}
</style>
