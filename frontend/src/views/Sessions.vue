<template>
  <div class="sessions-view">
    <div class="panel-header">
      <h2>会话库</h2>
      <el-button @click="loadSessions" :loading="loading">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>

    <div class="filter-toolbar">
      <el-input
        v-model="filterText"
        placeholder="搜索会话（ID / 项目 / Agent）"
        clearable
        style="width: 320px"
        @input="applyFilter"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="filterAgent" placeholder="按 Agent 筛选" clearable style="width: 180px" @change="applyFilter">
        <el-option v-for="a in agentOptions" :key="a" :label="a" :value="a" />
      </el-select>
      <div class="filter-stats">共 {{ filteredList.length }} / {{ allSessions.length }} 个会话</div>
    </div>

    <el-table :data="pagedList" v-loading="loading" stripe>
      <el-table-column label="会话" min-width="350">
        <template #default="{ row }">
          <div class="session-title">
            <span v-if="row.title">{{ row.title }}</span>
            <span v-else class="no-title" @click="generateTitle(row)">
              <el-icon><EditPen /></el-icon> 点击生成标题
            </span>
          </div>
          <div class="session-id">{{ row.id.slice(0, 8) }}…</div>
        </template>
      </el-table-column>
      <el-table-column label="Agent" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{ row.agentType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="projectPath" label="项目" min-width="200" show-overflow-tooltip />
      <el-table-column prop="messageCount" label="消息数" width="90" />
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="30"
      :total="filteredList.length"
      layout="prev, pager, next, total"
      style="margin-top: 16px; justify-content: flex-end"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Refresh, Search, EditPen } from '@element-plus/icons-vue'
import { apiService } from '../services/api'
import { ElMessage } from 'element-plus'

const allSessions = ref<any[]>([])
const loading = ref(false)
const filterText = ref('')
const filterAgent = ref('')
const page = ref(1)

const loadSessions = async () => {
  loading.value = true
  try {
    allSessions.value = await apiService.get<any[]>('/sessions?limit=500')
  } catch (e: any) {
    ElMessage.error('加载会话失败: ' + e.message)
  } finally {
    loading.value = false
  }
}

const agentOptions = computed(() => [...new Set(allSessions.value.map(s => s.agentType).filter(Boolean))])

const filteredList = ref<any[]>([])
const applyFilter = () => {
  const text = filterText.value.toLowerCase()
  filteredList.value = allSessions.value.filter(s => {
    if (filterAgent.value && s.agentType !== filterAgent.value) return false
    if (text) {
      const blob = `${s.id} ${s.projectPath || ''} ${s.agentType || ''} ${s.title || ''}`.toLowerCase()
      if (!blob.includes(text)) return false
    }
    return true
  })
  page.value = 1
}

const pagedList = computed(() => filteredList.value.slice((page.value - 1) * 30, page.value * 30))

const formatTime = (t: string) => t ? new Date(t).toLocaleString('zh-CN') : ''

const generateTitle = async (row: any) => {
  try {
    const res = await apiService.getSessionTitle(row.id)
    row.title = res.title
    applyFilter()
    ElMessage.success('已生成标题')
  } catch {
    ElMessage.error('生成失败')
  }
}

onMounted(() => {
  loadSessions().then(applyFilter)
})
</script>

<style scoped>
.sessions-view {
  padding: 20px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.filter-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filter-stats {
  margin-left: auto;
  font-size: 13px;
  color: #909399;
}
.session-title {
  font-weight: 500;
  color: #303133;
}
.no-title {
  color: #409eff;
  cursor: pointer;
  font-size: 13px;
}
.session-id {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  font-family: monospace;
}
</style>
