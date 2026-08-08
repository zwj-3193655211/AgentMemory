<template>
  <div class="project-view">
    <div class="panel-header">
      <h2>项目会话</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="success" @click="loadSessions">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </div>

    <!-- 按项目分组的会话树 -->
    <el-collapse v-model="expandedProjects" v-loading="loading">
      <el-collapse-item v-for="proj in projects" :key="proj.name" :name="proj.name">
        <template #title>
          <span class="project-name">
            <el-icon><FolderOpened /></el-icon>
            {{ proj.name || '（未指定项目）' }}
            <el-tag size="small" style="margin-left: 8px">{{ proj.sessions.length }}</el-tag>
          </span>
        </template>

        <el-table :data="proj.sessions" size="small" style="width: 100%">
          <el-table-column label="标题" min-width="300">
            <template #default="{ row }">
              <span v-if="row.title">{{ row.title }}</span>
              <span v-else class="no-title" @click="generateTitle(row)">
                <el-icon><EditPen /></el-icon> {{ row.generating ? '生成中...' : '点击生成标题' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="agentType" label="Agent" width="110">
            <template #default="{ row }">
              <el-tag size="small">{{ row.agentType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="messageCount" label="消息数" width="80" />
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <el-empty v-if="!loading && projects.length === 0" description="暂无会话" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, FolderOpened, EditPen } from '@element-plus/icons-vue'
import { apiService } from '../services/api'

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
const projects = ref<{ name: string; sessions: SessionItem[] }[]>([])
const expandedProjects = ref<string[]>([])

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
    expandedProjects.value = projects.value.slice(0, 5).map(p => p.name)
  } catch (e) {
    ElMessage.error('加载会话失败')
  } finally {
    loading.value = false
  }
}

const generateTitle = async (row: SessionItem) => {
  if (row.generating) return
  row.generating = true
  try {
    const res = await apiService.getSessionTitle(row.id)
    row.title = res.title
    ElMessage.success('标题已生成')
  } catch {
    ElMessage.error('标题生成失败')
  } finally {
    row.generating = false
  }
}

onMounted(loadSessions)
defineExpose({ loadSessions })
</script>

<style scoped>
.project-view {
  padding: 20px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.project-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}
.no-title {
  color: #409eff;
  cursor: pointer;
  font-size: 13px;
}
</style>
