<template>
  <div class="contexts-view">
    <div class="panel-header">
      <h2>项目上下文库</h2>
      <div style="display: flex; gap: 10px;">
        <el-button type="primary" @click="openCreate()">
          <el-icon><Plus /></el-icon> 新增
        </el-button>
        <el-button @click="exportData">
          <el-icon><Download /></el-icon> 导出
        </el-button>
      </div>
    </div>

    <el-table :data="dataList" stripe v-loading="loading">
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="techStack" label="技术栈" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="tech in parseTechStack(row.techStack)" :key="tech" size="small" class="tech-tag">{{ tech }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 对话框 -->
    <el-dialog v-model="dialog.visible" :title="dialog.isEdit ? '编辑项目上下文' : '新增项目上下文'" width="600px">
      <el-form :model="dialog.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialog.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="dialog.formData.projectName" placeholder="例如：agent-memory" />
        </el-form-item>
        <el-form-item label="技术栈" prop="techStack">
          <el-input v-model="techStackInput" placeholder="逗号分隔，如：React,TypeScript,Node.js" />
        </el-form-item>
        <el-form-item label="关键决策" prop="keyDecisions">
          <el-input v-model="dialog.formData.keyDecisions" type="textarea" :rows="2" placeholder="可选：项目中的关键决策" />
        </el-form-item>
        <el-form-item label="项目结构" prop="structure">
          <el-input v-model="dialog.formData.structure" type="textarea" :rows="3" placeholder="可选：项目结构说明" />
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
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download } from '@element-plus/icons-vue'
import { apiService, API_BASE_URL } from '../services/api'
import type { ProjectContext } from '../types'

// 数据列表
const dataList = ref<ProjectContext[]>([])
const loading = ref(false)

// 对话框状态
const dialog = reactive({
  visible: false,
  isEdit: false,
  formData: {} as Partial<ProjectContext>
})

// 技术栈输入
const techStackInput = ref('')

// 表单引用
const formRef = ref()

// 表单验证规则
const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
}

// 格式化时间
const formatTime = (time: string | Date) => {
  if (!time) return ''
  const d = new Date(time)
  return d.toLocaleString('zh-CN')
}

// 解析技术栈
const parseTechStack = (techStack: string | string[]) => {
  if (Array.isArray(techStack)) return techStack
  if (typeof techStack === 'string' && techStack) {
    return techStack.split(',').map(t => t.trim()).filter(t => t)
  }
  return []
}

// 加载数据
const loadData = async () => {
  loading.value = true
  try {
    dataList.value = await apiService.getContexts()
  } catch (error: any) {
    ElMessage.error(`加载数据失败: ${error.message}`)
  } finally {
    loading.value = false
  }
}

// 打开新增对话框
const openCreate = () => {
  dialog.isEdit = false
  dialog.formData = { title: '', projectName: '', projectPath: '', techStack: '', keyDecisions: '', structure: '' }
  techStackInput.value = ''
  dialog.visible = true
}

// 打开编辑对话框
const openEdit = (row: ProjectContext) => {
  dialog.isEdit = true
  dialog.formData = { ...row }
  techStackInput.value = Array.isArray(row.techStack) ? row.techStack.join(', ') : (row.techStack || '')
  dialog.visible = true
}

// 提交表单
const handleSubmit = async () => {
  if (!formRef.value) return
  
  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return

    const data = { ...dialog.formData }
    data.techStack = techStackInput.value.split(',').map(t => t.trim()).filter(t => t)

    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiService.updateContext(dialog.formData.id, data)
        ElMessage.success('更新成功')
      } else {
        await apiService.createContext(data)
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
const handleDelete = async (row: ProjectContext) => {
  if (!row.id) return
  
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '确认删除', { type: 'warning' })
    await apiService.deleteContext(row.id)
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
    const response = await fetch(`${API_BASE_URL}/contexts/export`)
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
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

// 暴露方法供父组件调用
defineExpose({ loadData })

// 初始化加载数据
loadData()
</script>

<style scoped>
.contexts-view {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}

.contexts-view :deep(.el-table) {
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

.tech-tag {
  margin-right: 6px;
  margin-bottom: 6px;
  background: linear-gradient(135deg, #48bb78 0%, #68d391 100%);
  border: none;
  color: #fff;
}
</style>
