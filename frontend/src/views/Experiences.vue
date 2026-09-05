<template>
  <div class="experiences-view">
    <div class="panel-header">
      <h2>实践经验</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="primary" @click="openCreate()">
          <el-icon><Plus /></el-icon> 新增
        </el-button>
        <el-button @click="exportData">
          <el-icon><Download /></el-icon> 导出
        </el-button>
      </div>
    </div>

    <!-- 类型 Tab -->
    <el-tabs v-model="activeType" @tab-change="handleTypeChange">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="最佳实践" name="best_practice" />
      <el-tab-pane label="错误纠正" name="error_correction" />
    </el-tabs>

    <!-- 查询工具栏 -->
    <div class="filter-toolbar">
      <el-input
        v-model="filters.searchText"
        placeholder="搜索标题、场景、做法..."
        clearable
        style="width: 280px"
        @input="handleFilterChange"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

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

    <!-- 数据表格 -->
    <el-table :data="filteredData" v-loading="loading" style="width: 100%">
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.type === 'error_correction' ? 'danger' : 'success'" size="small">
            {{ row.type === 'error_correction' ? '错误纠正' : '最佳实践' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="scenario" label="场景/问题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="practice" label="做法/解决" min-width="250" show-overflow-tooltip />
      <el-table-column label="标签" width="180">
        <template #default="{ row }">
          <el-tag v-for="t in parseTags(row.tags)" :key="t" size="small" style="margin-right: 4px">{{ t }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" >
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑实践经验' : '新增实践经验'" width="600px">
      <el-form ref="formRef" :model="dialog.formData" :rules="formRules" label-width="90px">
        <el-form-item label="类型">
          <el-radio-group v-model="dialog.formData.type">
            <el-radio value="best_practice">最佳实践</el-radio>
            <el-radio value="error_correction">错误纠正</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="简明扼要的标题" />
        </el-form-item>
        <el-form-item label="场景" prop="scenario">
          <el-input v-model="dialog.formData.scenario" type="textarea" :rows="3" placeholder="问题或使用场景描述" />
        </el-form-item>
        <el-form-item label="做法" prop="practice">
          <el-input v-model="dialog.formData.practice" type="textarea" :rows="4" placeholder="解决方案或实践做法（流程化、可执行）" />
        </el-form-item>
        <el-form-item label="原因" prop="rationale">
          <el-input v-model="dialog.formData.rationale" type="textarea" :rows="2" placeholder="原因或理由（可选）" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="tagsInput" placeholder="多个标签用逗号分隔" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download, Search, Refresh, PriceTag } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'

interface Experience {
  id?: string
  title: string
  type: string
  scenario: string
  practice: string
  rationale?: string
  tags?: string[] | string
  createdAt?: string
}

const dataList = ref<Experience[]>([])
const loading = ref(false)
const activeType = ref('all')

const dialog = reactive({
  visible: false,
  isEdit: false,
  formData: {} as Partial<Experience>
})

const tagsInput = ref('')
const formRef = ref()

const filters = reactive({
  searchText: '',
  tag: ''
})

const filteredData = computed(() => {
  let result = dataList.value

  if (filters.searchText) {
    const keyword = filters.searchText.toLowerCase()
    result = result.filter(item =>
      (item.title && item.title.toLowerCase().includes(keyword)) ||
      (item.scenario && item.scenario.toLowerCase().includes(keyword)) ||
      (item.practice && item.practice.toLowerCase().includes(keyword))
    )
  }

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

const handleFilterChange = () => {}
const clearFilters = () => {
  filters.searchText = ''
  filters.tag = ''
}

const parseTags = (tags: string | string[] | undefined) => {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string' && tags) {
    return tags.split(',').map(t => t.trim()).filter(t => t)
  }
  return []
}

const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  scenario: [{ required: true, message: '请描述场景', trigger: 'blur' }],
  practice: [{ required: true, message: '请提供做法', trigger: 'blur' }]
}

const formatTime = (time: string | Date) => {
  if (!time) return ''
  return new Date(time).toLocaleString('zh-CN')
}

const loadData = async () => {
  loading.value = true
  try {
    const type = activeType.value === 'all' ? undefined : activeType.value
    dataList.value = await apiService.getExperiences(type)
  } catch (error: any) {
    ElMessage.error(`加载数据失败: ${error.displayMessage || error.message}`)
  } finally {
    loading.value = false
  }
}

const handleTypeChange = () => {
  loadData()
}

const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', type: 'best_practice', scenario: '', practice: '', rationale: '', tags: [] }
  tagsInput.value = ''
  dialog.visible = true
}

const openEdit = (row: Experience) => {
  dialog.isEdit = true
  dialog.formData = { ...row, type: row.type || 'best_practice' }
  tagsInput.value = Array.isArray(row.tags) ? row.tags.join(', ') : ''
  dialog.visible = true
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    const data = { ...dialog.formData }
    data.tags = tagsInput.value.split(',').map(t => t.trim()).filter(t => t)

    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiService.updateExperience(dialog.formData.id, data)
        ElMessage.success('更新成功')
      } else {
        await apiService.createExperience(data)
        ElMessage.success('创建成功')
      }
      dialog.visible = false
      await loadData()
    } catch (error: any) {}
  })
}

const handleDelete = async (row: Experience) => {
  if (!row.id) return
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await apiService.deleteExperience(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    if (error !== 'cancel') {}
  }
}

const exportData = async () => {
  try {
    const type = activeType.value === 'all' ? '' : `?type=${activeType.value}`
    const response = await fetch(`${API_BASE_URL}/experiences${type}/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `experiences_${Date.now()}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    ElMessage.success('导出成功')
  } catch {
    ElMessage.error('导出失败')
  }
}

defineExpose({ loadData })
loadData()
</script>

<style scoped>
.experiences-view {
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
  color: #909399;
  font-size: 13px;
}
</style>
