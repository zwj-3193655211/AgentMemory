<template>
  <div class="errors-view">
    <div class="panel-header">
      <h2>错误纠正库</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="primary" @click="openCreate()">
          <el-icon><Plus /></el-icon> 新增
        </el-button>
        <el-button @click="exportData">
          <el-icon><Download /></el-icon> 导出
        </el-button>
      </div>
    </div>

    <!-- 查询工具栏 -->
    <div class="filter-toolbar">
      <el-input
        v-model="filters.searchText"
        placeholder="搜索标题、问题、解决方案..."
        clearable
        style="width: 280px"
        @input="handleFilterChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select
        v-model="filters.agentType"
        placeholder="按Agent筛选"
        clearable
        style="width: 160px"
        @change="handleFilterChange"
      >
        <el-option label="Claude Code" value="claude" />
        <el-option label="iFlow" value="iflow" />
        <el-option label="Qwen" value="qwen" />
        <el-option label="Qoder" value="qoder" />
        <el-option label="OpenClaw" value="openclaw" />
        <el-option label="Codex CLI" value="codex" />
        <el-option label="Crush CLI" value="crush" />
        <el-option label="WorkBuddy" value="workbuddy" />
      </el-select>

      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 260px"
        @change="handleFilterChange"
      />

      <el-button @click="clearFilters">
        <el-icon><Refresh /></el-icon> 重置
      </el-button>

      <div class="filter-stats">
        共 {{ filteredData.length }} 条记录
      </div>
    </div>

    <el-table :data="filteredData" stripe v-loading="loading">
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="problem" label="问题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="agentType" label="Agent" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="getAgentTagType(row.agentType)">{{ row.agentType || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="solution" label="解决方案" min-width="200" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑错误纠正' : '新增错误纠正'" width="600px">
      <el-form :model="dialog.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="问题描述" prop="problem">
          <el-input v-model="dialog.formData.problem" type="textarea" :rows="3" placeholder="请描述问题" />
        </el-form-item>
        <el-form-item label="原因分析" prop="cause">
          <el-input v-model="dialog.formData.cause" type="textarea" :rows="2" placeholder="请分析原因" />
        </el-form-item>
        <el-form-item label="解决方案" prop="solution">
          <el-input v-model="dialog.formData.solution" type="textarea" :rows="3" placeholder="请提供解决方案" />
        </el-form-item>
        <el-form-item label="示例代码" prop="example">
          <el-input v-model="dialog.formData.example" type="textarea" :rows="3" placeholder="可选：示例代码" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="tagsInput" placeholder="逗号分隔，如：bug,fix,python" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">{{ dialog.isEdit ? '更新' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download, Search, Refresh } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'
import type { ErrorCorrection } from '../types'

// 数据列表
const dataList = ref<ErrorCorrection[]>([])
const loading = ref(false)

// 对话框状态
const dialog = reactive({
  visible: false,
  isEdit: false,
  formData: {} as Partial<ErrorCorrection>
})

// 标签输入（字符串格式）
const tagsInput = ref('')

// 表单引用
const formRef = ref()

// 查询过滤器
const filters = reactive({
  searchText: '',
  agentType: '',
  dateRange: [] as string[]
})

// 过滤后的数据
const filteredData = computed(() => {
  let result = dataList.value

  // 文本搜索
  if (filters.searchText) {
    const keyword = filters.searchText.toLowerCase()
    result = result.filter(item =>
      (item.title && item.title.toLowerCase().includes(keyword)) ||
      (item.problem && item.problem.toLowerCase().includes(keyword)) ||
      (item.solution && item.solution.toLowerCase().includes(keyword)) ||
      (item.cause && item.cause.toLowerCase().includes(keyword))
    )
  }

  // Agent类型过滤
  if (filters.agentType) {
    result = result.filter(item => item.agentType === filters.agentType)
  }

  // 日期范围过滤
  if (filters.dateRange && filters.dateRange.length === 2) {
    const [startDate, endDate] = filters.dateRange
    result = result.filter(item => {
      if (!item.createdAt) return false
      const date = new Date(item.createdAt).toISOString().split('T')[0]
      return date >= startDate && date <= endDate
    })
  }

  return result
})

// 处理过滤变化
const handleFilterChange = () => {
  // 使用 computed 自动处理
}

// 清空过滤器
const clearFilters = () => {
  filters.searchText = ''
  filters.agentType = ''
  filters.dateRange = []
}

// Agent类型标签颜色
const getAgentTagType = (type: string) => {
  const types: Record<string, string> = {
    iflow: 'primary',
    claude: 'success',
    qwen: 'warning',
    qoder: 'danger',
    openclaw: 'info',
    codex: 'primary',
    crush: 'danger',
    workbuddy: 'primary'
  }
  return types[type] || 'info'
}

// 表单验证规则
const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  problem: [{ required: true, message: '请描述问题', trigger: 'blur' }],
  solution: [{ required: true, message: '请提供解决方案', trigger: 'blur' }]
}

// 格式化时间
const formatTime = (time: string | Date) => {
  if (!time) return ''
  const d = new Date(time)
  return d.toLocaleString('zh-CN')
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    dataList.value = await apiService.getErrors()
  } catch (error: any) {
    ElMessage.error(`加载数据失败: ${error.message}`)
  } finally {
    loading.value = false
  }
}

// 打开新增对话框
const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', problem: '', cause: '', solution: '', example: '', tags: [] }
  tagsInput.value = ''
  dialog.visible = true
}

// 打开编辑对话框
const openEdit = (row: ErrorCorrection) => {
  dialog.isEdit = true
  dialog.formData = { ...row }
  tagsInput.value = Array.isArray(row.tags) ? row.tags.join(', ') : ''
  dialog.visible = true
}

// 提交表单
const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 处理标签
    const data = { ...dialog.formData }
    data.tags = tagsInput.value.split(',').map(t => t.trim()).filter(t => t)

    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiService.updateError(dialog.formData.id, data)
        ElMessage.success('更新成功')
      } else {
        await apiService.createError(data)
        ElMessage.success('创建成功')
      }
      dialog.visible = false
      await loadData()
    } catch (error: any) {
      // 错误已在 ApiService 中处理
    }
  })
}

// 删除记录
const handleDelete = async (row: ErrorCorrection) => {
  if (!row.id) return

  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await apiService.deleteError(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    if (error !== 'cancel') {
      // 错误已在 ApiService 中处理
    }
  }
}

// 导出数据
const exportData = async () => {
  try {
    const response = await fetch(`${API_BASE_URL}/errors/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
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

// 暴露方法供父组件调用
defineExpose({ loadData })

// 初始化加载数据
loadData()
</script>

<style scoped>
.errors-view {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}

.errors-view :deep(.el-table) {
  width: 100% !important;
  table-layout: fixed;
}

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

.filter-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding: 16px;
  background: #f8f9fb;
  border-radius: 10px;
  flex-wrap: wrap;
}

.filter-stats {
  margin-left: auto;
  font-size: 13px;
  color: #606266;
  background: #fff;
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid #e8eaed;
}
</style>
