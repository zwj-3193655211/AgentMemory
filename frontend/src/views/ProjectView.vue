<template>
  <div class="project-view">
    <!-- 顶部工具条 -->
    <div class="panel-header">
      <div class="title-area">
        <h2>项目会话</h2>
        <el-tag v-if="projects.length" size="small" type="info">{{ totalSessions }} 个会话 · {{ projects.length }} 个项目</el-tag>
      </div>
      <div class="actions">
        <el-button @click="exportAll">
          <el-icon><Download /></el-icon> 导出全部
        </el-button>
        <el-button type="success" @click="loadSessions" :loading="loading">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <div class="layout">
      <!-- 左侧：项目树（默认全折叠） -->
      <aside class="sidebar">
        <div class="sidebar-header">
          <span>项目列表</span>
          <el-button link size="small" @click="toggleAll">
            {{ allCollapsed ? '全部展开' : '全部折叠' }}
          </el-button>
        </div>

        <el-scrollbar class="sidebar-scroll">
          <el-collapse v-model="expandedProjects" v-loading="loading" class="project-collapse">
            <el-collapse-item
              v-for="proj in projects"
              :key="proj.name"
              :name="proj.name"
              class="project-item"
            >
              <template #title>
                <div class="project-title">
                  <el-icon><FolderOpened /></el-icon>
                  <span class="proj-name">{{ proj.name }}</span>
                  <el-tag size="small" round class="count-badge">{{ proj.sessions.length }}</el-tag>
                </div>
              </template>

              <div class="session-list">
                <div
                  v-for="s in proj.sessions"
                  :key="s.id"
                  class="session-entry"
                  :class="{ active: selectedSession?.id === s.id }"
                  @click="openSession(s)"
                >
                  <div class="session-entry-title">
                    <span v-if="s.title">{{ s.title }}</span>
                    <span v-else class="no-title" @click.stop="generateTitle(s)">
                      <el-icon><EditPen /></el-icon> 生成标题
                    </span>
                  </div>
                  <div class="session-entry-meta">
                    <el-tag size="small" class="agent-tag">{{ s.agentType }}</el-tag>
                    <span class="msg-count">{{ s.messageCount }} 条</span>
                  </div>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>

          <el-empty v-if="!loading && projects.length === 0" description="暂无会话" :image-size="60" />
        </el-scrollbar>
      </aside>

      <!-- 右侧：会话详情 -->
      <main class="detail">
        <template v-if="selectedSession">
          <div class="detail-header">
            <div class="detail-title">
              <h3>{{ selectedSession.title || '未命名会话' }}</h3>
              <div class="detail-meta">
                <el-tag size="small">{{ selectedSession.agentType }}</el-tag>
                <span class="meta-item">{{ formatTime(selectedSession.createdAt) }}</span>
                <span class="meta-item">{{ messages.length }} 条消息</span>
                <span v-if="selectedSession.projectPath" class="meta-item path">{{ selectedSession.projectPath }}</span>
              </div>
            </div>
            <div class="detail-actions">
              <el-button size="small" @click="exportOne(selectedSession)">
                <el-icon><Download /></el-icon> 导出
              </el-button>
            </div>
          </div>

          <el-scrollbar class="messages-scroll" v-loading="loadingMessages">
            <div class="messages">
              <div
                v-for="(m, idx) in messages"
                :key="idx"
                class="message"
                :class="m.role === 'user' ? 'msg-user' : 'msg-assistant'"
              >
                <div class="msg-bubble">
                  <div class="msg-role">
                    <el-tag :type="m.role === 'user' ? 'primary' : 'success'" size="small" effect="plain">
                      {{ m.role === 'user' ? '用户' : '助手' }}
                    </el-tag>
                    <span class="msg-time">{{ formatTime(m.timestamp) }}</span>
                  </div>
                  <pre class="msg-content">{{ m.content }}</pre>
                </div>
              </div>
              <el-empty v-if="!loadingMessages && messages.length === 0" description="暂无消息" :image-size="60" />
            </div>
          </el-scrollbar>
        </template>

        <el-empty v-else description="从左侧选择一个会话查看详情" :image-size="120" />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, FolderOpened, EditPen, Download } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'

interface SessionItem {
  id: string
  title?: string
  agentType: string
  projectPath?: string
  messageCount: number
  createdAt?: string
  generating?: boolean
}

const loading = ref(false)
const loadingMessages = ref(false)
const projects = ref<{ name: string; sessions: SessionItem[] }[]>([])
// 默认全部折叠
const expandedProjects = ref<string[]>([])
const selectedSession = ref<SessionItem | null>(null)
const messages = ref<any[]>([])

const totalSessions = computed(() => projects.value.reduce((acc, p) => acc + p.sessions.length, 0))
const allCollapsed = computed(() => expandedProjects.value.length === 0)

const formatTime = (time: string) => {
  if (!time) return ''
  return new Date(time).toLocaleString('zh-CN')
}

const extractProjectName = (path?: string) => {
  if (!path) return '（未指定项目）'
  const clean = path.replace(/[/\\]+$/, '')
  const parts = clean.split(/[/\\]/)
  return parts[parts.length - 1] || clean
}

const loadSessions = async () => {
  loading.value = true
  try {
    const sessions = await apiService.get<any[]>(`/sessions?limit=500`)
    const groups = new Map<string, SessionItem[]>()
    for (const s of sessions) {
      const name = extractProjectName(s.projectPath)
      if (!groups.has(name)) groups.set(name, [])
      groups.get(name)!.push({
        id: s.id,
        title: s.title,
        agentType: s.agentType,
        projectPath: s.projectPath,
        messageCount: s.messageCount || 0,
        createdAt: s.createdAt
      })
    }
    projects.value = Array.from(groups.entries()).map(([name, sess]) => ({
      name,
      sessions: sess
    })).sort((a, b) => b.sessions.length - a.sessions.length)
    // 保持折叠，除非之前选中了会话
    if (selectedSession.value) {
      const keep = projects.value.find(p => p.sessions.some(s => s.id === selectedSession.value!.id))
      if (keep) expandedProjects.value = [keep.name]
    }
  } catch (e) {
    ElMessage.error('加载会话失败')
  } finally {
    loading.value = false
  }
}

const toggleAll = () => {
  if (allCollapsed.value) {
    expandedProjects.value = projects.value.map(p => p.name)
  } else {
    expandedProjects.value = []
  }
}

const openSession = async (s: SessionItem) => {
  selectedSession.value = s
  // 确保所在项目展开
  const proj = projects.value.find(p => p.sessions.some(x => x.id === s.id))
  if (proj && !expandedProjects.value.includes(proj.name)) {
    expandedProjects.value = [proj.name]
  }
  loadingMessages.value = true
  messages.value = []
  try {
    const msgs = await apiService.get<any[]>(`/messages/${s.id}`)
    messages.value = msgs || []
  } catch (e: any) {
    ElMessage.error('加载消息失败: ' + (e.message || ''))
  } finally {
    loadingMessages.value = false
  }
}

const generateTitle = async (row: SessionItem) => {
  if (row.generating) return
  row.generating = true
  try {
    const res = await apiService.getSessionTitle(row.id)
    row.title = res.title
    if (selectedSession.value?.id === row.id) {
      selectedSession.value.title = res.title
    }
    ElMessage.success('标题已生成')
  } catch {
    ElMessage.error('标题生成失败')
  } finally {
    row.generating = false
  }
}

// 导出单个会话（消息 + 元数据）
const exportOne = async (s: SessionItem) => {
  try {
    const msgs = await apiService.get<any[]>(`/messages/${s.id}`)
    const data = {
      session: {
        id: s.id,
        title: s.title,
        agentType: s.agentType,
        projectPath: s.projectPath,
        createdAt: s.createdAt,
        messageCount: msgs.length
      },
      messages: msgs
    }
    downloadJson(data, `session_${s.id.slice(0, 8)}.json`)
    ElMessage.success('会话已导出')
  } catch {
    ElMessage.error('导出失败')
  }
}

// 导出全部会话
const exportAll = async () => {
  try {
    const response = await fetch(`${API_BASE_URL}/sessions/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `sessions_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('全部会话已导出')
  } catch {
    ElMessage.error('导出失败')
  }
}

const downloadJson = (data: any, filename: string) => {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
}

onMounted(loadSessions)
defineExpose({ loadSessions })
</script>

<style scoped>
.project-view {
  padding: 20px;
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-shrink: 0;
}
.title-area {
  display: flex;
  align-items: center;
  gap: 12px;
}
.title-area h2 {
  margin: 0;
}
.actions {
  display: flex;
  gap: 10px;
}

.layout {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

/* ===== 左侧项目树 ===== */
.sidebar {
  width: 340px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  font-weight: 600;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
}
.sidebar-scroll {
  flex: 1;
  min-height: 0;
}
.project-collapse {
  padding: 8px;
}
.project-collapse :deep(.el-collapse-item__header) {
  border-radius: 8px;
  padding: 0 10px;
  height: 42px;
  font-weight: 500;
}
.project-collapse :deep(.el-collapse-item__header.is-active) {
  background: #f5f7ff;
  color: #409eff;
}
.project-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}
.proj-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.count-badge {
  flex-shrink: 0;
}

.session-list {
  padding: 4px 8px 8px;
}
.session-entry {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  border: 1px solid transparent;
  margin-bottom: 4px;
}
.session-entry:hover {
  background: #f5f7fa;
}
.session-entry.active {
  background: #ecf5ff;
  border-color: #b3d8ff;
}
.session-entry-title {
  font-size: 13px;
  color: #303133;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.session-entry-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.agent-tag {
  font-size: 11px;
}
.msg-count {
  font-size: 11px;
  color: #909399;
}
.no-title {
  color: #409eff;
  font-size: 12px;
}

/* ===== 右侧详情 ===== */
.detail {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f2f5;
  flex-shrink: 0;
  gap: 12px;
}
.detail-title h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.detail-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.meta-item {
  font-size: 12px;
  color: #909399;
}
.meta-item.path {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
}

.messages-scroll {
  flex: 1;
  min-height: 0;
}
.messages {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.message {
  display: flex;
}
.msg-user {
  justify-content: flex-end;
}
.msg-assistant {
  justify-content: flex-start;
}
.msg-bubble {
  max-width: 78%;
  border-radius: 10px;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  background: #fff;
}
.msg-user .msg-bubble {
  background: #ecf5ff;
  border-color: #d9ecff;
}
.msg-role {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.msg-time {
  font-size: 11px;
  color: #909399;
}
.msg-content {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
</style>
