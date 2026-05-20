<template>
  <div class="chat-container">
    <!-- 左侧：Agent 选择 + 会话列表 -->
    <div class="chat-sidebar">
      <div class="agent-selector">
        <el-select v-model="selectedAgentId" placeholder="选择 Agent" class="full-width" @change="onAgentChange">
          <el-option-group v-for="group in agentGroups" :key="group.label" :label="group.label">
            <el-option
              v-for="agent in group.agents"
              :key="agent.id"
              :label="agent.name"
              :value="agent.id"
            />
          </el-option-group>
        </el-select>
        <div class="btn-row">
          <el-button type="primary" @click="createNewSession" :disabled="!selectedAgentId" class="flex-1">
            + 新对话
          </el-button>
          <el-button @click="openImportDialog" :disabled="!currentSessionId" class="flex-1">
            📥 导入
          </el-button>
        </div>
      </div>

      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: currentSessionId === session.id }"
          @click="loadSession(session.id)"
        >
          <div class="session-title">{{ session.title || '新对话' }}</div>
          <div class="session-meta">{{ session.agentName }} · {{ formatTime(session.updatedAt) }}</div>
          <el-button class="delete-btn" link type="danger" size="small" @click.stop="deleteSession(session.id)">
            ✕
          </el-button>
        </div>
        <div v-if="sessions.length === 0" class="empty-sessions">
          暂无对话记录
        </div>
      </div>
    </div>

    <!-- 右侧：聊天区域 -->
    <div class="chat-main">
      <div v-if="!currentSessionId" class="no-session">
        <el-icon :size="48" color="#c0c4cc"><ChatDotRound /></el-icon>
        <p>选择 Agent 并创建新对话开始聊天</p>
      </div>

      <template v-else>
        <!-- 消息列表 -->
        <div class="messages-area" ref="messagesArea">
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="message-row"
            :class="msg.role"
          >
            <div class="message-avatar">
              {{ msg.role === 'user' ? '👤' : msg.role === 'system' ? '📥' : '🤖' }}
            </div>
            <div class="message-bubble" :class="{ 'imported': msg.role === 'system' }">
              <div class="message-role">{{ msg.role === 'user' ? '你' : msg.role === 'system' ? '导入历史' : 'AI' }}</div>
              <div class="message-content" v-html="renderMarkdown(msg.content)"></div>
            </div>
          </div>

          <!-- 正在输入指示器 -->
          <div v-if="isStreaming" class="message-row assistant">
            <div class="message-avatar">🤖</div>
            <div class="message-bubble">
              <div class="typing-indicator">
                <span></span><span></span><span></span>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入框 -->
        <div class="input-area">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            placeholder="输入消息... (Enter 发送, Shift+Enter 换行)"
            :disabled="isStreaming"
            @keydown="handleKeyDown"
            resize="none"
          />
          <el-button
            type="primary"
            @click="sendMessage"
            :disabled="!inputMessage.trim() || isStreaming"
            :loading="isStreaming"
            class="send-btn"
          >
            {{ isStreaming ? '生成中...' : '发送' }}
          </el-button>
        </div>
      </template>
    </div>

    <!-- 导入历史对话弹窗 -->
    <el-dialog v-model="importDialogVisible" title="导入历史对话" width="650px" :close-on-click-modal="false">
      <div class="import-dialog-body">
        <div class="import-toolbar">
          <p class="import-hint">从记忆库中选择会话，将其消息导入到当前对话中。</p>
          <el-button size="small" type="success" @click="triggerRescan" :loading="rescanning" plain>
            🔄 重新扫描
          </el-button>
        </div>

        <!-- 搜索 -->
        <el-input
          v-model="importSearchQuery"
          placeholder="搜索会话（Agent / 项目路径）..."
          clearable
          size="default"
          class="import-search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <!-- 加载状态 -->
        <div v-if="importLoading" class="import-loading">
          <el-icon class="is-loading" :size="20"><Loading /></el-icon> 加载中...
        </div>

        <!-- 会话列表 -->
        <div v-else class="import-session-list">
          <div
            v-for="session in filteredImportSessions"
            :key="session.id"
            class="import-session-item"
            :class="{ selected: selectedImportIds.has(session.id) }"
            @click="toggleImportSelect(session.id)"
          >
            <el-checkbox
              :model-value="selectedImportIds.has(session.id)"
              @change="toggleImportSelect(session.id)"
              @click.stop
            />
            <div class="import-session-info">
              <div class="import-session-header">
                <el-tag size="small" type="info">{{ session.agentType || session.agent }}</el-tag>
                <span class="import-msg-count">{{ session.messageCount || 0 }} 条消息</span>
              </div>
              <div class="import-session-path">{{ session.projectPath || '-' }}</div>
              <div class="import-session-time">{{ formatTime(session.createdAt) }}</div>
            </div>
          </div>
          <div v-if="filteredImportSessions.length === 0" class="import-empty">
            没有找到匹配的会话
          </div>
        </div>

        <!-- 底部统计 -->
        <div class="import-footer">
          已选择 <strong>{{ selectedImportIds.size }}</strong> 个会话
        </div>
      </div>

      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmImport" :disabled="selectedImportIds.size === 0" :loading="importing">
          导入 ({{ selectedImportIds.size }})
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, watch, computed } from 'vue'
import { ChatDotRound, Search, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'

// 配置 marked
marked.setOptions({
  highlight(code: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  },
  breaks: true,
  gfm: true
})

// 状态
const selectedAgentId = ref('')
const agents = ref<any[]>([])
const sessions = ref<any[]>([])
const currentSessionId = ref('')
const messages = ref<any[]>([])
const inputMessage = ref('')
const isStreaming = ref(false)
const messagesArea = ref<HTMLElement | null>(null)
const currentAgent = ref<any>(null)

// Agent 分组
const agentGroups = ref<{ label: string; agents: any[] }[]>([])

// 导入相关状态
const importDialogVisible = ref(false)
const importLoading = ref(false)
const importing = ref(false)
const rescanning = ref(false)
const importSearchQuery = ref('')
const importSessions = ref<any[]>([])
const selectedImportIds = ref<Set<string>>(new Set())

// 搜索过滤后的会话列表
const filteredImportSessions = computed(() => {
  const q = importSearchQuery.value.toLowerCase().trim()
  if (!q) return importSessions.value
  return importSessions.value.filter(s =>
    (s.agentType || s.agent || '').toLowerCase().includes(q) ||
    (s.projectPath || '').toLowerCase().includes(q) ||
    (s.title || '').toLowerCase().includes(q)
  )
})

// 重新扫描所有 Agent 目录，导入新对话
async function triggerRescan() {
  rescanning.value = true
  try {
    // 先获取检测到的所有 agent 类型
    const agentsRes = await fetch(`${API_BASE}/setup/agents`)
    const agents = await agentsRes.json()
    const agentTypes = (agents || []).map((a: any) => a.type).filter(Boolean)

    if (agentTypes.length === 0) {
      ElMessage.warning('未检测到任何 Agent')
      return
    }

    // 触发导入
    const res = await fetch(`${API_BASE}/setup/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentTypes })
    })
    const result = await res.json()

    ElMessage.success(`已触发 ${agentTypes.length} 个 Agent 的重新扫描，请稍等片刻后刷新列表`)

    // 等待 3 秒后重新加载会话列表（给文件扫描一些时间）
    setTimeout(async () => {
      await loadImportSessions()
    }, 3000)
  } catch (e) {
    console.error('重新扫描失败', e)
    ElMessage.error('重新扫描失败')
  } finally {
    rescanning.value = false
  }
}

// 加载导入会话列表（提取为独立方法以便复用）
async function loadImportSessions() {
  importLoading.value = true
  try {
    const res = await fetch(`${API_BASE}/sessions?limit=100`)
    const data = await res.json()
    importSessions.value = data.filter((s: any) => (s.messageCount || 0) > 0)
  } catch (e) {
    console.error('加载历史会话失败', e)
    ElMessage.error('加载历史会话失败')
  } finally {
    importLoading.value = false
  }
}

// ===== 导入历史对话 =====

async function openImportDialog() {
  if (!currentSessionId.value) {
    ElMessage.warning('请先选择或创建一个对话会话')
    return
  }
  importDialogVisible.value = true
  importSearchQuery.value = ''
  selectedImportIds.value = new Set()
  await loadImportSessions()
}

function toggleImportSelect(id: string) {
  const newSet = new Set(selectedImportIds.value)
  if (newSet.has(id)) {
    newSet.delete(id)
  } else {
    newSet.add(id)
  }
  selectedImportIds.value = newSet
}

async function confirmImport() {
  if (selectedImportIds.value.size === 0) return

  importing.value = true
  try {
    const res = await fetch(`${API_BASE}/chat/import-messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: currentSessionId.value,
        sourceSessionIds: Array.from(selectedImportIds.value)
      })
    })
    const result = await res.json()

    if (result.status === 'ok') {
      ElMessage.success(`成功导入 ${result.importedCount} 个会话`)
      importDialogVisible.value = false
      // 重新加载当前会话消息
      await loadSession(currentSessionId.value)
    } else {
      ElMessage.error('导入失败')
    }
  } catch (e: any) {
    console.error('导入失败', e)
    ElMessage.error('导入失败: ' + e.message)
  } finally {
    importing.value = false
  }
}

// 加载 agent 列表
async function loadAgents() {
  try {
    const res = await fetch(`${API_BASE}/chat/agents`)
    agents.value = await res.json()

    // 分组：LLM / CLI
    const llmAgents = agents.value.filter(a => a.type === 'llm')
    const cliAgents = agents.value.filter(a => a.type === 'cli')
    agentGroups.value = [
      { label: '🤖 LLM API', agents: llmAgents },
      { label: '💻 本地 CLI', agents: cliAgents }
    ]
  } catch (e) {
    console.error('加载 agents 失败', e)
  }
}

// 加载会话列表
async function loadSessions() {
  try {
    const res = await fetch(`${API_BASE}/chat/sessions`)
    sessions.value = await res.json()
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

// 选择 Agent
function onAgentChange(agentId: string) {
  currentAgent.value = agents.value.find(a => a.id === agentId)
}

// 创建新会话
async function createNewSession() {
  if (!currentAgent.value) return

  try {
    const res = await fetch(`${API_BASE}/chat/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        agentId: currentAgent.value.id,
        agentName: currentAgent.value.name,
        agentType: currentAgent.value.type
      })
    })
    const session = await res.json()
    currentSessionId.value = session.id
    messages.value = []
    await loadSessions()
    scrollToBottom()
  } catch (e) {
    console.error('创建会话失败', e)
  }
}

// 加载会话消息
async function loadSession(sessionId: string) {
  currentSessionId.value = sessionId
  try {
    const res = await fetch(`${API_BASE}/chat/sessions/${sessionId}`)
    messages.value = await res.json()
    await nextTick()
    scrollToBottom()
  } catch (e) {
    console.error('加载消息失败', e)
  }
}

// 删除会话
async function deleteSession(sessionId: string) {
  try {
    await fetch(`${API_BASE}/chat/sessions/${sessionId}`, { method: 'DELETE' })
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = ''
      messages.value = []
    }
  } catch (e) {
    console.error('删除会话失败', e)
  }
}

// 发送消息（SSE 流式）
async function sendMessage() {
  const msg = inputMessage.value.trim()
  if (!msg || !currentSessionId.value || !currentAgent.value) return

  // 添加用户消息到界面
  const userMsg = {
    id: 'temp-' + Date.now(),
    role: 'user',
    content: msg,
    createdAt: new Date().toISOString()
  }
  messages.value.push(userMsg)
  inputMessage.value = ''
  await nextTick()
  scrollToBottom()

  // 创建 AI 回复占位
  const aiMsg = {
    id: 'ai-' + Date.now(),
    role: 'assistant',
    content: '',
    createdAt: new Date().toISOString()
  }
  messages.value.push(aiMsg)
  isStreaming.value = true

  try {
    const res = await fetch(`${API_BASE}/chat/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: currentSessionId.value,
        message: msg,
        agentType: currentAgent.value.type,
        agentConfig: {
          baseUrl: currentAgent.value.baseUrl,
          apiKey: currentAgent.value.apiKey,
          model: currentAgent.value.model,
          command: currentAgent.value.command
        }
      })
    })

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }

    // 读取 SSE 流
    const reader = res.body?.getReader()
    const decoder = new TextDecoder()

    if (reader) {
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.substring(6))
              if (data.content) {
                aiMsg.content += data.content
                await nextTick()
                scrollToBottom()
              }
            } catch (e) {
              // 忽略解析错误
            }
          }
        }
      }
    }
  } catch (e: any) {
    aiMsg.content = '❌ 发送失败: ' + e.message
  } finally {
    isStreaming.value = false
    await loadSessions() // 刷新会话列表（更新时间/标题）
  }
}

// 键盘事件
function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 渲染 Markdown
function renderMarkdown(content: string): string {
  if (!content) return ''
  try {
    return marked.parse(content) as string
  } catch {
    return content.replace(/\n/g, '<br>')
  }
}

// 滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (messagesArea.value) {
      messagesArea.value.scrollTop = messagesArea.value.scrollHeight
    }
  })
}

// 格式化时间
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  return date.toLocaleDateString('zh-CN')
}

onMounted(() => {
  loadAgents()
  loadSessions()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  height: 100%;
  gap: 0;
}

/* 左侧边栏 */
.chat-sidebar {
  width: 280px;
  min-width: 280px;
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
}

.agent-selector {
  padding: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.btn-row {
  display: flex;
  gap: 8px;
}

.flex-1 {
  flex: 1;
}

.full-width {
  width: 100%;
}

.new-chat-btn {
  width: 100%;
}

.session-list {
  flex: 1;
  overflow-y: auto;
}

.session-item {
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  position: relative;
  transition: background 0.2s;
}

.session-item:hover {
  background: var(--el-fill-color-light);
}

.session-item.active {
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
}

.session-title {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 20px;
}

.session-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.delete-btn {
  position: absolute;
  top: 12px;
  right: 8px;
  opacity: 0;
  transition: opacity 0.2s;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.empty-sessions {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 40px 16px;
  font-size: 14px;
}

/* 右侧聊天区 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.no-session {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--el-text-color-secondary);
}

/* 消息区域 */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-row {
  display: flex;
  gap: 12px;
  max-width: 85%;
}

.message-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-row.assistant {
  align-self: flex-start;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
  background: var(--el-fill-color-light);
}

.message-bubble {
  border-radius: 12px;
  padding: 12px 16px;
  max-width: 100%;
}

.message-row.user .message-bubble {
  background: var(--el-color-primary);
  color: white;
  border-bottom-right-radius: 4px;
}

.message-row.assistant .message-bubble {
  background: var(--el-fill-color-light);
  border-bottom-left-radius: 4px;
}

.message-role {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
  opacity: 0.7;
}

.message-content {
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

/* Markdown 内容样式 */
.message-content :deep(pre) {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  margin: 8px 0;
}

.message-content :deep(code) {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
}

.message-content :deep(p code) {
  background: rgba(0, 0, 0, 0.1);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}

.message-content :deep(p) {
  margin: 8px 0;
}

.message-content :deep(ul),
.message-content :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}

.message-content :deep(blockquote) {
  border-left: 3px solid var(--el-color-primary);
  padding-left: 12px;
  margin: 8px 0;
  opacity: 0.8;
}

.message-content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
}

.message-content :deep(th),
.message-content :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 6px 12px;
}

/* 打字指示器 */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.typing-indicator span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-text-color-secondary);
  animation: typing 1.4s infinite;
}

.typing-indicator span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing-indicator span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-8px); opacity: 1; }
}

/* 输入区域 */
.input-area {
  padding: 16px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
  display: flex;
  gap: 12px;
  align-items: flex-end;
  background: var(--el-bg-color);
}

.input-area :deep(.el-textarea__inner) {
  font-family: inherit;
}

.send-btn {
  height: 40px;
  min-width: 80px;
}

/* 导入消息样式 */
.message-row.system {
  align-self: center;
  max-width: 90%;
}

.message-bubble.imported {
  background: linear-gradient(135deg, #f0f9eb 0%, #e1f3d8 100%);
  border: 1px solid #c2e7b0;
  border-bottom-left-radius: 4px;
}

.message-row.system .message-avatar {
  background: #e1f3d8;
}

/* 导入弹窗样式 */
.import-dialog-body {
  max-height: 60vh;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.import-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0;
}

.import-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.import-search {
  margin-bottom: 4px;
}

.import-loading {
  text-align: center;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.import-session-list {
  flex: 1;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  max-height: 400px;
}

.import-session-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  transition: background 0.2s;
}

.import-session-item:last-child {
  border-bottom: none;
}

.import-session-item:hover {
  background: var(--el-fill-color-light);
}

.import-session-item.selected {
  background: var(--el-color-primary-light-9);
}

.import-session-info {
  flex: 1;
  min-width: 0;
}

.import-session-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.import-msg-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.import-session-path {
  font-size: 13px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 4px;
}

.import-session-time {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}

.import-empty {
  text-align: center;
  padding: 30px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.import-footer {
  text-align: right;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding-top: 4px;
}
</style>
