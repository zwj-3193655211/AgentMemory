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

    <!-- 技能库 / 候选 Tab -->
    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <el-tab-pane label="技能库" name="approved" />
      <el-tab-pane name="pending">
        <template #label>
          技能候选
          <el-badge v-if="pendingCount > 0" :value="pendingCount" class="pending-badge" />
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 技能库列表 -->
    <template v-if="activeTab === 'approved'">

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
        <el-option label="库与API参考" value="library-api" />
        <el-option label="数据分析" value="data-analysis" />
        <el-option label="故障排查" value="troubleshooting" />
        <el-option label="代码脚手架" value="scaffold" />
        <el-option label="流程自动化" value="automation" />
        <el-option label="代码审查" value="code-review" />
        <el-option label="产品验证" value="product-verify" />
        <el-option label="CI/CD" value="cicd" />
        <el-option label="基础设施运维" value="infra-ops" />
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
      <el-table-column prop="skillType" label="类型" width="140">
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
      <el-table-column label="操作" width="150" >
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    </template>

    <!-- 技能候选列表（LLM 提取待人工确认） -->
    <template v-else>
      <el-empty v-if="pendingList.length === 0" description="暂无待确认的技能候选" />
      <el-card v-for="item in pendingList" :key="item.id" shadow="hover" class="pending-card">
        <template #header>
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span class="pending-title">{{ item.title }}</span>
            <div>
              <el-tag size="small" style="margin-right: 8px">{{ item.skillType || '未分类' }}</el-tag>
              <el-tag size="small" type="warning">LLM 提取</el-tag>
            </div>
          </div>
        </template>
        <p class="pending-desc">{{ item.description }}</p>
        <div v-if="item.steps && item.steps !== '[]'" class="pending-steps">
          <div class="steps-title">步骤：</div>
          <pre>{{ formatSteps(item.steps) }}</pre>
        </div>
        <div class="pending-tags">
          <el-tag v-for="t in parseTags(item.tags)" :key="t" size="small" class="skill-tag">{{ t }}</el-tag>
        </div>
        <div class="pending-actions">
          <el-button type="primary" size="small" @click="approveSkill(item)">
            <el-icon><Check /></el-icon> 确认入库
          </el-button>
          <el-button type="danger" size="small" @click="rejectSkill(item)">忽略</el-button>
        </div>
      </el-card>
    </template>

    <!-- 对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑技能沉淀' : '新增技能沉淀'" width="600px">
      <el-form :model="dialog.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="技能类型" prop="skillType">
          <el-select v-model="dialog.formData.skillType" placeholder="请选择技能类型">
            <el-option label="库与API参考" value="library-api" />
            <el-option label="数据分析" value="data-analysis" />
            <el-option label="故障排查" value="troubleshooting" />
            <el-option label="代码脚手架" value="scaffold" />
            <el-option label="流程自动化" value="automation" />
            <el-option label="代码审查" value="code-review" />
            <el-option label="产品验证" value="product-verify" />
            <el-option label="CI/CD" value="cicd" />
            <el-option label="基础设施运维" value="infra-ops" />
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
import { Plus, Download, Search, Refresh, PriceTag, Check } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'
import type { Skill, SkillType } from '../types'

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
  skillType: '' as SkillType | '',
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
    'library-api': '库与API参考',
    'data-analysis': '数据分析',
    'troubleshooting': '故障排查',
    'scaffold': '代码脚手架',
    'automation': '流程自动化',
    'code-review': '代码审查',
    'product-verify': '产品验证',
    'cicd': 'CI/CD',
    'infra-ops': '基础设施运维'
  }
  return labels[type] || type
}

const getSkillTypeTagType = (type: string) => {
  const types: Record<string, string> = {
    'library-api': 'primary',
    'data-analysis': 'success',
    'troubleshooting': 'danger',
    'scaffold': 'warning',
    'automation': 'primary',
    'code-review': 'info',
    'product-verify': 'success',
    'cicd': 'warning',
    'infra-ops': 'danger'
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
    ElMessage.error(`加载数据失败: ${error.displayMessage || error.message}`)
  } finally {
    loading.value = false
  }
}

// ===== 技能候选（LLM 提取待确认） =====
const activeTab = ref('approved')
const pendingList = ref<Skill[]>([])
const pendingCount = ref(0)

const loadPending = async () => {
  try {
    pendingList.value = await apiService.getSkillsByStatus('pending')
    const c = await apiService.getPendingSkillCount()
    pendingCount.value = c.count || 0
  } catch {}
}

const handleTabChange = () => {
  if (activeTab.value === 'pending') loadPending()
}

const formatSteps = (steps: string) => {
  if (!steps) return ''
  try {
    const arr = JSON.parse(steps)
    if (Array.isArray(arr)) return arr.map((s, i) => `${i + 1}. ${s}`).join('\n')
    return steps
  } catch {
    return steps
  }
}

const approveSkill = async (item: Skill) => {
  try {
    await apiService.approveSkill(item.id!)
    ElMessage.success('已确认入库')
    await loadPending()
  } catch {}
}

const rejectSkill = async (item: Skill) => {
  try {
    await apiService.rejectSkill(item.id!)
    ElMessage.success('已忽略')
    await loadPending()
  } catch {}
}

// 打开新增对话框
const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', skillType: 'library-api' as SkillType, description: '', steps: '', tags: [] }
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
loadPending()
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
