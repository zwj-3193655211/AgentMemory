<template>
  <div class="app-container">
    <!-- 顶部导航 -->
    <header class="app-header">
      <div class="logo">
        <el-icon :size="24"><Box /></el-icon>
        <span>AgentMemory</span>
      </div>
      
      <!-- 搜索框 -->
      <div class="search-box">
        <el-input
          v-model="searchQuery"
          placeholder="搜索..."
          :prefix-icon="Search"
          clearable
          @keyup.enter="handleSearch"
          style="width: 150px"
          size="small"
        />
      </div>

      <el-menu mode="horizontal" :default-active="activeMenu" @select="handleMenuSelect" class="main-menu">
        <el-menu-item index="dashboard">
          <el-tooltip content="仪表盘" placement="bottom">
            <el-icon><Odometer /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="sessions">
          <el-tooltip content="对话记录" placement="bottom">
            <el-icon><ChatDotRound /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="errors">
          <el-tooltip content="错误纠正" placement="bottom">
            <el-icon><WarningFilled /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="profiles">
          <el-tooltip content="用户画像" placement="bottom">
            <el-icon><User /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="practices">
          <el-tooltip content="实践经验" placement="bottom">
            <el-icon><DocumentChecked /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="contexts">
          <el-tooltip content="项目上下文" placement="bottom">
            <el-icon><FolderOpened /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="skills">
          <el-tooltip content="技能沉淀" placement="bottom">
            <el-icon><Reading /></el-icon>
          </el-tooltip>
        </el-menu-item>
        <el-menu-item index="settings">
          <el-tooltip content="设置" placement="bottom">
            <el-icon><Setting /></el-icon>
          </el-tooltip>
        </el-menu-item>
      </el-menu>
    </header>

    <!-- 主内容区 -->
    <main class="app-main">
      <!-- 仪表盘 -->
      <div v-if="activeMenu === 'dashboard'" class="content-panel full">
        <div class="panel-header">
          <h2>仪表盘</h2>
          <el-tag type="success">系统运行正常</el-tag>
        </div>

        <!-- 统计卡片 -->
        <div class="stats-grid">
          <el-card class="stat-card" @click="activeMenu = 'sessions'">
            <div class="stat-content">
              <div class="stat-icon sessions">
                <el-icon :size="32"><ChatDotRound /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.sessions }}</div>
                <div class="stat-label">会话总数</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'errors'">
            <div class="stat-content">
              <div class="stat-icon errors">
                <el-icon :size="32"><WarningFilled /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.errors }}</div>
                <div class="stat-label">错误纠正</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'practices'">
            <div class="stat-content">
              <div class="stat-icon practices">
                <el-icon :size="32"><DocumentChecked /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.practices }}</div>
                <div class="stat-label">实践经验</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'profiles'">
            <div class="stat-content">
              <div class="stat-icon profiles">
                <el-icon :size="32"><User /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.profiles }}</div>
                <div class="stat-label">用户画像</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'contexts'">
            <div class="stat-content">
              <div class="stat-icon contexts">
                <el-icon :size="32"><FolderOpened /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.contexts }}</div>
                <div class="stat-label">项目上下文</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'skills'">
            <div class="stat-content">
              <div class="stat-icon skills">
                <el-icon :size="32"><Reading /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.skills }}</div>
                <div class="stat-label">技能沉淀</div>
              </div>
            </div>
          </el-card>

          <el-card class="stat-card" @click="activeMenu = 'sessions'">
            <div class="stat-content">
              <div class="stat-icon messages">
                <el-icon :size="32"><Box /></el-icon>
              </div>
              <div class="stat-info">
                <div class="stat-number">{{ stats.messages.toLocaleString() }}</div>
                <div class="stat-label">消息总数</div>
              </div>
            </div>
          </el-card>
        </div>

        <!-- 最近活动 -->
        <div class="activity-section">
          <h3>最近会话</h3>
          <el-table :data="sessions.slice(0, 5)" stripe>
            <el-table-column prop="agentType" label="Agent" width="100">
              <template #default="{ row }">
                <el-tag :type="getAgentTagType(row.agentType)" size="small">{{ row.agentType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="projectPath" label="项目" show-overflow-tooltip />
            <el-table-column prop="messageCount" label="消息数" width="80" />
            <el-table-column prop="createdAt" label="创建时间" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <!-- 对话记录 -->
      <div v-if="activeMenu === 'sessions'" class="content-panel">
        <div class="panel-header">
          <h2>对话记录</h2>
          <div style="display: flex; gap: 10px;">
            <el-select v-model="selectedAgent" placeholder="选择 Agent" clearable style="width: 150px">
              <el-option v-for="agent in agents" :key="agent.name" :label="agent.name || agent.displayName" :value="agent.name" />
            </el-select>
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
      <div v-if="activeMenu === 'sessions' && selectedSession" class="detail-panel">
        <div class="panel-header">
          <h3>{{ selectedSession.id }}</h3>
          <div style="display: flex; gap: 10px;">
            <el-button @click="exportSingleSession(selectedSession.id)" size="small">
              <el-icon><Download /></el-icon> 导出
            </el-button>
            <el-button @click="selectedSession = null" text>
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
        </div>
        <div class="message-list">
          <div v-for="msg in filteredMessages" :key="msg.id" class="message-item" :class="msg.role">
            <div class="message-role">{{ msg.role === 'user' ? '用户' : 'AI' }}</div>
            <div class="message-content">{{ msg.content || '(工具调用)' }}</div>
          </div>
          <div v-if="filteredMessages.length === 0 && messages.length > 0" class="empty-hint">
            已过滤 {{ messages.length - filteredMessages.length }} 条工具调用记录
          </div>
        </div>
      </div>

      <!-- 错误纠正库 -->
      <div v-if="activeMenu === 'errors'" class="content-panel full">
        <div class="panel-header">
          <h2>错误纠正库</h2>
          <div style="display: flex; gap: 10px;">
            <el-button type="primary" @click="openErrorDialog">
              <el-icon><Plus /></el-icon> 新增
            </el-button>
            <el-button @click="exportErrors">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
        </div>
        <el-table :data="errors" stripe>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="problem" label="问题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="solution" label="解决方案" min-width="200" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="editError(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deleteError(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 用户画像 -->
      <div v-if="activeMenu === 'profiles'" class="content-panel full">
        <div class="panel-header">
          <h2>用户画像库</h2>
          <div style="display: flex; gap: 10px;">
            <el-button type="primary" @click="openProfileDialog">
              <el-icon><Plus /></el-icon> 新增
            </el-button>
            <el-button @click="exportProfiles">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
        </div>
        <el-table :data="profiles" stripe>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="category" label="类别" width="120" />
          <el-table-column prop="items" label="内容" min-width="300">
            <template #default="{ row }">
              <el-tag v-for="(item, idx) in parseItems(row.items)" :key="idx" class="item-tag">
                {{ item.key }}: {{ item.value }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="editProfile(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deleteProfile(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 实践经验 -->
      <div v-if="activeMenu === 'practices'" class="content-panel full">
        <div class="panel-header">
          <h2>实践经验库</h2>
          <div style="display: flex; gap: 10px;">
            <el-button type="primary" @click="openPracticeDialog">
              <el-icon><Plus /></el-icon> 新增
            </el-button>
            <el-button @click="exportPractices">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
        </div>
        <el-table :data="practices" stripe>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="scenario" label="场景" min-width="200" />
          <el-table-column prop="practice" label="实践" min-width="200" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="editPractice(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deletePractice(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 项目上下文 -->
      <div v-if="activeMenu === 'contexts'" class="content-panel full">
        <div class="panel-header">
          <h2>项目上下文库</h2>
          <div style="display: flex; gap: 10px;">
            <el-button type="primary" @click="openContextDialog">
              <el-icon><Plus /></el-icon> 新增
            </el-button>
            <el-button @click="exportContexts">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
        </div>
        <el-table :data="contexts" stripe>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="projectPath" label="项目路径" min-width="250" show-overflow-tooltip />
          <el-table-column prop="techStack" label="技术栈" min-width="200">
            <template #default="{ row }">
              <el-tag v-for="tech in row.techStack" :key="tech" size="small" class="tech-tag">{{ tech }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="180">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="editContext(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deleteContext(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 技能沉淀 -->
      <div v-if="activeMenu === 'skills'" class="content-panel full">
        <div class="panel-header">
          <h2>技能沉淀库</h2>
          <div style="display: flex; gap: 10px;">
            <el-button type="primary" @click="openSkillDialog">
              <el-icon><Plus /></el-icon> 新增
            </el-button>
            <el-button @click="exportSkills">
              <el-icon><Download /></el-icon> 导出
            </el-button>
          </div>
        </div>
        <el-table :data="skills" stripe>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="skillType" label="类型" width="120" />
          <el-table-column prop="description" label="描述" min-width="300" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="editSkill(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="deleteSkill(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      
      <!-- 搜索结果 -->
      <div v-if="activeMenu === 'search'" class="content-panel full">
        <div class="panel-header">
          <h2>搜索结果: "{{ searchQuery }}"</h2>
          <el-tag>{{ searchResults.length }} 条结果</el-tag>
        </div>
        <el-table :data="searchResults" stripe>
          <el-table-column prop="type" label="类型" width="120">
            <template #default="{ row }">
              <el-tag :type="getTypeTagType(row.type)">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="similarity" label="相似度" width="100">
            <template #default="{ row }">{{ (row.similarity * 100).toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
        </el-table>
      </div>
      
      <!-- 设置页面 -->
      <div v-if="activeMenu === 'settings'" class="content-panel full">
        <div class="panel-header">
          <h2>系统设置</h2>
        </div>
        
        <el-card class="setting-card">
          <template #header>
            <div class="card-header">
              <span>LLM 配置</span>
              <el-tag :type="llmConfig.mode === 'disabled' ? 'info' : 'success'">
                {{ llmConfig.mode === 'disabled' ? '规则模式' : llmConfig.mode === 'api' ? 'API模式' : '本地模式' }}
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
                    {{ preset.mode === 'api' ? preset.provider : '本地' }}
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
                <el-radio-button value="api">外部 API</el-radio-button>
                <el-radio-button value="local">本地模型</el-radio-button>
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
              
              <el-form-item label="API Key">
                <el-input v-model="llmConfig.apiKey" type="password" placeholder="sk-..." show-password style="width: 400px" @input="connectionTestSuccess = false" />
              </el-form-item>
              
              <el-form-item label="模型名称">
                <el-input v-model="llmConfig.model" placeholder="gpt-4o-mini" style="width: 300px" @input="connectionTestSuccess = false" />
              </el-form-item>
              
              <el-form-item>
                <el-button type="warning" @click="testLLMConnection" :loading="testingConnection" :disabled="!llmConfig.apiKey">
                  测试连接
                </el-button>
                <el-button type="primary" @click="saveLLMConfig" :loading="savingConfig" :disabled="!connectionTestSuccess">
                  保存配置
                </el-button>
                <el-button type="success" @click="showSavePresetDialog = true" :disabled="!llmConfig.apiKey">
                  保存为预设
                </el-button>
                <span v-if="!connectionTestSuccess && llmConfig.apiKey" class="form-tip" style="color: #e6a23c; margin-left: 8px;">请先测试连接</span>
              </el-form-item>
            </template>
            
            <template v-if="llmConfig.mode === 'local'">
              <el-form-item label="本地模型">
                <el-input v-model="llmConfig.localModel" placeholder="Qwen/Qwen3-0.6B" style="width: 400px" />
                <div class="form-tip">支持 HuggingFace 模型名称，首次加载需要下载</div>
              </el-form-item>
              
              <el-form-item>
                <el-button type="warning" @click="testLocalModel" :loading="testingConnection">测试模型</el-button>
                <el-button type="primary" @click="saveLLMConfig" :loading="savingConfig" :disabled="!connectionTestSuccess">保存配置</el-button>
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
        
        <el-card class="setting-card">
          <template #header>
            <span>服务状态</span>
          </template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="Embedding 服务">{{ embeddingStatus.status || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="Embedding 模型">{{ embeddingStatus.embedding_model || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="向量维度">{{ embeddingStatus.dimension || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="LLM 状态">{{ embeddingStatus.llm?.mode || '未知' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>
    </main>
    
    <!-- 保存预设对话框 -->
    <el-dialog v-model="showSavePresetDialog" title="保存配置预设" width="400px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="预设名称">
          <el-input v-model="newPresetName" placeholder="例如：我的DeepSeek" @keyup.enter="addPreset" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSavePresetDialog = false">取消</el-button>
        <el-button type="primary" @click="addPreset">保存</el-button>
      </template>
    </el-dialog>

    <!-- 错误纠正库对话框 -->
    <el-dialog v-model="errorDialogVisible" :title="errorIsEdit ? '编辑错误纠正' : '新增错误纠正'" width="600px">
      <el-form :model="errorFormData" :rules="errorRules" ref="errorFormRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="errorFormData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="问题描述" prop="problem">
          <el-input v-model="errorFormData.problem" type="textarea" :rows="3" placeholder="请描述问题" />
        </el-form-item>
        <el-form-item label="原因分析" prop="cause">
          <el-input v-model="errorFormData.cause" type="textarea" :rows="2" placeholder="请分析原因" />
        </el-form-item>
        <el-form-item label="解决方案" prop="solution">
          <el-input v-model="errorFormData.solution" type="textarea" :rows="3" placeholder="请提供解决方案" />
        </el-form-item>
        <el-form-item label="示例代码" prop="example">
          <el-input v-model="errorFormData.example" type="textarea" :rows="3" placeholder="可选：示例代码" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="errorFormData.tags" placeholder="逗号分隔，如：bug,fix,python" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="errorDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitErrorForm">{{ errorIsEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 实践经验库对话框 -->
    <el-dialog v-model="practiceDialogVisible" :title="practiceIsEdit ? '编辑实践经验' : '新增实践经验'" width="600px">
      <el-form :model="practiceFormData" :rules="practiceRules" ref="practiceFormRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="practiceFormData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="适用场景" prop="scenario">
          <el-input v-model="practiceFormData.scenario" type="textarea" :rows="2" placeholder="请描述适用场景" />
        </el-form-item>
        <el-form-item label="实践经验" prop="practice">
          <el-input v-model="practiceFormData.practice" type="textarea" :rows="3" placeholder="请提供实践经验" />
        </el-form-item>
        <el-form-item label="原理说明" prop="rationale">
          <el-input v-model="practiceFormData.rationale" type="textarea" :rows="2" placeholder="可选：原理说明" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="practiceFormData.tags" placeholder="逗号分隔，如：performance,optimization" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="practiceDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPracticeForm">{{ practiceIsEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 用户画像库对话框 -->
    <el-dialog v-model="profileDialogVisible" :title="profileIsEdit ? '编辑用户画像' : '新增用户画像'" width="600px">
      <el-form :model="profileFormData" :rules="profileRules" ref="profileFormRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="profileFormData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="类别" prop="category">
          <el-select v-model="profileFormData.category" placeholder="请选择类别">
            <el-option label="偏好设置" value="preference" />
            <el-option label="行为模式" value="behavior" />
            <el-option label="技术栈" value="techstack" />
            <el-option label="工作习惯" value="workhabit" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="items">
          <el-input v-model="profileFormData.items" type="textarea" :rows="4" placeholder='JSON格式，例如：[{"key": "语言", "value": "Python"}]' />
          <div style="font-size: 12px; color: #909399; margin-top: 4px;">必须是有效的JSON数组格式</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProfileForm">{{ profileIsEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 项目上下文库对话框 -->
    <el-dialog v-model="contextDialogVisible" :title="contextIsEdit ? '编辑项目上下文' : '新增项目上下文'" width="600px">
      <el-form :model="contextFormData" :rules="contextRules" ref="contextFormRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="contextFormData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="项目路径" prop="projectPath">
          <el-input v-model="contextFormData.projectPath" placeholder="例如：/home/user/project" />
        </el-form-item>
        <el-form-item label="技术栈" prop="techStack">
          <el-input v-model="contextFormData.techStack" placeholder="逗号分隔，如：React,TypeScript,Node.js" />
        </el-form-item>
        <el-form-item label="关键决策" prop="keyDecisions">
          <el-input v-model="contextFormData.keyDecisions" type="textarea" :rows="2" placeholder="可选：项目中的关键决策" />
        </el-form-item>
        <el-form-item label="项目结构" prop="structure">
          <el-input v-model="contextFormData.structure" type="textarea" :rows="3" placeholder="可选：项目结构说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contextDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitContextForm">{{ contextIsEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 技能沉淀库对话框 -->
    <el-dialog v-model="skillDialogVisible" :title="skillIsEdit ? '编辑技能沉淀' : '新增技能沉淀'" width="600px">
      <el-form :model="skillFormData" :rules="skillRules" ref="skillFormRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="skillFormData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="技能类型" prop="skillType">
          <el-select v-model="skillFormData.skillType" placeholder="请选择技能类型">
            <el-option label="技术" value="technique" />
            <el-option label="方法" value="method" />
            <el-option label="工具" value="tool" />
            <el-option label="模式" value="pattern" />
            <el-option label="最佳实践" value="bestpractice" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="skillFormData.description" type="textarea" :rows="3" placeholder="请描述技能" />
        </el-form-item>
        <el-form-item label="步骤" prop="steps">
          <el-input v-model="skillFormData.steps" type="textarea" :rows="4" placeholder="可选：详细步骤说明" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="skillFormData.tags" placeholder="逗号分隔，如：debugging,performance" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skillDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSkillForm">{{ skillIsEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Setting, ChatDotRound, WarningFilled, User, DocumentChecked, FolderOpened, Reading, Odometer, Box, Delete, Plus, Download, Close } from '@element-plus/icons-vue'

const API_BASE = 'http://localhost:8080/api'
const EMBED_BASE = 'http://localhost:8100'

// 数据
const activeMenu = ref('dashboard')
const agents = ref<any[]>([])
const sessions = ref<any[]>([])
const messages = ref<any[]>([])
const errors = ref<any[]>([])
const profiles = ref<any[]>([])
const practices = ref<any[]>([])
const contexts = ref<any[]>([])
const skills = ref<any[]>([])
const stats = ref({ sessions: 0, messages: 0, errors: 0, profiles: 0, practices: 0, contexts: 0, skills: 0 })
const selectedAgent = ref('')
const selectedSession = ref<any>(null)

// 错误纠正库 CRUD 状态
const errorDialogVisible = ref(false)
const errorIsEdit = ref(false)
const errorFormData = ref<any>({})
const errorFormRef = ref()
const errorRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  problem: [{ required: true, message: '请描述问题', trigger: 'blur' }],
  solution: [{ required: true, message: '请提供解决方案', trigger: 'blur' }]
}

// 实践经验库 CRUD 状态
const practiceDialogVisible = ref(false)
const practiceIsEdit = ref(false)
const practiceFormData = ref<any>({})
const practiceFormRef = ref()
const practiceRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  scenario: [{ required: true, message: '请描述场景', trigger: 'blur' }],
  practice: [{ required: true, message: '请提供实践经验', trigger: 'blur' }]
}

// 用户画像库 CRUD 状态
const profileDialogVisible = ref(false)
const profileIsEdit = ref(false)
const profileFormData = ref<any>({})
const profileFormRef = ref()
const profileRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  items: [{ required: true, message: '请输入内容（JSON格式）', trigger: 'blur' }]
}

// 项目上下文库 CRUD 状态
const contextDialogVisible = ref(false)
const contextIsEdit = ref(false)
const contextFormData = ref<any>({})
const contextFormRef = ref()
const contextRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  projectPath: [{ required: true, message: '请输入项目路径', trigger: 'blur' }]
}

// 技能沉淀库 CRUD 状态
const skillDialogVisible = ref(false)
const skillIsEdit = ref(false)
const skillFormData = ref<any>({})
const skillFormRef = ref()
const skillRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  skillType: [{ required: true, message: '请选择技能类型', trigger: 'change' }],
  description: [{ required: true, message: '请输入描述', trigger: 'blur' }]
}

// 搜索
const searchQuery = ref('')
const searchResults = ref<any[]>([])
const searching = ref(false)

// LLM 配置
const llmConfig = ref({
  mode: 'disabled',
  provider: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  model: 'gpt-4o-mini',
  localModel: 'Qwen/Qwen3-0.6B'
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

// 加载预设
const loadPresets = () => {
  try {
    const saved = localStorage.getItem('llmPresets')
    if (saved) {
      llmPresets.value = JSON.parse(saved)
    }
  } catch (e) {
    console.error('加载预设失败', e)
  }
}

// 保存预设到 localStorage
const savePresets = () => {
  localStorage.setItem('llmPresets', JSON.stringify(llmPresets.value))
}

// 添加新预设
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

// 应用预设
const applyPreset = async (preset: LLMPreset) => {
  llmConfig.value.mode = preset.mode
  llmConfig.value.provider = preset.provider
  llmConfig.value.baseUrl = preset.baseUrl
  llmConfig.value.apiKey = preset.apiKey
  llmConfig.value.model = preset.model
  llmConfig.value.localModel = preset.localModel
  connectionTestSuccess.value = false
  connectionTestResult.value = ''
  
  // 自动保存到服务端
  await saveLLMConfig()
}

// 删除预设
const deletePreset = (id: string) => {
  llmPresets.value = llmPresets.value.filter(p => p.id !== id)
  savePresets()
}

// 清理配置
const autoCleanup = ref(false)
const cleanupDays = ref(30)
const cleaningUp = ref(false)

// 提供商预设
const providerPresets: Record<string, { baseUrl: string; model: string }> = {
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'qwen3:0.6b' }
}

// 计算属性
// Agent名称映射：将agent表中的name映射到session表中的agentType
const agentNameMapping: Record<string, string> = {
  'Claude Code': 'claude',
  'iFlow CLI': 'iflow',
  'Nanobot': 'nanobot',
  'OpenClaw': 'openclaw',
  'Qoder CLI': 'qoder',
  'Qwen CLI': 'qwen'
}

const filteredSessions = computed(() => {
  if (!selectedAgent.value) return sessions.value
  // 将选中的agent名称转换为agentType格式
  const targetType = agentNameMapping[selectedAgent.value] || selectedAgent.value.toLowerCase().replace(' ', '').replace('cli', '')
  return sessions.value.filter(s => s.agentType === targetType)
})

// 过滤空消息（工具调用等）
const filteredMessages = computed(() => {
  return messages.value.filter(m => m.content && m.content.trim().length > 0)
})

// 方法
const handleMenuSelect = (index: string) => {
  activeMenu.value = index
  selectedSession.value = null
}

const selectSession = async (session: any) => {
  selectedSession.value = session
  try {
    const res = await axios.get(`${API_BASE}/messages/${session.id}`)
    messages.value = res.data
  } catch (e) {
    console.error('加载消息失败', e)
  }
}

const getAgentTagType = (type: string) => {
  const types: Record<string, string> = {
    iflow: 'primary',
    claude: 'success',
    qwen: 'warning',
    qoder: 'danger',
    openclaw: 'info',
    nanobot: ''
  }
  return types[type] || ''
}

const formatTime = (time: string | Date) => {
  if (!time) return ''
  const d = new Date(time)
  return d.toLocaleString('zh-CN')
}

const parseItems = (itemsStr: string) => {
  try {
    return JSON.parse(itemsStr || '[]')
  } catch {
    return []
  }
}

// 统一加载数据（避免重复请求）
const loadAllData = async () => {
  try {
    const [agentsRes, sessionsRes, statsRes, errorsRes, profilesRes, practicesRes, contextsRes, skillsRes] = await Promise.all([
      axios.get(`${API_BASE}/agents`),
      axios.get(`${API_BASE}/sessions`),
      axios.get(`${API_BASE}/stats`),
      axios.get(`${API_BASE}/errors`),
      axios.get(`${API_BASE}/profiles`),
      axios.get(`${API_BASE}/practices`),
      axios.get(`${API_BASE}/contexts`),
      axios.get(`${API_BASE}/skills`)
    ])
    agents.value = agentsRes.data
    sessions.value = sessionsRes.data
    stats.value = statsRes.data
    errors.value = errorsRes.data
    profiles.value = profilesRes.data
    practices.value = practicesRes.data
    contexts.value = contextsRes.data
    skills.value = skillsRes.data
  } catch (e) {
    console.error('加载数据失败', e)
  }
}

// 保留单独加载方法供特殊情况使用
const loadData = async () => {
  try {
    const [agentsRes, sessionsRes, statsRes] = await Promise.all([
      axios.get(`${API_BASE}/agents`),
      axios.get(`${API_BASE}/sessions`),
      axios.get(`${API_BASE}/stats`)
    ])
    agents.value = agentsRes.data
    sessions.value = sessionsRes.data
    stats.value = statsRes.data
  } catch (e) {
    console.error('加载数据失败', e)
  }
}

const loadMemoryData = async () => {
  try {
    const [errorsRes, profilesRes, practicesRes, contextsRes, skillsRes] = await Promise.all([
      axios.get(`${API_BASE}/errors`),
      axios.get(`${API_BASE}/profiles`),
      axios.get(`${API_BASE}/practices`),
      axios.get(`${API_BASE}/contexts`),
      axios.get(`${API_BASE}/skills`)
    ])
    errors.value = errorsRes.data
    profiles.value = profilesRes.data
    practices.value = practicesRes.data
    contexts.value = contextsRes.data
    skills.value = skillsRes.data
  } catch (e) {
    console.error('加载记忆库数据失败', e)
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

const getTypeTagType = (type: string) => {
  const types: Record<string, string> = {
    ERROR_CORRECTION: 'danger',
    USER_PROFILE: 'primary',
    BEST_PRACTICE: 'success',
    PROJECT_CONTEXT: 'warning',
    SKILL: 'info'
  }
  return types[type] || ''
}

// LLM 配置
const loadEmbeddingStatus = async () => {
  try {
    const res = await axios.get(`${EMBED_BASE}/health`)
    embeddingStatus.value = res.data
    
    // 同步 LLM 配置
    if (res.data.llm) {
      llmConfig.value.mode = res.data.llm.mode || 'disabled'
      if (res.data.llm.mode === 'api') {
        llmConfig.value.provider = res.data.llm.provider || 'openai'
        llmConfig.value.model = res.data.llm.model || 'gpt-4o-mini'
        llmConfig.value.baseUrl = res.data.llm.base || ''
        llmConfig.value.apiKey = '' // 不返回 key
      } else if (res.data.llm.mode === 'local') {
        llmConfig.value.localModel = res.data.llm.model || ''
      }
    }
  } catch (e) {
    console.error('获取 Embedding 服务状态失败', e)
  }
}

const onProviderChange = (provider: string) => {
  const preset = providerPresets[provider]
  if (preset) {
    llmConfig.value.baseUrl = preset.baseUrl
    llmConfig.value.model = preset.model
  }
}

const saveLLMConfig = async () => {
  savingConfig.value = true
  try {
    await axios.post(`${EMBED_BASE}/config`, {
      llm_mode: llmConfig.value.mode,
      llm_api_provider: llmConfig.value.provider,
      llm_api_base: llmConfig.value.baseUrl,
      llm_api_key: llmConfig.value.apiKey,
      llm_api_model: llmConfig.value.model,
      llm_local_model: llmConfig.value.localModel
    })
    
    // 重新加载状态
    await loadEmbeddingStatus()
    connectionTestResult.value = '配置已保存'
    connectionTestSuccess.value = true
  } catch (e) {
    console.error('保存配置失败', e)
    connectionTestResult.value = '保存配置失败'
    connectionTestSuccess.value = false
  } finally {
    savingConfig.value = false
  }
}

const testLLMConnection = async () => {
  testingConnection.value = true
  connectionTestResult.value = ''
  
  try {
    // 临时配置到服务端进行测试
    await axios.post(`${EMBED_BASE}/config`, {
      llm_mode: 'api',
      llm_api_provider: llmConfig.value.provider,
      llm_api_base: llmConfig.value.baseUrl,
      llm_api_key: llmConfig.value.apiKey,
      llm_api_model: llmConfig.value.model
    })
    
    // 测试提取
    const res = await axios.post(`${EMBED_BASE}/extract`, {
      content: '这是一个测试：解决了 Python 导入错误，原因是缺少模块，安装 pip install xxx 后解决了。'
    })
    
    if (res.data.type && res.data.type !== 'SKIP') {
      connectionTestResult.value = `连接成功！提取类型: ${res.data.type}，点击"保存配置"生效`
      connectionTestSuccess.value = true
    } else {
      connectionTestResult.value = `连接成功，但提取结果为空，可能是模型响应问题`
      connectionTestSuccess.value = true
    }
  } catch (e: any) {
    const detail = e.response?.data?.detail || e.message || '未知错误'
    connectionTestResult.value = `连接失败: ${detail}`
    connectionTestSuccess.value = false
    // 测试失败，恢复之前的配置
    await loadEmbeddingStatus()
  } finally {
    testingConnection.value = false
  }
}

const updateLLMConfig = () => {
  // 切换模式时重置测试状态
  connectionTestSuccess.value = false
  connectionTestResult.value = ''
}

const testLocalModel = async () => {
  testingConnection.value = true
  connectionTestResult.value = ''
  
  try {
    // 先保存配置
    await axios.post(`${EMBED_BASE}/config`, {
      llm_mode: 'local',
      llm_local_model: llmConfig.value.localModel
    })
    
    // 测试提取
    const res = await axios.post(`${EMBED_BASE}/extract`, {
      content: '这是一个测试：成功解决了 Python 导入错误。'
    })
    
    if (res.data.type && res.data.type !== 'SKIP') {
      connectionTestResult.value = `模型加载成功！提取类型: ${res.data.type}`
      connectionTestSuccess.value = true
    } else {
      connectionTestResult.value = `模型已加载，规则模式下提取: ${res.data.type || 'SKIP'}`
      connectionTestSuccess.value = true
    }
  } catch (e: any) {
    connectionTestResult.value = `模型加载失败: ${e.response?.data?.detail || e.message || '未知错误'}`
    connectionTestSuccess.value = false
  } finally {
    testingConnection.value = false
  }
}

const saveCleanupConfig = async () => {
  try {
    // 保存到后端配置（这里简化处理，实际应该调用后端API）
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
    // 重新加载数据 - 使用统一加载方法
    await loadAllData()
  } catch (e: any) {
    connectionTestResult.value = `清理失败: ${e.response?.data?.error || e.message || '未知错误'}`
    connectionTestSuccess.value = false
  } finally {
    cleaningUp.value = false
  }
}

// ===== 错误纠正库 CRUD 方法 =====

// 打开新增对话框
const openErrorDialog = () => {
  errorIsEdit.value = false
  errorFormData.value = { title: '', problem: '', cause: '', solution: '', example: '', tags: '' }
  errorDialogVisible.value = true
}

// 打开编辑对话框
const editError = (row: any) => {
  errorIsEdit.value = true
  errorFormData.value = { ...row }
  if (Array.isArray(errorFormData.value.tags)) {
    errorFormData.value.tags = errorFormData.value.tags.join(', ')
  }
  errorDialogVisible.value = true
}

// 提交表单
const submitErrorForm = async () => {
  if (!errorFormRef.value) return
  await errorFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 处理tags字段
    const data = { ...errorFormData.value }
    if (typeof data.tags === 'string') {
      data.tags = data.tags.split(',').map((t: string) => t.trim()).filter((t: string) => t)
    }

    try {
      if (errorIsEdit.value) {
        await axios.put(`${API_BASE}/errors/${data.id}`, data)
        ElMessage.success('更新成功')
      } else {
        await axios.post(`${API_BASE}/errors`, data)
        ElMessage.success('创建成功')
      }
      errorDialogVisible.value = false
      await loadAllData()
    } catch (error: any) {
      ElMessage.error(error.response?.data?.message || '操作失败')
    }
  })
}

// 删除记录
const deleteError = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await axios.delete(`${API_BASE}/errors/${row.id}`)
    ElMessage.success('删除成功')
    await loadAllData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 导出所有
const exportErrors = async () => {
  try {
    const res = await axios.get(`${API_BASE}/errors/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `errors_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

// ===== 实践经验库 CRUD 方法 =====

// 打开新增对话框
const openPracticeDialog = () => {
  practiceIsEdit.value = false
  practiceFormData.value = { title: '', scenario: '', practice: '', rationale: '', tags: '' }
  practiceDialogVisible.value = true
}

// 打开编辑对话框
const editPractice = (row: any) => {
  practiceIsEdit.value = true
  practiceFormData.value = { ...row }
  if (Array.isArray(practiceFormData.value.tags)) {
    practiceFormData.value.tags = practiceFormData.value.tags.join(', ')
  }
  practiceDialogVisible.value = true
}

// 提交表单
const submitPracticeForm = async () => {
  if (!practiceFormRef.value) return
  await practiceFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 处理tags字段
    const data = { ...practiceFormData.value }
    if (typeof data.tags === 'string') {
      data.tags = data.tags.split(',').map((t: string) => t.trim()).filter((t: string) => t)
    }

    try {
      if (practiceIsEdit.value) {
        await axios.put(`${API_BASE}/practices/${data.id}`, data)
        ElMessage.success('更新成功')
      } else {
        await axios.post(`${API_BASE}/practices`, data)
        ElMessage.success('创建成功')
      }
      practiceDialogVisible.value = false
      await loadAllData()
    } catch (error: any) {
      ElMessage.error(error.response?.data?.message || '操作失败')
    }
  })
}

// 删除记录
const deletePractice = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await axios.delete(`${API_BASE}/practices/${row.id}`)
    ElMessage.success('删除成功')
    await loadAllData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 导出所有
const exportPractices = async () => {
  try {
    const res = await axios.get(`${API_BASE}/practices/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `practices_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

// ===== 用户画像库 CRUD 方法 =====

// 打开新增对话框
const openProfileDialog = () => {
  profileIsEdit.value = false
  profileFormData.value = { title: '', category: 'preference', items: '[{"key": "", "value": ""}]' }
  profileDialogVisible.value = true
}

// 打开编辑对话框
const editProfile = (row: any) => {
  profileIsEdit.value = true
  profileFormData.value = { ...row }
  profileDialogVisible.value = true
}

// 提交表单
const submitProfileForm = async () => {
  if (!profileFormRef.value) return
  await profileFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 验证JSON格式
    try {
      JSON.parse(profileFormData.value.items)
    } catch (e) {
      ElMessage.error('内容必须是有效的JSON格式')
      return
    }

    const data = { ...profileFormData.value }

    try {
      if (profileIsEdit.value) {
        await axios.put(`${API_BASE}/profiles/${data.id}`, data)
        ElMessage.success('更新成功')
      } else {
        await axios.post(`${API_BASE}/profiles`, data)
        ElMessage.success('创建成功')
      }
      profileDialogVisible.value = false
      await loadAllData()
    } catch (error: any) {
      ElMessage.error(error.response?.data?.message || '操作失败')
    }
  })
}

// 删除记录
const deleteProfile = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await axios.delete(`${API_BASE}/profiles/${row.id}`)
    ElMessage.success('删除成功')
    await loadAllData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 导出所有
const exportProfiles = async () => {
  try {
    const res = await axios.get(`${API_BASE}/profiles/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `profiles_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

// ===== 项目上下文库 CRUD 方法 =====

// 打开新增对话框
const openContextDialog = () => {
  contextIsEdit.value = false
  contextFormData.value = { title: '', projectPath: '', techStack: '', keyDecisions: '', structure: '' }
  contextDialogVisible.value = true
}

// 打开编辑对话框
const editContext = (row: any) => {
  contextIsEdit.value = true
  contextFormData.value = { ...row }
  if (Array.isArray(contextFormData.value.techStack)) {
    contextFormData.value.techStack = contextFormData.value.techStack.join(', ')
  }
  contextDialogVisible.value = true
}

// 提交表单
const submitContextForm = async () => {
  if (!contextFormRef.value) return
  await contextFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 处理techStack字段
    const data = { ...contextFormData.value }
    if (typeof data.techStack === 'string') {
      data.techStack = data.techStack.split(',').map((t: string) => t.trim()).filter((t: string) => t)
    }

    try {
      if (contextIsEdit.value) {
        await axios.put(`${API_BASE}/contexts/${data.id}`, data)
        ElMessage.success('更新成功')
      } else {
        await axios.post(`${API_BASE}/contexts`, data)
        ElMessage.success('创建成功')
      }
      contextDialogVisible.value = false
      await loadAllData()
    } catch (error: any) {
      ElMessage.error(error.response?.data?.message || '操作失败')
    }
  })
}

// 删除记录
const deleteContext = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await axios.delete(`${API_BASE}/contexts/${row.id}`)
    ElMessage.success('删除成功')
    await loadAllData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 导出所有
const exportContexts = async () => {
  try {
    const res = await axios.get(`${API_BASE}/contexts/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `contexts_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

// ===== 技能沉淀库 CRUD 方法 =====

// 打开新增对话框
const openSkillDialog = () => {
  skillIsEdit.value = false
  skillFormData.value = { title: '', skillType: 'technique', description: '', steps: '', tags: '' }
  skillDialogVisible.value = true
}

// 打开编辑对话框
const editSkill = (row: any) => {
  skillIsEdit.value = true
  skillFormData.value = { ...row }
  if (Array.isArray(skillFormData.value.tags)) {
    skillFormData.value.tags = skillFormData.value.tags.join(', ')
  }
  skillDialogVisible.value = true
}

// 提交表单
const submitSkillForm = async () => {
  if (!skillFormRef.value) return
  await skillFormRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 处理tags字段
    const data = { ...skillFormData.value }
    if (typeof data.tags === 'string') {
      data.tags = data.tags.split(',').map((t: string) => t.trim()).filter((t: string) => t)
    }

    try {
      if (skillIsEdit.value) {
        await axios.put(`${API_BASE}/skills/${data.id}`, data)
        ElMessage.success('更新成功')
      } else {
        await axios.post(`${API_BASE}/skills`, data)
        ElMessage.success('创建成功')
      }
      skillDialogVisible.value = false
      await loadAllData()
    } catch (error: any) {
      ElMessage.error(error.response?.data?.message || '操作失败')
    }
  })
}

// 删除记录
const deleteSkill = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await axios.delete(`${API_BASE}/skills/${row.id}`)
    ElMessage.success('删除成功')
    await loadAllData()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 导出所有
const exportSkills = async () => {
  try {
    const res = await axios.get(`${API_BASE}/skills/export`, { responseType: 'blob' })
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = `skills_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

// ===== 对话记录导出方法 =====

// 导出所有对话记录
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

// 导出单个对话记录（包含消息）
const exportSingleSession = async (sessionId: string) => {
  try {
    // 获取会话信息
    const sessionRes = await axios.get(`${API_BASE}/sessions`)
    const session = sessionRes.data.find((s: any) => s.id === sessionId)
    if (!session) {
      ElMessage.error('会话不存在')
      return
    }

    // 获取消息
    const messagesRes = await axios.get(`${API_BASE}/messages/${sessionId}`)

    // 组合导出数据
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
  } catch (error) {
    ElMessage.error('导出失败')
  }
}

onMounted(() => {
  // 使用统一加载方法，减少API调用次数
  loadAllData()
  loadEmbeddingStatus()
  loadPresets()
  // 加载清理配置
  const savedAutoCleanup = localStorage.getItem('autoCleanup')
  const savedCleanupDays = localStorage.getItem('cleanupDays')
  if (savedAutoCleanup) autoCleanup.value = savedAutoCleanup === 'true'
  if (savedCleanupDays) cleanupDays.value = parseInt(savedCleanupDays) || 30
})
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  max-width: 1400px;
  margin: 0 auto;
  background: #f5f7fa;
}

.app-header {
  display: flex;
  align-items: center;
  padding: 0 16px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  position: sticky;
  top: 0;
  z-index: 100;
  gap: 16px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: bold;
  color: #409eff;
  flex-shrink: 0;
}

.search-box {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.stats {
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.main-menu {
  flex: 1;
  border-bottom: none;
}

.main-menu .el-menu-item {
  padding: 0 8px;
  height: 50px;
  line-height: 50px;
}

.main-menu .el-menu-item .el-icon {
  font-size: 18px;
}

.app-main {
  display: flex;
  padding: 24px;
  gap: 24px;
}

.content-panel {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.content-panel.full {
  width: 100%;
}

.detail-panel {
  width: 400px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  padding: 24px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.panel-header h2, .panel-header h3 {
  margin: 0;
  font-size: 18px;
}

.session-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.session-card {
  cursor: pointer;
  transition: all 0.3s;
}

.session-card:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}

.session-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.session-time {
  font-size: 12px;
  color: #909399;
}

.session-project {
  font-size: 14px;
  color: #606266;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-count {
  font-size: 12px;
  color: #909399;
}

.message-list {
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

.message-item {
  padding: 12px;
  margin-bottom: 12px;
  border-radius: 8px;
}

.message-item.user {
  background: #ecf5ff;
}

.message-item.assistant {
  background: #f0f9eb;
}

.message-role {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.message-content {
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-all;
}

.item-tag {
  margin: 2px;
}

.tech-tag {
  margin: 2px;
}

.setting-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.empty-hint {
  text-align: center;
  color: #909399;
  font-size: 12px;
  padding: 20px;
}

.preset-section {
  margin-bottom: 20px;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.preset-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.preset-title {
  font-weight: 500;
  color: #303133;
}

.preset-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 12px;
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
  padding: 12px 16px;
  background: white;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.preset-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.preset-name {
  font-weight: 500;
  color: #303133;
  min-width: 100px;
}

.preset-model {
  font-size: 12px;
  color: #909399;
}

/* 仪表盘样式 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  cursor: pointer;
  transition: all 0.3s;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.stat-icon.sessions {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.stat-icon.messages {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.stat-icon.errors {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
}

.stat-icon.practices {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
}

.stat-icon.profiles {
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
}

.stat-icon.contexts {
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
}

.stat-icon.skills {
  background: linear-gradient(135deg, #30cfd0 0%, #330867 100%);
}

.stat-icon.agents {
  background: linear-gradient(135deg, #a8edea 0%, #fed6e3 100%);
}

.stat-info {
  flex: 1;
}

.stat-number {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
  line-height: 1;
}

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}

.activity-section {
  margin-top: 24px;
}

.activity-section h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
</style>
