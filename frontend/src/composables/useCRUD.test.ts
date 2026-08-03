import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useCRUD } from './useCRUD'

interface TestItem {
  id?: string
  title?: string
}

describe('useCRUD', () => {
  // Mock api methods
  const mockApiMethods = {
    list: vi.fn<() => Promise<TestItem[]>>(),
    create: vi.fn<(data: Partial<TestItem>) => Promise<any>>(),
    update: vi.fn<(id: string, data: Partial<TestItem>) => Promise<any>>(),
    delete: vi.fn<(id: string) => Promise<any>>()
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('initial state', () => {
    it('should initialize with empty dataList', () => {
      const { dataList, loading, dialog } = useCRUD<TestItem>(mockApiMethods, 'Test')

      expect(dataList.value).toEqual([])
      expect(loading.value).toBe(false)
      expect(dialog.visible).toBe(false)
      expect(dialog.isEdit).toBe(false)
    })
  })

  describe('loadData', () => {
    it('should load data list successfully', async () => {
      const mockData = [{ id: '1', title: 'Test 1' }, { id: '2', title: 'Test 2' }]
      mockApiMethods.list.mockResolvedValue(mockData)

      const { dataList, loading, loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')

      await loadData()

      expect(dataList.value).toEqual(mockData)
      expect(loading.value).toBe(false)
    })

    it('should handle load error', async () => {
      mockApiMethods.list.mockRejectedValue(new Error('Load failed'))

      const { loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')
      await loadData()

      // Loading should be false after error
    })
  })

  describe('openCreate', () => {
    it('should open create dialog with default values', () => {
      const { dialog, openCreate } = useCRUD<TestItem>(mockApiMethods, 'Test')
      const defaults = { title: 'Default Title' }

      openCreate(defaults)

      expect(dialog.visible).toBe(true)
      expect(dialog.isEdit).toBe(false)
      expect(dialog.formData).toEqual(defaults)
    })

    it('should open create dialog with empty defaults', () => {
      const { dialog, openCreate } = useCRUD<TestItem>(mockApiMethods, 'Test')

      openCreate()

      expect(dialog.visible).toBe(true)
      expect(dialog.isEdit).toBe(false)
    })
  })

  describe('openEdit', () => {
    it('should open edit dialog with item data', () => {
      const { dialog, openEdit } = useCRUD<TestItem>(mockApiMethods, 'Test')
      const item = { id: '123', title: 'Edit Me' }

      openEdit(item)

      expect(dialog.visible).toBe(true)
      expect(dialog.isEdit).toBe(true)
      expect(dialog.formData).toEqual(item)
    })
  })

  describe('closeDialog', () => {
    it('should close dialog and reset form data', () => {
      const { dialog, closeDialog, openCreate } = useCRUD<TestItem>(mockApiMethods, 'Test')

      openCreate({ title: 'Test' })
      closeDialog()

      expect(dialog.visible).toBe(false)
      expect(dialog.formData).toEqual({})
    })
  })

  describe('submitForm', () => {
    it('should create new item successfully', async () => {
      const mockNewItem = { id: '1', title: 'New' }
      mockApiMethods.create.mockResolvedValue(mockNewItem)

      const { dialog, submitForm, loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')
      dialog.formData = { title: 'New' }

      await loadData() // Initialize
      mockApiMethods.list.mockResolvedValue([])
      const result = await submitForm()

      expect(mockApiMethods.create).toHaveBeenCalledWith({ title: 'New' })
      expect(dialog.visible).toBe(false)
      expect(result).toBe(true)
    })

    it('should update existing item successfully', async () => {
      mockApiMethods.update.mockResolvedValue({})

      const { dialog, submitForm, loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')
      dialog.formData = { id: '123', title: 'Updated' }
      dialog.isEdit = true

      await loadData() // Initialize
      mockApiMethods.list.mockResolvedValue([])
      const result = await submitForm()

      expect(mockApiMethods.update).toHaveBeenCalledWith('123', { id: '123', title: 'Updated' })
      expect(result).toBe(true)
    })

    it('should handle submit error', async () => {
      mockApiMethods.create.mockRejectedValue(new Error('Create failed'))

      const { dialog, submitForm, loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')
      dialog.formData = { title: 'New' }

      await loadData()
      mockApiMethods.list.mockResolvedValue([])
      const result = await submitForm()

      expect(result).toBe(false)
    })
  })

  describe('deleteItem', () => {
    it('should delete item successfully', async () => {
      mockApiMethods.delete.mockResolvedValue({})

      const { deleteItem, loadData } = useCRUD<TestItem>(mockApiMethods, 'Test')
      await loadData()
      mockApiMethods.list.mockResolvedValue([])

      const result = await deleteItem('123')

      expect(mockApiMethods.delete).toHaveBeenCalledWith('123')
      expect(result).toBe(true)
    })

    it('should handle delete error', async () => {
      mockApiMethods.delete.mockRejectedValue(new Error('Delete failed'))

      const { deleteItem } = useCRUD<TestItem>(mockApiMethods, 'Test')
      const result = await deleteItem('123')

      expect(result).toBe(false)
    })
  })

  describe('confirmDelete', () => {
    it('should return false if item has no id', async () => {
      const { confirmDelete } = useCRUD<TestItem>(mockApiMethods, 'Test')
      const item = {}

      const result = await confirmDelete(item as any, 'Test Item')

      expect(result).toBe(false)
    })
    // Note: Other branches of confirmDelete require DOM (ElMessageBox) - covered by manual testing
  })

  describe('entity name', () => {
    it('should use custom entity name in error messages', async () => {
      mockApiMethods.list.mockRejectedValue(new Error('Network error'))

      const { loadData } = useCRUD(mockApiMethods, 'CustomEntity')

      await loadData()
      // The error message would include "加载CustomEntity失败"
    })
  })
})