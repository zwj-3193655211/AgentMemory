<template>
  <el-card
    class="stat-card"
    :class="{ clickable: clickable }"
    :shadow="clickable ? 'hover' : 'never'"
    @click="clickable && $emit('click')"
  >
    <div class="stat-content">
      <div class="stat-icon" :class="color">
        <el-icon :size="32">
          <component :is="iconComponent" />
        </el-icon>
      </div>
      <div class="stat-info">
        <div class="stat-number">{{ value }}</div>
        <div class="stat-label">{{ title }}</div>
      </div>
      <div v-if="clickable" class="stat-arrow">
        <el-icon><ArrowRight /></el-icon>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight, ChatDotRound, WarningFilled, DocumentChecked,
  User, FolderOpened, Reading, Box
} from '@element-plus/icons-vue'

interface Props {
  icon: string
  title: string
  value: string | number
  color?: string
  clickable?: boolean
}

const props = defineProps<Props>()

defineEmits<{
  click: []
}>()

const iconMap: Record<string, any> = {
  ChatDotRound, WarningFilled, DocumentChecked,
  User, FolderOpened, Reading, Box
}

const iconComponent = computed(() => iconMap[props.icon] || Box)
</script>

<style scoped>
.stat-card {
  border-radius: 8px;
  transition: all 0.3s;
}

.stat-card.clickable {
  cursor: pointer;
}

.stat-card.clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.stat-content {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-icon.sessions {
  background: #e6f7ff;
  color: #1890ff;
}

.stat-icon.errors {
  background: #fff2e8;
  color: #fa541c;
}

.stat-icon.practices {
  background: #f6ffed;
  color: #52c41a;
}

.stat-icon.profiles {
  background: #f9f0ff;
  color: #722ed1;
}

.stat-icon.contexts {
  background: #e6fffb;
  color: #13c2c2;
}

.stat-icon.skills {
  background: #fff0f6;
  color: #eb2f96;
}

.stat-icon.messages {
  background: #f0f5ff;
  color: #2f54eb;
}

.stat-info {
  flex: 1;
  min-width: 0;
}

.stat-number {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}

.stat-arrow {
  color: #c0c4cc;
}
</style>
