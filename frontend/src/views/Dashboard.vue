<template>
  <div class="content-panel full">
    <div class="panel-header">
      <h2>仪表盘</h2>
      <el-tag type="success">系统运行正常</el-tag>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <StatCard icon="ChatDotRound" title="会话总数" :value="stats.sessions" color="sessions" />
      <StatCard icon="WarningFilled" title="错误纠正" :value="stats.errors" color="errors" clickable @click="$emit('navigate', 'errors')" />
      <StatCard icon="DocumentChecked" title="实践经验" :value="stats.practices" color="practices" clickable @click="$emit('navigate', 'practices')" />
      <StatCard icon="User" title="用户画像" :value="stats.profiles" color="profiles" clickable @click="$emit('navigate', 'profiles')" />
      <StatCard icon="FolderOpened" title="项目上下文" :value="stats.contexts" color="contexts" clickable @click="$emit('navigate', 'contexts')" />
      <StatCard icon="Reading" title="技能沉淀" :value="stats.skills" color="skills" clickable @click="$emit('navigate', 'skills')" />
      <StatCard icon="Box" title="消息总数" :value="stats.messages.toLocaleString()" color="messages" />
    </div>

    <!-- ECharts 图表区 -->
    <div class="charts-grid">
      <el-card class="chart-card" shadow="never">
        <template #header><span class="chart-title">近30天活跃趋势</span></template>
        <v-chart class="chart-instance" :option="trendOption" autoresize />
      </el-card>
      <el-card class="chart-card" shadow="never">
        <template #header><span class="chart-title">Agent 使用分布</span></template>
        <v-chart class="chart-instance" :option="agentPieOption" autoresize />
      </el-card>
      <el-card class="chart-card" shadow="never">
        <template #header><span class="chart-title">记忆库概览</span></template>
        <v-chart class="chart-instance" :option="memoryBarOption" autoresize />
      </el-card>
      <el-card class="chart-card" shadow="never">
        <template #header><span class="chart-title">消息增长趋势</span></template>
        <v-chart class="chart-instance" :option="msgAreaOption" autoresize />
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent,
  TitleComponent, DataZoomComponent
} from 'echarts/components'
import StatCard from '../components/StatCard.vue'
import type { Stats } from '../types'

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, DataZoomComponent])

interface Props {
  stats: Stats
}

const props = defineProps<Props>()

defineEmits<{
  navigate: [menu: string]
}>()

const trendOption = computed(() => {
  const ds = props.stats.dailySessions || []
  const dm = props.stats.dailyMessages || []
  const dates = Array.from(new Set([...ds.map(r => r.date), ...dm.map(r => r.date)])).sort()
  const sessionMap = Object.fromEntries(ds.map(r => [r.date, r.count]))
  const msgMap = Object.fromEntries(dm.map(r => [r.date, r.count]))
  return {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['会话数', '消息数'], top: 0 },
    grid: { left: 40, right: 50, bottom: 30, top: 32 },
    xAxis: { type: 'category' as const, data: dates, axisLabel: { fontSize: 10, rotate: 30 } },
    yAxis: [
      { type: 'value' as const, name: '会话', nameTextStyle: { fontSize: 10 } },
      { type: 'value' as const, name: '消息', nameTextStyle: { fontSize: 10 } }
    ],
    series: [
      { name: '会话数', type: 'line' as const, smooth: true, data: dates.map(d => sessionMap[d] || 0), yAxisIndex: 0, itemStyle: { color: '#5470c6' } },
      { name: '消息数', type: 'line' as const, smooth: true, data: dates.map(d => msgMap[d] || 0), yAxisIndex: 1, itemStyle: { color: '#91cc75' } }
    ]
  }
})

const agentPieOption = computed(() => {
  const dist = props.stats.agentDistribution || []
  return {
    tooltip: { trigger: 'item' as const, formatter: '{b}: {c} ({d}%)' },
    legend: { orient: 'vertical' as const, right: 10, top: 'center', textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie' as const, radius: ['40%', '70%'], center: ['40%', '50%'],
      label: { show: false },
      data: dist.map(r => ({ name: r.agentType || '未知', value: r.count }))
    }]
  }
})

const memoryBarOption = computed(() => {
  const dist = props.stats.memoryDistribution || []
  return {
    tooltip: { trigger: 'axis' as const, axisPointer: { type: 'shadow' as const } },
    grid: { left: 80, right: 20, bottom: 20, top: 20 },
    xAxis: { type: 'value' as const },
    yAxis: { type: 'category' as const, data: dist.map(r => r.type), axisLabel: { fontSize: 11 } },
    series: [{
      type: 'bar' as const, data: dist.map(r => r.count),
      itemStyle: { color: '#5470c6', borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right' as const, fontSize: 11 }
    }]
  }
})

const msgAreaOption = computed(() => {
  const dm = props.stats.dailyMessages || []
  let cum = 0
  const dates = dm.map(r => r.date)
  const cumData = dm.map(r => { cum += r.count; return cum })
  return {
    tooltip: { trigger: 'axis' as const },
    grid: { left: 50, right: 20, bottom: 30, top: 20 },
    xAxis: { type: 'category' as const, data: dates, axisLabel: { fontSize: 10, rotate: 30 } },
    yAxis: { type: 'value' as const },
    series: [{
      type: 'line' as const, smooth: true, data: cumData,
      areaStyle: { opacity: 0.3 },
      itemStyle: { color: '#91cc75' }
    }]
  }
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.chart-card {
  border-radius: 8px;
}

.chart-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.chart-instance {
  height: 280px;
  width: 100%;
}

@media (max-width: 1200px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
}
</style>
