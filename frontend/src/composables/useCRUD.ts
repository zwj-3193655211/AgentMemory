import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 通用 CRUD Composable
 * 提供列表、增删改查的通用逻辑
 * 
 * @example
 * ```ts
 * const { dataList, loading, dialog, loadData, openCreate, openEdit, submitForm, deleteItem } = useCRUD(
 *   {
 *     list: () => apiService.getErrors(),
 *     create: (data) => apiService.createError(data),
 *     update: (id, data) => apiService.updateError(id, data),
 *     delete: (id) => apiService.deleteError(id)
 *   },
 *   '错误纠正'
 * )
 * ```
 */
export function useCRUD<T extends { id?: string }>(
  apiMethods: {
    list: () => Promise<T[]>
    create: (data: Partial<T>) => Promise<any>
    update: (id: string, data: Partial<T>) => Promise<any>
    delete: (id: string) => Promise<any>
  },
  entityName: string
) {
  // 数据列表
  const dataList = ref<T[]>([])
  
  // 加载状态
  const loading = ref(false)
  
  // 对话框状态（使用 any 类型避免 Vue reactive 的类型问题）
  const dialog = reactive<any>({
    visible: false,
    isEdit: false,
    formData: {}
  })

  /**
   * 加载数据列表
   */
  async function loadData() {
    loading.value = true
    try {
      dataList.value = await apiMethods.list()
    } catch (error: any) {
      ElMessage.error(`加载${entityName}失败: ${error.message}`)
    } finally {
      loading.value = false
    }
  }

  /**
   * 打开新建对话框
   */
  function openCreate(defaultValues: Partial<T> = {}) {
    dialog.isEdit = false
    dialog.formData = { ...defaultValues }
    dialog.visible = true
  }

  /**
   * 打开编辑对话框
   */
  function openEdit(item: T) {
    dialog.isEdit = true
    dialog.formData = { ...item }
    dialog.visible = true
  }

  /**
   * 关闭对话框
   */
  function closeDialog() {
    dialog.visible = false
    dialog.formData = {}
  }

  /**
   * 提交表单（新建或编辑）
   */
  async function submitForm() {
    try {
      if (dialog.isEdit && dialog.formData.id) {
        await apiMethods.update(dialog.formData.id, dialog.formData)
        ElMessage.success(`更新${entityName}成功`)
      } else {
        await apiMethods.create(dialog.formData)
        ElMessage.success(`创建${entityName}成功`)
      }
      closeDialog()
      await loadData()
      return true
    } catch (error: any) {
      // 错误已在 ApiService 中处理
      return false
    }
  }

  /**
   * 删除项目
   */
  async function deleteItem(id: string) {
    try {
      await apiMethods.delete(id)
      ElMessage.success(`删除${entityName}成功`)
      await loadData()
      return true
    } catch (error: any) {
      // 错误已在 ApiService 中处理
      return false
    }
  }

  /**
   * 确认删除
   */
  async function confirmDelete(item: T, itemName: string) {
    if (!item.id) return false
    
    // 返回一个 Promise，让调用者可以等待用户确认
    return new Promise<boolean>((resolve) => {
      ElMessageBox.confirm(
        `确定要删除${entityName} "${itemName}" 吗？`,
        '确认删除',
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        }
      ).then(async () => {
        const result = await deleteItem(item.id!)
        resolve(result)
      }).catch(() => {
        resolve(false)
      })
    })
  }

  return {
    dataList,
    loading,
    dialog,
    loadData,
    openCreate,
    openEdit,
    closeDialog,
    submitForm,
    deleteItem,
    confirmDelete
  }
}
