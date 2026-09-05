<template>
  <div class="profiles-view">
    <div class="panel-header">
      <h2>用户画像库</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="primary" @click="openCreate()">
          <el-icon><Plus /></el-icon> 新增
        </el-button>
        <el-button type="success" @click="syncNow" :loading="syncing">
          <el-icon><Refresh /></el-icon> 同步记忆
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
        placeholder="搜索标题、内容..."
        clearable
        style="width: 280px"
        @input="handleFilterChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select
        v-model="filters.category"
        placeholder="按类别筛选"
        clearable
        style="width: 160px"
        @change="handleFilterChange"
      >
        <el-option label="偏好设置" value="preference" />
        <el-option label="行为模式" value="behavior" />
        <el-option label="技术栈" value="techstack" />
        <el-option label="工作习惯" value="workhabit" />
        <el-option label="其他" value="other" />
      </el-select>

      <el-button @click="clearFilters">
        <el-icon><Refresh /></el-icon> 重置
      </el-button>

      <div class="filter-stats">
        共 {{ filteredData.length }} 条记录
      </div>
    </div>

    <el-table :data="filteredData" stripe v-loading="loading">
      <el-table-column label="来源" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.sourceAgent" size="small" type="info">{{ row.sourceAgent }}</el-tag>
          <el-tag v-else size="small">手动</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="120">
        <template #default="{ row }">
          <el-tag :type="getCategoryTagType(row.category)">{{ getCategoryLabel(row.category) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="500">
        <template #default="{ row }">
          <div class="item-content" :title="getFullContent(row.items)">
            <div class="content-line">{{ getPreview(row.items) }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑用户画像' : '新增用户画像'" width="600px">
      <el-form :model="dialog.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="类别" prop="category">
          <el-select v-model="dialog.formData.category" placeholder="请选择类别">
            <el-option label="偏好设置" value="preference" />
            <el-option label="行为模式" value="behavior" />
            <el-option label="技术栈" value="techstack" />
            <el-option label="工作习惯" value="workhabit" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="items">
          <el-input v-model="itemsInput" type="textarea" :rows="4" placeholder='JSON格式，例如：[{"key": "语言", "value": "Python"}]' />
          <div style="font-size: 12px; color: #909399; margin-top: 4px;">必须是有效的JSON数组格式</div>
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
import { ref, reactive, computed, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download, Search, Refresh } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'
import { parseProfileContent } from '../utils/profileContent'

// 记忆同步状态
const syncing = ref(false)

// 组件卸载时清理对话框（防止遮罩残留阻塞菜单点击）
// 注意：卸载钩子里不应修改 reactive 状态（会干扰 Vue patch），改为标记由父级处理
onUnmounted(() => {
  // dialog.visible = false  // 移除此处赋值，避免卸载时改状态导致 patch 中断
})

// 手动同步 agent 记忆文件
const syncNow = async () => {
  syncing.value = true
  try {
    const res = await apiService.syncAll()
    const profiles = res.profiles || {}
    ElMessage.success(`同步完成：新增画像 ${profiles.totalSynced || 0} 条`)
  } catch {
    ElMessage.error('同步失败')
  } finally {
    syncing.value = false
    await loadData()
  }
}
import type { UserProfile } from '../types'

// 数据列表
const dataList = ref<UserProfile[]>([])
const loading = ref(false)

// 对话框状态
const dialog = reactive({
  visible: false,
  isEdit: false,
  formData: {} as Partial<UserProfile>
})

// JSON 输入
const itemsInput = ref('[{"key": "", "value": ""}]')

// 表单引用
const formRef = ref()

// 查询过滤器
const filters = reactive({
  searchText: '',
  category: ''
})

// 过滤后的数据
const filteredData = computed(() => {
  let result = dataList.value

  // 文本搜索
  if (filters.searchText) {
    const keyword = filters.searchText.toLowerCase()
    result = result.filter(item => {
      // 搜索标题
      if (item.title && item.title.toLowerCase().includes(keyword)) return true
      // 搜索内容项
      if (Array.isArray(item.items)) {
        return item.items.some((i: any) =>
          (i.key && i.key.toLowerCase().includes(keyword)) ||
          (i.value && i.value.toLowerCase().includes(keyword))
        )
      }
      return false
    })
  }

  // 类别过滤
  if (filters.category) {
    result = result.filter(item => item.category === filters.category)
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
  filters.category = ''
}

// 表单验证规则
const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  items: [{ required: true, message: '请输入内容（JSON格式）', trigger: 'blur' }]
}

// 类别映射
const getCategoryLabel = (category: string) => {
  const labels: Record<string, string> = {
    preference: '偏好设置',
    behavior: '行为模式',
    techstack: '技术栈',
    workhabit: '工作习惯',
    other: '其他'
  }
  return labels[category] || category
}

const getCategoryTagType = (category: string) => {
  const types: Record<string, string> = {
    preference: 'primary',
    behavior: 'success',
    techstack: 'warning',
    workhabit: 'info',
    other: 'info'
  }
  return types[category] || 'info'
}

// 格式化时间
const formatTime = (time: string | Date) => {
  if (!time) return ''
  const d = new Date(time)
  return d.toLocaleString('zh-CN')
}

// 解析画像内容，兼容数组、单个对象和字符串格式
const getPreview = (itemsStr: string | any[]) => {
  const full = parseProfileContent(itemsStr)
  const single = full.replace(/\s+/g, ' ')
  return single.length > 200 ? single.slice(0, 200) + '…' : single
}

// 完整内容（用于悬停提示）
const getFullContent = (itemsStr: string | any[]) => {
  return parseProfileContent(itemsStr)
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    dataList.value = await apiService.getProfiles()
  } catch (error: any) {
    ElMessage.error(`加载数据失败: ${error.displayMessage || error.message}`)
  } finally {
    loading.value = false
  }
}

// 打开新增对话框
const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', category: 'preference', items: [] }
  itemsInput.value = '[{"key": "", "value": ""}]'
  dialog.visible = true
}

// 打开编辑对话框
const openEdit = (row: UserProfile) => {
  dialog.isEdit = true
  dialog.formData = { ...row }
  itemsInput.value = typeof row.items === 'string' ? row.items : JSON.stringify(row.items)
  dialog.visible = true
}

// 提交表单
const handleSubmit = async () => {
  if (!formRef.value) return

  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    // 验证 JSON 格式
    try {
      JSON.parse(itemsInput.value)
    } catch {
      ElMessage.error('内容必须是有效的JSON格式')
      return
    }

    const data = { ...dialog.formData, items: JSON.parse(itemsInput.value) }

    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiService.updateProfile(dialog.formData.id, data)
        ElMessage.success('更新成功')
      } else {
        await apiService.createProfile(data)
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
const handleDelete = async (row: UserProfile) => {
  if (!row.id) return

  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await apiService.deleteProfile(row.id)
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
    const response = await fetch(`${API_BASE_URL}/profiles/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
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

// 暴露方法供父组件调用
defineExpose({ loadData })

// 初始化加载数据
loadData()
</script>

<style scoped>
.profiles-view {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}

.profiles-view :deep(.el-table) {
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

.item-tag {
  margin-right: 6px;
  margin-bottom: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  color: #fff;
}

.item-content {
  font-size: 13px;
  line-height: 1.5;
  color: #303133;
}

.content-line {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
