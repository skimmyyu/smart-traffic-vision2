<template>
  <div class="records-page">
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>闸机通行记录</span>
              <div>
                <el-button type="primary" link @click="downloadExport('plates')">导出 CSV</el-button>
                <el-button type="primary" link @click="loadRecords">刷新</el-button>
              </div>
            </div>
          </template>
          <el-table v-loading="loading" :data="records" stripe empty-text="暂无识别记录">
            <el-table-column prop="plateNumber" label="车牌号码" width="140" />
            <el-table-column prop="passResult" label="通行结果" width="120">
              <template #default="{ row }">
                <el-tag :type="row.passResult === 'allow' ? 'success' : 'danger'" size="small">
                  {{ row.passResult === 'allow' ? '允许通行' : '拒绝通行' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="采集摄像头" min-width="190" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.cameraName || row.cameraId || '历史记录未标注' }}
                <span v-if="row.cameraName && row.cameraId">（{{ row.cameraId }}）</span>
              </template>
            </el-table-column>
            <el-table-column prop="recognizedAt" label="识别时间" min-width="180" />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="hover">
          <template #header>手动模拟识别</template>
          <el-form inline @submit.prevent>
            <el-form-item label="车牌">
              <el-input v-model="mockPlate" placeholder="如 京A12345" style="width: 160px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="mocking" @click="handleMock">模拟识别</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card class="live-card" shadow="hover">
          <template #header>WebSocket 实时事件</template>
          <el-scrollbar height="280px">
            <div v-for="(item, idx) in liveEvents" :key="idx" class="live-item">
              <el-tag size="small" type="info">{{ item.type }}</el-tag>
              <span class="live-text">{{ item.summary }}</span>
            </div>
            <el-empty v-if="!liveEvents.length" description="等待实时推送" :image-size="60" />
          </el-scrollbar>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { addPlateRecord, fetchPlateRecords } from '../api/plate.js'
import { downloadExport } from '../api/export.js'
import { connectLiveSocket, disconnectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const records = ref([])
const loading = ref(false)
const mocking = ref(false)
const mockPlate = ref('京A12345')
const liveEvents = ref([])
let offLive = null

function pushLiveEvent(type, summary) {
  liveEvents.value.unshift({ type, summary, at: new Date().toLocaleTimeString() })
  if (liveEvents.value.length > 20) {
    liveEvents.value.pop()
  }
}

async function loadRecords() {
  loading.value = true
  try {
    records.value = await fetchPlateRecords(100)
  } catch (e) {
    ElMessage.error(e.message || '加载记录失败')
  } finally {
    loading.value = false
  }
}

async function handleMock() {
  const plate = mockPlate.value.trim()
  if (!plate) {
    ElMessage.warning('请输入车牌')
    return
  }
  mocking.value = true
  try {
    await addPlateRecord(plate)
    ElMessage.success('已生成识别记录')
    await loadRecords()
  } catch (e) {
    ElMessage.error(e.message || '模拟失败')
  } finally {
    mocking.value = false
  }
}

function handleLiveMessage(msg) {
  if (msg.type === 'detection_result') {
    const plate = msg.data?.plateNumber || '-'
    const decision = msg.data?.decision === 'allow' ? '允许' : '拒绝'
    const camera = msg.data?.cameraName || msg.data?.cameraId || '未标注摄像头'
    pushLiveEvent('车牌识别', `${plate} · ${decision} · ${camera}`)
    loadRecords()
  } else if (msg.type === 'alert') {
    pushLiveEvent('告警', msg.data?.message || msg.data?.alertType || '新告警')
  } else if (msg.type === 'heatmap_update') {
    pushLiveEvent('拥堵', `车辆数 ${msg.data?.vehicleCount ?? '-'}`)
  }
}

onMounted(() => {
  loadRecords()
  connectLiveSocket()
  offLive = onLiveMessage(handleLiveMessage)
})

onUnmounted(() => {
  offLive?.()
  disconnectLiveSocket()
})
</script>

<style scoped>
.records-page {
  max-width: 1200px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.live-card {
  margin-top: 16px;
}
.live-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}
.live-text {
  font-size: 13px;
  color: #606266;
}
</style>
