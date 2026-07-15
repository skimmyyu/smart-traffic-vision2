<template>
  <div class="alerts-page">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span>告警中心</span>
          <div>
            <el-button type="primary" link @click="downloadExport('alerts')">导出 CSV</el-button>
            <el-button type="primary" link @click="loadAlerts">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="loading" :data="alerts" stripe empty-text="暂无告警">
        <el-table-column prop="alertType" label="类型" width="160">
          <template #default="{ row }">
            <el-tag :type="alertTagType(row.alertType)" size="small">{{ formatAlertType(row.alertType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        <el-table-column label="摄像头 / 区域" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ formatLocation(row.location) }}</template>
        </el-table-column>
        <el-table-column prop="occurredAt" label="发生时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAlerts } from '../api/alert.js'
import { downloadExport } from '../api/export.js'
import { connectLiveSocket, disconnectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const alerts = ref([])
const loading = ref(false)
let offLive = null

function formatAlertType(type) {
  const map = {
    parking_violation: '禁停告警',
    road_anomaly: '道路异常',
    congestion_warning: '拥堵预警'
  }
  return map[type] || type
}

function alertTagType(type) {
  if (type === 'parking_violation') return 'warning'
  if (type === 'road_anomaly') return 'danger'
  return 'info'
}

function formatLocation(location) {
  if (!location) return '—'
  try {
    const value = typeof location === 'string' ? JSON.parse(location) : location
    if (value.cameraName || value.region) {
      return [value.cameraName && `${value.cameraName}${value.cameraId ? ` (${value.cameraId})` : ''}`, value.region]
        .filter(Boolean).join(' / ')
    }
    if (value.lane) return value.lane
  } catch {
    /* old plain-text records */
  }
  return String(location)
}

async function loadAlerts() {
  loading.value = true
  try {
    alerts.value = await fetchAlerts(100)
  } catch (e) {
    ElMessage.error(e.message || '加载告警失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAlerts()
  connectLiveSocket()
  offLive = onLiveMessage((msg) => {
    if (msg.type === 'alert') loadAlerts()
  })
})

onUnmounted(() => {
  offLive?.()
})
</script>

<style scoped>
.alerts-page {
  max-width: 1350px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
