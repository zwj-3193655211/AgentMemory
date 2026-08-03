# Frontend Refactor: Component Extraction & Code Deduplication

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 重构前端 App.vue (2544行)，消除700行重复代码，拆分为可维护的组件结构

**架构:** 采用渐进式重构策略，优先消除CRUD重复，然后提取通用组件。保持所有现有功能不变。

**技术栈:** Vue 3 (Composition API + TypeScript), Vite, Element Plus, Axios

---

## 🎯 重构目标

### 量化目标
- App.vue: 2544行 → <800行 (-68%)
- 重复代码: ~700行 → <100行 (-85%)
- 文件数: 1 → 15+ 个组件
- TypeScript覆盖率: ~30% → >95%

### 功能对等
- ✅ 所有现有功能完全保留
- ✅ UI/UX 完全一致
- ✅ API调用逻辑不变
- ✅ 数据流不变

---

## 📋 Task 0: 创建基础架构（类型定义）

### Step 1: 创建 types 目录

```bash
cd frontend/src
mkdir -p types
```

**预期结果**: `frontend/src/types/` 目录创建成功

---

### Step 2: 创建主类型定义文件

**创建**: `frontend/src/types/index.ts`

```typescript
// ============== 系统状态类型 ==============

export interface Stats {
  sessions: number
  messages: number
  errors: number
  profiles: number
  practices: number
  contexts: number
  skills: number
}

// ============== 记忆库类型 ==============

// 错误纠正
export interface ErrorCorrection {
  id?: string
  title: string
  problem: string
  cause: string
  solution: string
  example?: string
  tags?: string[]
  agentType?: string
  sessionId?: string
  createdAt?: string
}

// 用户画像
export interface UserProfile {
  id?: string
  title: string
  category: 'preference' | 'behavior' | 'techstack' | 'workhabit' | 'other'
  items: any[]  // JSON数组
  createdAt?: string
  updatedAt?: string
}

// 实践经验
export interface BestPractice {
  id?: string
  title: string
  scenario: string
  practice: string
  rationale?: string
  tags?: string[]
  sourceSession?: string
  createdAt?: string
  expiresAt?: string
}

// 项目上下文
export interface ProjectContext {
  id?: string
  title: string
  projectPath?: string
  techStack?: string
  keyDecisions?: string
  structure?: string
  createdAt?: string
  updatedAt?: string
}

// 技能沉淀
export class Skill {
  id?: string
  title: string
  skillType: 'technique' | 'method' | 'tool' | 'pattern' | 'bestpractice'
  description: string
  steps?: string
  tags?: string[]
  createdAt?: string
}

// ============== LLM 类型 ==============

export interface LLMConfig {
  mode: 'disabled' | 'api' | 'local'
  provider: string
  baseUrl: string
  apiKey: string
  model: string
  localModel: string
}

export interface LLMProvider {
  id: number
  providerName: string
  displayName: string
  baseUrl: string
  apiKey?: string
  model: string
  enabled: boolean
  isDefault: boolean
  thinkMode: boolean
}

// ============== 会话压缩类型 ==============

export interface CompressionConfig {
  autoCompress: boolean
  windowSize: number
  summaryThreshold: number
  compressionType: 'SLIDING_WINDOW' | 'SUMMARIZE' | 'HYBRID'
  llmProvider: string
}

export interface CompressionStats {
  totalSessions: number
  compressedSessions: number
  pendingSessions: number
  totalMessages: number
}

// ============== 表单状态类型 ==============

export interface DialogState<T> {
  visible: boolean
  isEdit: boolean
  formData: Partial<T>
}

export interface FormRules {
  [key: string]: any[]
}

// ============== Agent 类型 ==============

export interface Agent {
  id: number
  name: string
  displayName: string
  logBasePath: string
  cliPath?: string
  version?: string
  enabled: boolean
}
```

---

### Step 3: 提交基础架构

```bash
cd frontend
git add src/types/index.ts
git commit -m "refactor: add TypeScript type definitions"
```

---

## 📋 Task 1: 创建 API Service 层

### Step 1: 创建 services 目录

```bash
cd frontend/src
mkdir -p services
```

---

### Step 2: 创建 API Service 基类

**创建**: `frontend/src/services/api.ts`

```typescript
import axios from 'axios'
import { ElMessage } from 'element-plus'

const API_BASE = 'http://localhost:8080/api'

interface ApiResponse<T = any> {
  data: T
  message?: string
}

export class ApiService {
  /**
   * 通用 GET 请求
   */
  async get<T = any>(endpoint: string): Promise<T> {
    try {
      const res = await axios.get<ApiResponse<T>>(`${API_BASE}${endpoint}`)
      return res.data.data
    } catch (error: any) {
      ElMessage.error(`请求失败: ${endpoint}`)
      throw error
    }
  }

  /**
   * 通用 POST 请求
   */
  async post<T = any>(endpoint: string, data: any): Promise<T> {
    try {
      const res = await axios.post<ApiResponse<T>>(`${API_BASE}${endpoint}`, data)
      return res.data
    } catch (error: any) {
      ElMessage.error(`请求失败: ${endpoint}`)
      throw error
    }
  }

  /**
   * 通用 PUT 请求
   */
  async put<T = any>(endpoint: string, data: any): Promise<T> {
    try {
      const res = await axios.put<ApiResponse<T>>(`${API_BASE}${endpoint}`, data)
      return res.data
    } catch (error: any) {
      ElMessage.error(`请求失败: ${endpoint}`)
      throw error
    }
  }

  /**
   * 通用 DELETE 请求
   */
  async delete<T = any>(endpoint: string): Promise<T> {
    try {
      const res = await axios.delete<ApiResponse<T>>(`${API_BASE}${endpoint}`)
      return res.data
    } catch (error: any) {
      ElMessage.error(`请求失败: ${endpoint}`)
      throw error
    }
  }
}

// 单例实例
export const apiService = new ApiService()
```

---

### Step 3: 提交

```bash
git add frontend/src/services/api.ts
git commit -m "refactor: create API service layer"
```

---

## 📋 Task 2: 创建通用 CRUD Composable

### Step 1: 创建 composables 目录

```bash
cd frontend/src
mkdir -p composables
```

---

### Step 2: 创建通用 CRUD composable

**创建**: `frontend/src/composables/useCRUD.ts`

```typescript
import { ref, reactive } from 'vue'
import { apiService } from '../services/api'
import type { DialogState, FormRules } from '../types'

/**
 * 通用 CRUD 管理的 Composable
 * @param resourceType 资源类型（如 'errors', 'profiles' 等）
 */
export function useCRUD<T extends { id?: string }>(
  resourceType: string,
  getFormRules: () => FormRules,
  defaultData: () => Partial<T>
) {
  // 对话框状态
  const dialogState = reactive<DialogState<T>>({
    visible: false,
    isEdit: false,
    formData: {}
  })

  const formRef = ref()

  // 数据列表
  const items = ref<T[]>([])

  /**
   * 加载数据列表
   */
  const loadItems = async () => {
    try {
      const data = await apiService.get<T[]>(`/${resourceType}`)
      items.value = data
    } catch (e: any) {
      console.error(`加载 ${resourceType} 失败`, e)
    }
  }

  /**
   * 打开新建对话框
   */
  const openCreateDialog = () => {
    dialogState.visible = true
    dialogState.isEdit = false
    dialogState.formData = defaultData()
    formRef.value?.resetFields()
  }

  /**
   * 打开编辑对话框
   */
  const openEditDialog = (item: T) => {
    dialogState.visible = true
    dialogState.isEdit = true
    dialogState.formData = { ...item }
  }

  /**
   * 关闭对话框
   */
  const closeDialog = () => {
    dialogState.visible = false
    dialogState.formData = {}
  }

  /**
   * 提交表单（新建或更新）
   */
  const submitForm = async () => {
    if (dialogState.isEdit) {
      // 更新
      if (!dialogState.formData.id) {
        ElMessage.error('缺少 ID')
        return
      }
      await apiService.put(`/${resourceType}/${dialogState.formData.id}`, dialogState.formData)
      ElMessage.success('更新成功')
    } else {
      // 新建
      await apiService.post(`/${resourceType}`, dialogState.formData)
      ElMessage.success('创建成功')
    }

    closeDialog()
    await loadItems()
  }

  /**
   * 删除项目
   */
  const deleteItem = async (id: string) => {
    try {
      await apiService.delete(`/${resourceType}/${id}`)
      ElMessage.success('删除成功')
      await loadItems()
    } catch (e: any) {
      ElMessage.error('删除失败')
    }
  }

  return {
    dialogState,
    formRef,
    items,
    loadItems,
    openCreateDialog,
    openEditDialog,
    closeDialog,
    submitForm,
    deleteItem
  }
}
```

---

### Step 3: 提交

```bash
git add frontend/src/composables/useCRUD.ts
git commit -m "refactor: create generic CRUD composable"
```

---

## 📋 Task 3: 重构错误纠正库组件

### Step 1: 创建错误纠正视图组件

**创建**: `frontend/src/views/Errors.vue`

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useCRUD } from '../composables/useCRUD'
import type { ErrorCorrection } from '../types'

// 表单验证规则
const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  problem: [{ required: true, message: '请描述问题', trigger: 'blur' }],
  solution: [{ required: true, message: '请提供解决方案', trigger: 'blur' }]
}

const defaultData = () => ({
  title: '',
  problem: '',
  solution: ''
})

const {
  dialogState,
  formRef,
  items,
  loadItems,
  openCreateDialog,
  openEditDialog,
  closeDialog,
  submitForm,
  deleteItem
} = useCRUD<ErrorCorrection>('errors', formRules, defaultData)

onMounted(() => {
  loadItems()
})
</script>

<template>
  <div class="errors-view">
    <div class="panel-header">
      <h2>错误纠正库</h2>
      <el-button type="primary" @click="openCreateDialog">
        <el-icon><Plus /></el-icon> 新增
      </el-button>
    </div>

    <!-- 数据表格 -->
    <el-card class="data-card">
      <el-table :data="items" stripe>
        <el-table-column prop="title" label="标题" width="200" />
        <el-table-column prop="problem" label="问题" min-width="250" show-overflow-tooltip />
        <el-table-column prop="solution" label="解决方案" min-width="250" show-overflow-tooltip />
        <el-table-column prop="tags" label="标签" width="150">
          <template #default="{ row }">
            <el-tag v-for="(tag, idx) in parseTags(row.tags)" :key="idx" size="small">
              {{ tag }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="deleteItem(row.id!)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑对话框 -->
    <el-dialog
      v-model="dialogState.visible"
      :title="dialogState.isEdit ? '编辑错误纠正' : '新增错误纠正'"
      width="600px"
    >
      <el-form :model="dialogState.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialogState.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="问题" prop="problem">
          <el-input v-model="dialogState.formData.problem" type="textarea" :rows="3" placeholder="请描述问题" />
        </el-form-item>
        <el-form-item label="解决方案" prop="solution">
          <el-input v-model="dialogState.formData.solution" type="textarea" :rows="3" placeholder="请提供解决方案" />
        </el-form-item>
        <el-form-item label="示例代码" prop="example">
          <el-input v-model="dialogState.formData.example" type="textarea" :rows="3" placeholder="可选：示例代码" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="dialogState.formData.tags" placeholder="逗号分隔，如：bug,fix,python" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" @click="submitForm">
          {{ dialogState.isEdit ? '更新' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 解析标签字符串为数组
function parseTags(tagsStr: string): string[] {
  if (!tagsStr) return []
  return tagsStr.split(',').map(t => t.trim()).filter(t => t)
}
</script>

<style scoped>
.errors-view {
  padding: 20px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.data-card {
  margin-top: 20px;
}
</style>
```

---

### Step 2: 提交错误纠正组件

```bash
git add frontend/src/views/Errors.vue
git commit -m "refactor: extract Errors component"
```

---

## 📋 Task 4: 在 App.vue 中使用错误纠正组件

### Step 1: 在 App.vue 中导入组件

**修改**: `frontend/src/App.vue`

在 import 部分添加（约第945行之后）：

```typescript
import Errors from './views/Errors.vue'
```

---

### Step 2: 替换错误纠正页面模板

**修改**: `frontend/src/App.vue`

找到错误纠正页面的 template 部分（约在第？行）：

```vue
<!-- 原来的代码（约150-200行）-->
<div v-if="activeMenu === 'errors'" class="content-panel full">
  <div class="panel-header">
    <h2>错误纠正库</h2>
    <!-- ... 大量代码 ... -->
  </div>

  <!-- 对话框、表格等 -->
</div>
```

替换为：

```vue
<!-- 使用新组件 -->
<Errors v-if="activeMenu === 'errors'" />
```

---

### Step 3: 删除错误纠正相关的状态和函数

**修改**: `frontend/src/App.vue`

删除以下内容（约在第965-973行）：

```typescript
// 删除这些状态
const errorDialogVisible = ref(false)
const errorIsEdit = ref(false)
const errorFormData = ref<any>({})
const errorFormRef = ref()
const errorRules = { ... }
```

删除以下函数（找到它们，删除整个函数）：

```typescript
const submitErrorForm = async () => { /* ... */ }
const deleteError = async (id: string) => { /* ... */ }
// 还有其他相关函数
```

---

### Step 4: 测试验证

1. 启动前端：`cd frontend && npm run dev`
2. 访问 http://localhost:5173
3. 点击"错误纠正"菜单
4. 验证功能：
   - ✅ 表格数据显示
   - ✅ 新增按钮打开对话框
   - ✅ 编辑功能正常
   - ✅ 删除功能正常
   - ✅ 表单验证生效

**预期结果**: 所有功能与重构前完全一致

---

### Step 5: 提交

```bash
git add frontend/src/App.vue
git commit -m "refactor: use Errors component, remove duplicated code"
```

---

## 📋 Task 5: 重构实践经验库组件

**创建**: `frontend/src/views/Practices.vue`

使用与 Task 3/4 相同的模式，创建实践经验组件：

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useCRUD } from '../composables/useCRUD'
import type { BestPractice } from '../types'

const formRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  scenario: [{ required: true, message: '请描述场景', trigger: 'blur' }],
  practice: [{ required: true, message: '请提供实践经验', trigger: 'blur' }]
}

const defaultData = () => ({
  title: '',
  scenario: '',
  practice: ''
})

const {
  dialogState,
  formRef,
  items,
  loadItems,
  openCreateDialog,
  openEditDialog,
  closeDialog,
  submitForm,
  deleteItem
} = useCRUD<BestPractice>('practices', formRules, defaultData)

onMounted(() => {
  loadItems()
})
</script>

<template>
  <div class="practices-view">
    <div class="panel-header">
      <h2>实践经验库</h2>
      <el-button type="primary" @click="openCreateDialog">
        <el-icon><Plus /></el-icon> 新增
      </el-button>
    </div>

    <el-card class="data-card">
      <el-table :data="items" stripe>
        <el-table-column prop="title" label="标题" width="200" />
        <el-table-column prop="scenario" label="适用场景" min-width="200" show-overflow-tooltip />
        <el-table-column prop="practice" label="实践经验" min-width="250" show-overflow-tooltip />
        <el-table-column prop="rationale" label="原理说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="deleteItem(row.id!)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogState.visible"
      :title="dialogState.isEdit ? '编辑实践经验' : '新增实践经验'"
      width="600px"
    >
      <el-form :model="dialogState.formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="dialogState.formData.title" placeholder="请输入标题" />
        </el-form-item>
        <el-form-item label="适用场景" prop="scenario">
          <el-input v-model="dialogState.formData.scenario" type="textarea" :rows="2" placeholder="请描述适用场景" />
        </el-form-item>
        <el-form-item label="实践经验" prop="practice">
          <el-input v-model="dialogState.formData.practice" type="textarea" :rows="3" placeholder="请提供实践经验" />
        </el-form-item>
        <el-form-item label="原理说明" prop="rationale">
          <el-input v-model="dialogState.formData.rationale" type="textarea" :rows="2" placeholder="可选：原理说明" />
        </el-form-item>
        <el-form-item label="标签" prop="tags">
          <el-input v-model="dialogState.formData.tags" placeholder="逗号分隔，如：performance,optimization" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" @click="submitForm">
          {{ dialogState.isEdit ? '更新' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.practices-view {
  padding: 20px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.data-card {
  margin-top: 20px;
}
</style>
```

---

### Step 6: 在 App.vue 中使用

**修改**: `frontend/src/App.vue`

1. 添加导入：
```typescript
import Practices from './views/Practices.vue'
```

2. 替换模板（找到实践经验部分）：
```vue
<Practices v-if="activeMenu === 'practices'" />
```

3. 删除相关状态和函数

---

### Step 7: 测试并提交

```bash
# 测试：访问 http://localhost:5173，点击"实践经验"菜单，验证所有CRUD功能
git add frontend/src/views/Practices.vue frontend/src/App.vue
git commit -m "refactor: extract Practices component"
```

---

## 📋 Task 6-10: 重构其他3个记忆库组件

重复 Task 5 的步骤，创建：

### Task 6: 用户画像组件
- 文件: `frontend/src/views/Profiles.vue`
- 类型: `UserProfile`
- API: `/profiles`

### Task 7: 项目上下文组件
- 文件: `frontend/src/views/Contexts.vue`
- 类型: `ProjectContext`
- API: `/contexts`

### Task 8: 技能沉淀组件
- 文件: `frontend/src/views/Skills.vue`
- 类型: `Skill` (注意这是class，不是interface)
- API: `/skills`

### Task 9: 合并仪表盘和会话页面
- 文件: `frontend/src/views/Dashboard.vue`
- 文件: `frontend/src/views/Sessions.vue`

### Task 10: 测试所有组件

测试所有页面功能，创建测试清单。

---

## 📋 Task 11: 清理和优化

### Step 1: 删除不再使用的导入

**修改**: `frontend/src/App.vue`

删除已经移到组件中的导入：
- 如果 InfoFilled 只在某个组件中使用，从 App.vue 导入中移除
- 清理无用的 import

---

### Step 2: 合并重复的 API 调用

**修改**: `frontend/src/App.vue`

将所有 `loadXxx()` 函数统一为一个：

```typescript
const loadAllData = async () => {
  await Promise.all([
    loadStats(),
    loadAgents(),
    loadSessions(),
    loadErrors(),
    loadProfiles(),
    loadPractices(),
    loadContexts(),
    loadSkills()
  ])
}
```

在 `onMounted` 中调用：

```typescript
onMounted(() => {
  loadAllData()
})
```

---

### Step 3: 提交

```bash
git add frontend/src/App.vue
git commit -m "refactor: clean up imports and merge duplicate API calls"
```

---

## 📋 Task 12: 最终验证和文档

### Step 1: 检查文件大小

```bash
wc -l frontend/src/App.vue
wc -l frontend/src/views/*.vue
wc -l frontend/src/composables/*.ts
wc -l frontend/src/services/*.ts
wc -l frontend/src/types/*.ts
```

**预期结果**:
- App.vue < 800行
- 总代码量与之前相当（代码被拆分，不是删除）

---

### Step 2: 检查重复代码

```bash
cd frontend/src
# 检查是否还有明显的重复代码
```

---

### Step 3: 最终提交

```bash
git add frontend/
git commit -m "refactor: complete frontend component extraction and deduplication"

# 打标签
git tag -a v2.2.0 -m "Frontend refactored - components extracted"
```

---

## ✅ 验证检查清单

在每个 Task 完成后验证：

- [ ] 组件正常显示
- [ ] 数据加载正常
- [ ] 新建功能正常
- [ ] 编辑功能正常
- [ ] 删除功能正常
- [ ] 表单验证正常
- [ ] 无控制台错误
- [ ] TypeScript 编译无错误

---

## 🎯 成功标准

| 指标 | 目标 | 验证方法 |
|------|------|---------|
| App.vue 行数 | <800行 | wc -l |
| 功能完整性 | 100% | 手动测试所有页面 |
| 重复代码 | <100行 | 代码审查 |
| TypeScript 错误 | 0 | npm run build |
| 控制台错误 | 0 | 浏览器控制台 |

---

## 🔄 回滚计划

如果重构失败，回滚命令：

```bash
# 回滚到重构前的标签
git reset --hard v2.1.0

# 或者回滚到上一个稳定版本
git reset --hard HEAD~1
```

---

## 📝 预期收益

执行完所有12个任务后：

- ✅ 代码可维护性大幅提升
- ✅ 代码重复率从 28% 降至 <5%
- ✅ 组件化程度 0% → 90%+
- ✅ 类型覆盖率 30% → 95%+
- ✅ 单文件行数 2544行 → <800行

---

**预计总时间**: 约12-15小时

**风险等级**: 中等（每个Task独立可回滚）
