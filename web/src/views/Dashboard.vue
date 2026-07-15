<template>
  <div class="dashboard">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>推流状态</template>
          <div class="status-row">
            <el-tag :type="stream.online ? 'success' : 'danger'" size="large">
              {{ stream.online ? '在线' : '离线' }}
            </el-tag>
            <span class="fps">{{ stream.fps.toFixed(1) }} FPS</span>
          </div>
          <el-descriptions :column="1" size="small" class="desc">
            <el-descriptions-item label="累计帧数">{{ stream.totalFrames }}</el-descriptions-item>
            <el-descriptions-item label="距上次帧">{{ formatAge(stream.lastFrameAgeMs) }}</el-descriptions-item>
            <el-descriptions-item label="RTSP">{{ stream.rtspUrl }}</el-descriptions-item>
            <el-descriptions-item v-if="stream.lastError" label="错误">
              <span class="error">{{ stream.lastError }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>服务器资源</template>
          <el-descriptions :column="1">
            <el-descriptions-item label="CPU">{{ system.cpuUsage.toFixed(1) }}%</el-descriptions-item>
            <el-descriptions-item label="内存">{{ system.memoryUsage.toFixed(1) }}%</el-descriptions-item>
            <el-descriptions-item label="GPU">{{ system.gpuUsage.toFixed(1) }}%</el-descriptions-item>
            <el-descriptions-item label="拉流">{{ system.streamOnline ? '在线' : '离线' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>连接信息</template>
          <el-descriptions :column="1" size="small">
            <el-descriptions-item label="后端 API">{{ serverUrl }}</el-descriptions-item>
            <el-descriptions-item label="刷新间隔">3 秒</el-descriptions-item>
            <el-descriptions-item label="最近更新">{{ lastUpdate || '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" plain class="refresh-btn" @click="loadData">立即刷新</el-button>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="source-card">
      <template #header>视频传输状态</template>
      <div class="source-grid">
        <div v-for="source in cameraSources" :key="source.id" class="source-item">
          <div><b>{{ source.name }}</b><span>{{ source.id }}</span></div>
          <el-tag :type="source.online ? 'success' : 'info'">{{ source.online ? '帧传输正常' : '等待画面' }}</el-tag>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-num">{{ stats.todayPlateCount }}</div>
          <div class="stat-label">今日识别</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-num">{{ stats.todayAlertCount }}</div>
          <div class="stat-label">今日告警</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-num">{{ Number(stats.avgVehicleCount || 0).toFixed(1) }}</div>
          <div class="stat-label">平均车流</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-num">{{ stats.onlineDeviceCount }}/{{ stats.deviceCount }}</div>
          <div class="stat-label">在线设备</div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="loadError"
      class="alert"
      type="error"
      :title="loadError"
      show-icon
      :closable="false"
    />

    <el-card class="tips" shadow="never">
      <template #header>启动检查清单（704 沙盘摄像头）</template>
      <ol>
        <li>电脑能访问沙盘 RTSP 服务器（校园网内）：VLC 试播 rtsp://10.126.59.120:8554/live/live12</li>
        <li>MediaMTX 已运行，本地转发正常：VLC 试播 rtsp://127.0.0.1:8554/cam1</li>
        <li>Java 后端 start-server.bat 已运行，Dashboard 显示拉流在线</li>
        <li>前端 web/start-web.bat 已运行，监控页可切换 live1～live12</li>
      </ol>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { fetchStreamStatus, fetchSystemStatus } from '../api/system.js'
import { fetchStatisticsOverview } from '../api/statistics.js'
import { SERVER_URL } from '../config.js'
import { fetchCameraSources } from '../api/camera.js'

const HIDDEN_CAMERA_SOURCES = new Set(['local', 'phone'])

function visibleCameraSources(sources) {
  return (sources || []).filter((source) => !HIDDEN_CAMERA_SOURCES.has(source.id))
}

const serverUrl = SERVER_URL
const lastUpdate = ref('')
const loadError = ref('')
let timer = null
const cameraSources = ref([])

const stream = reactive({
  online: false,
  fps: 0,
  totalFrames: 0,
  lastFrameAgeMs: -1,
  rtspUrl: '',
  lastError: ''
})

const system = reactive({
  cpuUsage: 0,
  memoryUsage: 0,
  gpuUsage: 0,
  streamOnline: false,
  fps: 0
})

const stats = reactive({
  todayPlateCount: 0,
  todayAlertCount: 0,
  avgVehicleCount: 0,
  deviceCount: 0,
  onlineDeviceCount: 0
})

function formatAge(ms) {
  if (ms < 0) return '无数据'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

async function loadData() {
  try {
    loadError.value = ''
    const [streamData, systemData, statsData, cameraData] = await Promise.all([
      fetchStreamStatus(),
      fetchSystemStatus(),
      fetchStatisticsOverview(),
      fetchCameraSources()
    ])
    Object.assign(stream, streamData)
    Object.assign(system, systemData)
    Object.assign(stats, statsData)
    cameraSources.value = visibleCameraSources(cameraData.sources || [])
    lastUpdate.value = new Date().toLocaleTimeString()
  } catch (e) {
    loadError.value = e.message || '无法连接后端，请确认 start-server.bat 已运行'
  }
}

onMounted(() => {
  loadData()
  timer = setInterval(loadData, 3000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.dashboard {
  max-width: 1200px;
  margin: 0 auto;
}
.status-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}
.fps {
  font-size: 24px;
  font-weight: 600;
  color: #409eff;
}
.desc {
  margin-top: 8px;
}
.error {
  color: #f56c6c;
}
.refresh-btn {
  margin-top: 12px;
}
.stats-row {
  margin-top: 16px;
}
.source-card { margin-top: 16px; }.source-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.source-item { display: flex; align-items: center; justify-content: space-between; padding: 14px; background: #f8fafc; border-radius: 8px; }
.source-item b { display: block; }.source-item span { font-size: 12px; color: #94a3b8; }
.stat-num {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}
.stat-label {
  margin-top: 4px;
  color: #909399;
  font-size: 13px;
}
.alert {
  margin-top: 16px;
}
.tips {
  margin-top: 16px;
}
.tips ol {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
}
</style>
