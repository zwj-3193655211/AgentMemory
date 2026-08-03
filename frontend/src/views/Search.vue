<template>
  <div class="content-panel full">
    <div class="panel-header">
      <h2>搜索结果: "{{ searchQuery }}"</h2>
      <el-tag>{{ searchResults.length }} 条结果</el-tag>
    </div>
    <el-table :data="searchResults" stripe v-loading="searching">
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
</template>

<script setup lang="ts">
interface Props {
  searchQuery: string
  searchResults: any[]
  searching: boolean
}

defineProps<Props>()

const getTypeTagType = (type: string): string => {
  const map: Record<string, string> = {
    error_correction: 'danger',
    best_practice: 'success',
    user_profile: 'warning',
    project_context: 'primary',
    skill: 'info'
  }
  return map[type] || 'info'
}
</script>
