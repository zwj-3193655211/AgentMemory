<template>
  <div class="agents-view">
    <div class="panel-header">
      <h2>Agent 接入</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="success" @click="syncAll" :loading="syncingAll">
          <el-icon><Refresh /></el-icon> 全部同步
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="8" v-for="a in agents" :key="a.id" style="margin-bottom: 16px">
        <el-card shadow="hover">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <span class="agent-name">
                <el-icon><Cpu /></el-icon>
                {{ a.displayName || a.name }}
              </span>
              <el-tag :type="a.enabled ? 'success' : 'info'" size="small">
                {{ a.enabled ? '已启用' : '未启用' }}
              </el-tag>
            </div>
          </template>
          <div class="agent-info">
            <p><label>解析器:</label> {{ a.parserType || '-' }}</p>
            <p><label>日志路径:</label> <span class="path">{{ a.logBasePath || '-' }}</span></p>
            <p><label>CLI:</label> <span :class="a.cliExists ? '' : (a.cliPath ? 'missing' : 'neutral')">{{ a.cliExists ? '已找到' : (a.cliPath ? '已删除/不存在' : '无 CLI（桌面版）') }}</span></p>
          </div>
          <div style="margin-top: 12px; display: flex; gap: 8px">
            <el-button size="small" @click="syncAgent(a)" :loading="a.syncing">同步记忆</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="agents.length === 0" description="暂无 Agent 数据" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Cpu } from '@element-plus/icons-vue'
import { apiService } from '../services/api'

const agents = ref<any[]>([])
const syncingAll = ref(false)

const loadAgents = async () => {
  try {
    agents.value = await apiService.get<any[]>('/agents')
  } catch {
    ElMessage.error('加载 Agent 列表失败')
  }
}

const syncAll = async () => {
  syncingAll.value = true
  try {
    const res = await apiService.syncAll()
    const profiles = res.profiles || {}
    const sessions = res.sessions || {}
    ElMessage.success(
      `同步完成：画像 ${profiles.totalSynced || 0} 条` +
      (sessions.totalSessions ? `，会话 ${sessions.totalSessions} 个 / ${sessions.totalMessages} 条消息` : '')
    )
  } catch {
    ElMessage.error('同步失败')
  } finally {
    syncingAll.value = false
    await loadAgents()
  }
}

const syncAgent = async (agent: any) => {
  agent.syncing = true
  try {
    const res = await apiService.syncAgent(agent.name)
    ElMessage.success(`Agent ${agent.name} 同步成功`)
  } catch {
    ElMessage.error(`Agent ${agent.name} 同步失败`)
  } finally {
    agent.syncing = false
  }
}

onMounted(loadAgents)
defineExpose({ loadAgents })
</script>

<style scoped>
.agents-view {
  padding: 20px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.agent-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.agent-info p {
  margin: 6px 0;
  font-size: 13px;
  color: #606266;
}
.agent-info label {
  color: #909399;
  margin-right: 4px;
}
.agent-info .path {
  word-break: break-all;
}
.agent-info .missing {
  color: #f56c6c;
}
.agent-info .neutral {
  color: #909399;
}
</style>
