<template>
  <div class="sessions-view">
    <div class="content-panel">
      <div class="panel-header">
        <h2>对话记录</h2>
        <div class="header-actions">
          <el-select v-model="selectedAgent" placeholder="选择 Agent" clearable style="width: 150px">
            <el-option v-for="agent in agents" :key="agent.name" :label="agent.displayName || agent.name" :value="agent.name" />
          </el-select>
          <el-tag type="info" size="small">{{ filteredSessions.length }} 条</el-tag>
          <el-button @click="$emit('add-agent')" title="添加自定义 Agent">
            <el-icon><Plus /></el-icon>
          </el-button>
          <el-button @click="exportSessions">
            <el-icon><Download /></el-icon> 导出
          </el-button>
        </div>
      </div>
      <div class="session-list">
        <el-card v-for="session in filteredSessions" :key="session.id" class="session-card" @click="selectSession(session)">
          <div class="session-header">
            <el-tag :type="getAgentTagType(session.agentType)">{{ session.agentType }}</el-tag>
            <span class="session-time">{{ formatTime(session.createdAt) }}</span>
          </div>
          <div class="session-project">{{ session.projectPath || '未知项目' }}</div>
          <div class="session-count">{{ session.messageCount }} 条消息</div>
        </el-card>
      </div>
    </div>

    <!-- 对话详情 -->
    <div v-if="selectedSession" class="detail-panel">
      <div class="panel-header">
        <h3>{{ selectedSession.id }}</h3>
        <div class="header-actions">
          <el-button @click="exportSingleSession(selectedSession.id)" size="small">
            <el-icon><Download /></el-icon> 导出
          </el-button>
          <el-button @click="closeDetail" text>
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
      </div>
      <div class="message-list" v-loading="loadingMessages">
        <div v-for="msg in filteredMessages" :key="msg.id" class="message-item" :class="msg.role">
          <div class="message-role">{{ msg.role === 'user' ? '用户' : 'AI' }}</div>
          <div class="message-content">{{ msg.content || '(工具调用)' }}</div>
        </div>
        <div v-if="filteredMessages.length === 0 && messages.length > 0" class="empty-hint">
          已过滤 {{ messages.length - filteredMessages.length }} 条工具调用记录
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Plus, Download, Close } from '@element-plus/icons-vue'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'

interface Props {
  sessions: any[]
  agents: any[]
}

const props = defineProps<Props>()

defineEmits<{
  'add-agent': []
}>()

// Agent 名称映射：将 agent 表中的 name 映射到 session 表中的 agentType
const agentNameMapping: Record<string, string> = {
  'Claude Code': 'claude',
  'Codex CLI': 'codex',
  'Crush CLI': 'crush',
  'iFlow CLI': 'iflow',
  'Nanobot': 'nanobot',
  'OpenClaw': 'openclaw',
  'Qoder CLI': 'qoder',
  'Qwen CLI': 'qwen',
  'workbuddy': 'workbuddy'
}

const selectedAgent = ref('')
const selectedSession = ref<any>(null)
const messages = ref<any[]>([])
const loadingMessages = ref(false)

const filteredSessions = computed(() => {
  if (!selectedAgent.value) return props.sessions
  const targetType = agentNameMapping[selectedAgent.value]
    || selectedAgent.value.toLowerCase().replace(' ', '').replace('cli', '')
  return props.sessions.filter(s => s.agentType === targetType)
})

// 过滤空消息（工具调用等）
const filteredMessages = computed(() => {
  return messages.value.filter(m => m.content && m.content.trim().length > 0)
})

const selectSession = async (session: any) => {
  selectedSession.value = session
  loadingMessages.value = true
  try {
    const res = await axios.get(`${API_BASE}/messages/${session.id}`)
    messages.value = res.data
  } catch (e) {
    console.error('加载消息失败', e)
  } finally {
    loadingMessages.value = false
  }
}

const closeDetail = () => {
  selectedSession.value = null
  messages.value = []
}

const getAgentTagType = (type: string): string => {
  const types: Record<string, string> = {
    iflow: 'primary', claude: 'success', qwen: 'warning', qoder: 'danger',
    openclaw: 'info', nanobot: 'info', crush: 'danger', workbuddy: 'primary',
    codex: 'success', pi: 'success'
  }
  return types[type] || 'info'
}

const formatTime = (time: string | Date): string => {
  if (!time) return ''
  const d = new Date(time)
  return d.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

const exportSessions = async () => {
  try {
    const res = await axios.get(`${API_BASE}/sessions/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `sessions_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

const exportSingleSession = async (sessionId: string) => {
  try {
    const session = props.sessions.find((s: any) => s.id === sessionId)
    if (!session) {
      ElMessage.error('会话不存在')
      return
    }
    const messagesRes = await axios.get(`${API_BASE}/messages/${sessionId}`)
    const exportData = {
      session: session,
      messages: messagesRes.data,
      exportedAt: new Date().toISOString()
    }
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `session_${sessionId}_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}
</script>

<style scoped>
.sessions-view { display: flex; gap: 16px; height: 100%; width: 100%; }
.content-panel { flex: 1; max-width: 450px; background: #fff; border-radius: 8px; padding: 16px; display: flex; flex-direction: column; overflow: hidden; }
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-shrink: 0; }
.panel-header h2, .panel-header h3 { margin: 0; font-size: 18px; font-weight: 500; }
.header-actions { display: flex; align-items: center; gap: 10px; }
.session-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.session-card { cursor: pointer; transition: all 0.2s; border: 1px solid #e4e7ed; flex-shrink: 0; }
.session-card:hover { border-color: #409eff; box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15); }
.session-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.session-time { font-size: 12px; color: #909399; }
.session-project { font-size: 14px; color: #303133; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-count { font-size: 12px; color: #909399; }
.detail-panel { flex: 2; display: flex; flex-direction: column; background: #fff; border-radius: 8px; padding: 16px; overflow: hidden; }
.message-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }
.message-item { display: flex; gap: 12px; padding: 12px; border-radius: 8px; background: #f5f7fa; }
.message-item.user { background: #ecf5ff; flex-direction: row-reverse; }
.message-role { font-size: 12px; color: #909399; min-width: 40px; }
.message-content { flex: 1; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.empty-hint { text-align: center; color: #909399; padding: 20px; font-size: 13px; }
@media (max-width: 768px) {
  .sessions-view { flex-direction: column; }
  .content-panel { max-width: 100%; }
}
</style>
