<template>
  <div class="app-container">
    <!-- 初始化向导 -->
    <Setup v-if="showSetup" @complete="handleSetupComplete" />

    <!-- 主界面 -->
    <div v-show="!showSetup">
    <!-- 顶部导航 -->
    <header class="app-header">
      <div class="app-header-inner">
        <div class="logo">
          <el-icon :size="24"><Box /></el-icon>
          <span>AgentMemory</span>
        </div>
        
        <!-- 搜索框 -->
        <div class="search-box">
          <el-input
            v-model="searchQuery"
            placeholder="搜索记忆..."
            :prefix-icon="SearchIcon"
            clearable
            @keyup.enter="handleSearch"
            style="width: 220px"
            size="default"
            round
          />
        </div>

        <!-- 实时连接状态 -->
        <el-tooltip :content="sseConnected ? '实时更新已连接' : '实时更新未连接'" placement="bottom">
          <div class="sse-indicator" :class="{ connected: sseConnected }">
            <span class="sse-dot"></span>
            <span class="sse-label">实时</span>
          </div>
        </el-tooltip>

        <el-menu mode="horizontal" v-model:default-active="activeMenu" @select="handleMenuSelect" class="main-menu" :ellipsis="false">
          <el-menu-item index="dashboard">
            <el-tooltip content="仪表盘" placement="bottom">
              <el-icon><Odometer /></el-icon>
            </el-tooltip>
            <span class="menu-label">仪表盘</span>
          </el-menu-item>
          <el-menu-item index="experiences">
            <el-tooltip content="实践经验" placement="bottom">
              <el-icon><WarningFilled /></el-icon>
            </el-tooltip>
            <span class="menu-label">实践经验</span>
          </el-menu-item>
          <el-menu-item index="profiles">
            <el-tooltip content="用户画像" placement="bottom">
              <el-icon><User /></el-icon>
            </el-tooltip>
            <span class="menu-label">用户画像</span>
          </el-menu-item>
          <el-menu-item index="projects">
            <el-tooltip content="项目会话" placement="bottom">
              <el-icon><FolderOpened /></el-icon>
            </el-tooltip>
            <span class="menu-label">项目会话</span>
          </el-menu-item>
          <el-menu-item index="skills">
            <el-tooltip content="技能沉淀" placement="bottom">
              <el-icon><Reading /></el-icon>
            </el-tooltip>
            <span class="menu-label">技能沉淀</span>
          </el-menu-item>
          <el-menu-item index="agents">
            <el-tooltip content="Agent 接入" placement="bottom">
              <el-icon><Box /></el-icon>
            </el-tooltip>
            <span class="menu-label">Agent 接入</span>
          </el-menu-item>
          <el-menu-item index="sessions">
            <el-tooltip content="会话库" placement="bottom">
              <el-icon><ChatDotRound /></el-icon>
            </el-tooltip>
            <span class="menu-label">会话库</span>
          </el-menu-item>
          <el-menu-item index="compression">
            <el-tooltip content="会话摘要" placement="bottom">
              <el-icon><Connection /></el-icon>
            </el-tooltip>
            <span class="menu-label">会话摘要</span>
          </el-menu-item>
          <el-menu-item index="settings" class="settings-menu-item">
            <el-tooltip content="设置" placement="bottom">
              <el-icon><Setting /></el-icon>
            </el-tooltip>
            <span class="menu-label">设置</span>
          </el-menu-item>
        </el-menu>
      </div>
    </header>

    <!-- 主内容区 -->
    <main class="app-main">
      <!-- 仪表盘 -->
      <Dashboard
        v-if="activeMenu === 'dashboard'"
        :stats="stats"
        @navigate="handleMenuSelect"
      />

      <!-- 实践经验（合并错误纠正 + 最佳实践） -->
      <Experiences v-if="activeMenu === 'experiences'" ref="experiencesRef" />

      <!-- 用户画像 -->
      <Profiles v-if="activeMenu === 'profiles'" ref="profilesRef" />

      <!-- 项目会话（按项目分组） -->
      <ProjectView v-if="activeMenu === 'projects'" ref="projectViewRef" />

      <!-- 技能沉淀 -->
      <Skills v-if="activeMenu === 'skills'" ref="skillsRef" />

      <!-- Agent 接入 -->
      <Agents v-if="activeMenu === 'agents'" ref="agentsRef" />

      <!-- 会话库 -->
      <SessionsView v-if="activeMenu === 'sessions'" ref="sessionsRef" />

      <!-- 搜索结果 -->
      <Search
        v-if="activeMenu === 'search'"
        :search-query="searchQuery"
        :search-results="searchResults"
        :searching="searching"
      />
      
      <!-- 会话摘要页面 -->
      <Compression v-if="activeMenu === 'compression'" />

      <!-- 设置页面 -->
      <Settings v-if="activeMenu === 'settings'" />
    </main>

    <!-- 添加自定义 Agent 对话框 -->
    <el-dialog v-model="showAddAgentDialog" title="添加自定义 Agent" width="450px">
      <el-form :model="newAgent" label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="newAgent.name" placeholder="如: MyAgent" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="newAgent.displayName" placeholder="如: 我的 Agent" />
        </el-form-item>
        <el-form-item label="日志路径">
          <el-input v-model="newAgent.logBasePath" placeholder="如: ~/.myagent/sessions" />
        </el-form-item>
        <el-form-item label="解析器类型">
          <el-select v-model="newAgent.parserType" placeholder="选择日志格式" style="width: 100%">
            <el-option label="OpenClaw 格式" value="openclaw" />
            <el-option label="Claude Code 格式" value="claude" />
            <el-option label="iFlow CLI 格式" value="iflow" />
            <el-option label="Qwen/Qoder 格式" value="qwen" />
            <el-option label="Codex 格式" value="codex" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用监控">
          <el-switch v-model="newAgent.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddAgentDialog = false">取消</el-button>
        <el-button type="primary" @click="addCustomAgent" :loading="addingAgent">添加</el-button>
      </template>
    </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { sseService } from './services/sse'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Search as SearchIcon, Setting, WarningFilled, User, FolderOpened, Reading, Odometer, Box, Connection, ChatDotRound } from '@element-plus/icons-vue'

// 导入记忆库组件
import Experiences from './views/Experiences.vue'
import Profiles from './views/Profiles.vue'
import SessionsView from './views/Sessions.vue'
import ProjectView from './views/ProjectView.vue'
import Skills from './views/Skills.vue'
import Agents from './views/Agents.vue'
import Setup from './views/Setup.vue'
import Dashboard from './views/Dashboard.vue'
import Search from './views/Search.vue'
import Compression from './views/Compression.vue'
import Settings from './views/Settings.vue'

// 使用 Vite 环境变量，支持运行时配置
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'

// 数据
const activeMenu = ref('dashboard')
const sseConnected = ref(false)
const showSetup = ref(localStorage.getItem('agentmemory_setup_done') !== 'true')
const agents = ref<any[]>([])
const profiles = ref<any[]>([])
const skills = ref<any[]>([])
const stats = ref({ sessions: 0, messages: 0, errors: 0, profiles: 0, practices: 0, contexts: 0, skills: 0, dailySessions: [] as any[], dailyMessages: [] as any[], agentDistribution: [] as any[], memoryDistribution: [] as any[] })

// 搜索
const searchQuery = ref('')
const searchResults = ref<any[]>([])
const searching = ref(false)

// 自定义 Agent 状态
const showAddAgentDialog = ref(false)
const addingAgent = ref(false)
const newAgent = ref({
  name: '',
  displayName: '',
  logBasePath: '',
  parserType: 'openclaw',
  enabled: true
})


// 方法
const handleMenuSelect = (index: string) => {
  activeMenu.value = index
  // 防御性：同步到 localStorage 防止偶发状态丢失
  try { localStorage.setItem('agentmemory_activeMenu', index) } catch {}
}

// 初始化时恢复上次的菜单
try {
  const saved = localStorage.getItem('agentmemory_activeMenu')
  if (saved) activeMenu.value = saved
} catch {}

// 添加自定义 Agent
const addCustomAgent = async () => {
  if (!newAgent.value.name) {
    ElMessage.warning('请输入 Agent 名称')
    return
  }
  addingAgent.value = true
  try {
    await axios.post(`${API_BASE}/agents`, newAgent.value)
    ElMessage.success('添加成功')
    showAddAgentDialog.value = false
    newAgent.value = { name: '', displayName: '', logBasePath: '', parserType: 'openclaw', enabled: true }
    await loadAllData()
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || '添加失败')
  } finally {
    addingAgent.value = false
  }
}

// 统一加载数据（避免重复请求）
const loadAllData = async () => {
  try {
    const [agentsRes, statsRes, profilesRes, skillsRes] = await Promise.all([
      axios.get(`${API_BASE}/agents`),
      axios.get(`${API_BASE}/stats`),
      axios.get(`${API_BASE}/profiles`),
      axios.get(`${API_BASE}/skills`)
    ])
    agents.value = agentsRes.data
    stats.value = statsRes.data
    profiles.value = profilesRes.data
    skills.value = skillsRes.data
  } catch (e) {
    console.error('加载数据失败', e)
  }
}

// 仅刷新统计数据（用于 SSE 实时更新，轻量级）
const loadStatsOnly = async () => {
  try {
    const res = await axios.get(`${API_BASE}/stats`)
    stats.value = res.data
  } catch (e) {
    console.error('刷新统计失败', e)
  }
}

// 搜索功能
const handleSearch = async () => {
  if (!searchQuery.value.trim()) return
  
  searching.value = true
  activeMenu.value = 'search'
  
  try {
    const res = await axios.post(`${API_BASE}/search`, {
      query: searchQuery.value,
      limit: 20
    })
    searchResults.value = res.data
  } catch (e) {
    console.error('搜索失败', e)
  } finally {
    searching.value = false
  }
}

// ===== 对话记录导出方法 =====

// 导出所有对话记录

// 初始化完成处理
const handleSetupComplete = () => {
  showSetup.value = false
  localStorage.setItem('agentmemory_setup_done', 'true')
  loadAllData()
}

onMounted(async () => {
  // 建立 SSE 实时连接，新消息时自动刷新统计
  sseService.on('stats_update', loadStatsOnly)
  sseService.onStatusChange((connected) => { sseConnected.value = connected })
  sseService.connect()

  // 使用统一加载方法，减少API调用次数
  loadAllData()
})

onUnmounted(() => {
  // 断开 SSE 连接
  sseService.disconnect()
})
</script>

<style scoped>
/* 实时连接状态指示 */
.sse-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 12px;
  background: #f5f7fa;
  cursor: default;
  flex-shrink: 0;
}

.sse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  transition: background 0.3s;
}

.sse-indicator.connected .sse-dot {
  background: #67c23a;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.6);
  animation: sse-pulse 2s infinite;
}

@keyframes sse-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.sse-label {
  font-size: 12px;
  color: #909399;
}

.sse-indicator.connected .sse-label {
  color: #67c23a;
}

/* 整体布局 */
.app-container {
  min-height: 100vh;
  background: linear-gradient(180deg, #f5f7fa 0%, #e4e8f0 100%);
}

/* 顶部导航 */
.app-header {
  background: linear-gradient(135deg, #ffffff 0%, #f8fafc 100%);
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
  position: sticky;
  top: 0;
  z-index: 100;
}

.app-header-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 24px;
  height: 60px;
}

.app-main {
  display: flex;
  padding: 24px;
  gap: 24px;
  max-width: 1600px;
  margin: 0 auto;
  box-sizing: border-box;
}

/* Logo */
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  font-weight: 700;
  color: #409eff;
  flex-shrink: 0;
  padding: 8px 0;
}

.logo .el-icon {
  font-size: 22px;
}

/* 搜索框 */
.search-box {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

/* 主菜单 */
.main-menu {
  flex: 1;
  border-bottom: none;
  justify-content: flex-start;
  background: transparent;
}

.main-menu .el-menu-item {
  padding: 0 10px;
  height: 60px;
  line-height: 60px;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.3s ease;
}

.main-menu .el-menu-item:hover {
  background: rgba(64, 158, 255, 0.08);
  color: #409eff;
}

.main-menu .el-menu-item .el-icon {
  font-size: 16px;
}

.menu-label {
  font-size: 12px;
  font-weight: 500;
}

.settings-menu-item {
  margin-left: auto;
}

/* 内容面板 */
.content-panel {
  flex: 1;
  min-width: 0;
  background: #ffffff;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}

.content-panel.full {
  width: 100%;
}

/* 面板头部 */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f2f5;
}

.panel-header h2, .panel-header h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a1a2e;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .app-main {
    padding: 16px;
    gap: 16px;
  }

  .content-panel {
    padding: 16px;
  }

  .menu-label {
    display: none;
  }
}

@media (max-width: 768px) {
  .app-header-inner {
    padding: 0 12px;
    gap: 12px;
  }

  .logo span {
    display: none;
  }

  .search-box {
    flex: 1;
    max-width: 200px;
  }

  .search-box :deep(.el-input) {
    width: 100% !important;
  }

  .main-menu {
    overflow-x: auto;
  }

  .main-menu .el-menu-item {
    padding: 0 12px;
  }

  .settings-menu-item {
    margin-left: 0;
  }

  .app-main {
    flex-direction: column;
    padding: 12px;
  }
}

@media (max-width: 480px) {
  .stats-grid {
    grid-template-columns: 1fr !important;
  }

  .panel-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
