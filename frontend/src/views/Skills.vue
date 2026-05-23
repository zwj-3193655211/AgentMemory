<template>
  <div class="skills-view">
    <div class="panel-header">
      <h2>技能沉淀库</h2>
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
        placeholder="搜索标题、描述..."
        clearable
        style="width: 280px"
        @input="handleFilterChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select
        v-model="filters.skillType"
        placeholder="按类型筛选"
        clearable
        style="width: 160px"
        @change="handleFilterChange"
      >
        <el-option label="技术" value="technique" />
        <el-option label="方法" value="method" />
        <el-option label="工具" value="tool" />
        <el-option label="模式" value="pattern" />
        <el-option label="最佳实践" value="bestpractice" />
      </el-select>

      <el-input
        v-model="filters.tag"
        placeholder="搜索标签..."
        clearable
        style="width: 160px"
        @input="handleFilterChange"
      >
        <template #prefix><el-icon><PriceTag /></el-icon></template>
      </el-input>

      <el-button @click="clearFilters">
        <el-icon><Refresh /></el-icon> 重置
      </el-button>

      <div class="filter-stats">
        共 {{ filteredData.length }} 条记录
      </div>
    </div>

    <el-table :data="filteredData" stripe v-loading="loading">
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="skillType" label="类型" width="120">
        <template #default="{ row }">
          <el-tag :type="getSkillTypeTagType(row.skillType)">{{ getSkillTypeLabel(row.skillType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="300" show-overflow-tooltip />
      <el-table-column prop="tags" label="标签" width="200">
        <template #default="{ row }">
          <el-tag v-for="tag in parseTags(row.tags)" :key="tag" size="small" class="skill-tag">
            {{ tag }}
          </el-tag>
        </template>
      </el-table-column>
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
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑技能沉淀' : '新增技能沉淀'" width="600px">
      <el-form :model="dialog.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="技能类型" prop="skillType">
          <el-select v-model="dialog.formData.skillType" placeholder="请选择技能类型">
            <el-option label="技术" value="technique" />
            <el-option label="方法" value="method" />
            <el-option label="工具" value="tool" />
            <el-option label="模式" value="pattern" />
            <el-option label="最佳实践" value="bestpractice" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="dialog.formData.description" type="textarea" :rows="3" placeholder="请描述技能" />
        </el-form-item>
        <el-form-item label="步骤" prop="steps">
          <el-input v-model="dialog.formData.steps" type="textarea" :rows="4" placeholder="可选：详细步骤说明" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="tagsInput" placeholder="逗号分隔，如：debugging,performance" />
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
import { Plus, Download, Search, Refresh, PriceTag } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'
import type { Skill } from '../types'

// 数据列表
const dataList = ref<Skill[]>([])
const loading = ref(false)

// 对话框状态
const dialog = reactive({
  visible: false,
  isEdit: false,
  formData: {} as Partial<Skill>
})

// 标签输入
const tagsInput = ref('')

// 表单引用
const formRef = ref()

// 查询过滤器
const filters = reactive({
  searchText: '',
  skillType: '',
  tag: ''
})

// 过滤后的数据
const filteredData = computed(() => {
  let result = dataList.value

  // 文本搜索
  if (filters.searchText) {
    const keyword = filters.searchText.toLowerCase()
    result = result.filter(item =>
      (item.title && item.title.toLowerCase().includes(keyword)) ||
      (item.description && item.description.toLowerCase().includes(keyword)) ||
      (item.steps && item.steps.toLowerCase().includes(keyword))
    )
  }

  // 技能类型过滤
  if (filters.skillType) {
    result = result.filter(item => item.skillType === filters.skillType)
  }

  // 标签过滤
  if (filters.tag) {
    const tagKeyword = filters.tag.toLowerCase()
    result = result.filter(item => {
      if (Array.isArray(item.tags)) {
        return item.tags.some(t => t.toLowerCase().includes(tagKeyword))
      }
      return false
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
  filters.skillType = ''
  filters.tag = ''
}

// 解析标签
const parseTags = (tags: string | string[]) => {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string' && tags) {
    return tags.split(',').map(t => t.trim()).filter(t => t)
  }
  return []
}

// 表单验证规则
const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  skillType: [{ required: true, message: '请选择技能类型', trigger: 'change' }],
  description: [{ required: true, message: '请输入描述', trigger: 'blur' }]
}

// 技能类型映射
const getSkillTypeLabel = (type: string) => {
  const labels: Record<string, string> = {
    technique: '技术',
    method: '方法',
    tool: '工具',
    pattern: '模式',
    bestpractice: '最佳实践'
  }
  return labels[type] || type
}

const getSkillTypeTagType = (type: string) => {
  const types: Record<string, string> = {
    technique: 'primary',
    method: 'success',
    tool: 'warning',
    pattern: 'info',
    bestpractice: 'danger'
  }
  return types[type] || 'info'
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
    dataList.value = await apiService.getSkills()
  } catch (error: any) {
    ElMessage.error(`加载数据失败: ${error.message}`)
  } finally {
    loading.value = false
  }
}

// 打开新增对话框
const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', skillType: 'technique', description: '', steps: '', tags: [] }
  tagsInput.value = ''
  dialog.visible = true
}

// 打开编辑对话框
const openEdit = (row: Skill) => {
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

    const data = { ...dialog.formData }
    data.tags = tagsInput.value.split(',').map(t => t.trim()).filter(t => t)

    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiService.updateSkill(dialog.formData.id, data)
        ElMessage.success('更新成功')
      } else {
        await apiService.createSkill(data)
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
const handleDelete = async (row: Skill) => {
  if (!row.id) return

  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await apiService.deleteSkill(row.id)
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
    const response = await fetch(`${API_BASE_URL}/skills/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
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

// 暴露方法供父组件调用
defineExpose({ loadData })

// 初始化加载数据
loadData()
</script>

<style scoped>
.skills-view {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}

.skills-view :deep(.el-table) {
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

.skill-tag {
  margin-right: 4px;
  margin-bottom: 2px;
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
  border: none;
  color: #fff;
}
</style>
