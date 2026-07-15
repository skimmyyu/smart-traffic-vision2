<template>
  <div class="scene-page">
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>道路监控 · {{ channelName }}</span>
              <el-radio-group v-model="channelId" size="small" @change="switchRoadChannel">
                <el-radio-button
                  v-for="ch in ROAD_CHANNELS"
                  :key="ch.id"
                  :value="ch.id"
                >{{ ch.label }}</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div class="video-wrap">
            <video ref="videoRef" class="video-player" controls autoplay muted playsinline />
            <canvas ref="overlayRef" class="detect-overlay" />
            <div v-if="videoError" class="video-overlay">{{ videoError }}</div>
            <div v-if="bgLearning" class="bg-learning-tip">
              <span class="bg-learning-dot" />
              <span>正在进行背景学习</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>道路异常告警</span>
              <el-button link type="primary" @click="downloadExport('alerts')">导出 CSV</el-button>
            </div>
          </template>
          <el-table :data="roadAlerts" size="small" max-height="480" stripe empty-text="暂无道路异常">
            <el-table-column prop="alertType" label="类型" width="120">
              <template #default="{ row }">
                <el-tag type="danger" size="small">{{ row.alertType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
            <el-table-column prop="occurredAt" label="时间" width="160" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAlerts } from '../api/alert.js'
import { switchModel } from '../api/model.js'
import { switchChannel } from '../api/stream.js'
import { downloadExport } from '../api/export.js'
import { useHlsPlayer } from '../composables/useHlsPlayer.js'
import { getContainMapping } from '../composables/zoneOverlay.js'
import { useDelayedDetections, OVERLAY_TICK_MS } from '../composables/useDelayedDetections.js'
import { connectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

/** 道路异常场景仅开放这四路监控画面 */
const ROAD_CHANNELS = [
  { id: 'live12', label: '道路1 live12', name: '道路1' },
  { id: 'live3', label: '隧道旁道路 live3', name: '隧道旁道路' },
  { id: 'live6', label: '桥入口 live6', name: '桥入口' },
  { id: 'live7', label: '道路2 live7', name: '道路2' },
]

const DEFAULT_ROAD_CHANNEL = ROAD_CHANNELS[0]

const channelId = ref(DEFAULT_ROAD_CHANNEL.id)
const channelName = ref(DEFAULT_ROAD_CHANNEL.name)
const alerts = ref([])
const overlaySync = useDelayedDetections()
const bgLearning = ref(false)
const overlayRef = ref(null)
const { videoRef, videoError, playHls } = useHlsPlayer()
let offLive = null
let overlayTimer = null

const BOX_COLORS = {
  debris: '#e11d48',
  anomaly: '#e11d48',
  person: '#ef4444',
  car: '#22c55e'
}

const roadAlerts = computed(() =>
  alerts.value.filter((a) => a.alertType === 'road_anomaly' || (a.description || '').includes('道路'))
)

async function loadAlerts() {
  alerts.value = await fetchAlerts(100)
}

async function switchRoadChannel(id) {
  const roadChannel = ROAD_CHANNELS.find((ch) => ch.id === id)
  if (!roadChannel) {
    ElMessage.warning('该通道不属于道路异常监控范围')
    return
  }
  try {
    const result = await switchChannel(id)
    channelId.value = id
    channelName.value = result.channelName || roadChannel.name
    overlaySync.clear()
    bgLearning.value = false
    clearOverlay()
    setTimeout(() => playHls(result.hlsUrl), 1500)
    ElMessage.success(`已切换至 ${channelName.value}`)
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

function clearOverlay() {
  const canvas = overlayRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
}

function drawDetections() {
  const video = videoRef.value
  const canvas = overlayRef.value
  if (!video || !canvas) return

  const rect = video.getBoundingClientRect()
  const cssW = Math.max(1, Math.round(rect.width))
  const cssH = Math.max(1, Math.round(rect.height))
  if (canvas.width !== cssW || canvas.height !== cssH) {
    canvas.width = cssW
    canvas.height = cssH
  }

  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, cssW, cssH)

  const dets = overlaySync.detections.value
  if (!dets.length) return

  const srcW = overlaySync.frameSize.width || video.videoWidth || 1920
  const srcH = overlaySync.frameSize.height || video.videoHeight || 1080
  const { scale, offsetX, offsetY } = getContainMapping(cssW, cssH, srcW, srcH)

  ctx.lineWidth = 2
  ctx.font = '12px sans-serif'
  ctx.textBaseline = 'top'

  for (const det of dets) {
    const bbox = det.bbox
    if (!Array.isArray(bbox) || bbox.length < 4) continue
    const [x1, y1, x2, y2] = bbox
    const left = offsetX + x1 * scale
    const top = offsetY + y1 * scale
    const width = Math.max(1, (x2 - x1) * scale)
    const height = Math.max(1, (y2 - y1) * scale)
    const color = BOX_COLORS[det.className] || '#e11d48'
    const nameMap = { debris: '异物', anomaly: '异物' }
    const showName = nameMap[det.className] || det.className || 'obj'
    const label = `${showName}${det.confidence != null ? ` ${(det.confidence * 100).toFixed(0)}%` : ''}`

    ctx.strokeStyle = color
    ctx.fillStyle = color
    ctx.strokeRect(left, top, width, height)

    const textW = ctx.measureText(label).width + 8
    const labelY = Math.max(0, top - 16)
    ctx.globalAlpha = 0.85
    ctx.fillRect(left, labelY, textW, 16)
    ctx.globalAlpha = 1
    ctx.fillStyle = '#fff'
    ctx.fillText(label, left + 4, labelY + 2)
  }
}

onMounted(async () => {
  try {
    await switchRoadChannel(DEFAULT_ROAD_CHANNEL.id)
    await loadAlerts()
    try {
      await switchModel('anomaly')
    } catch {
      /* optional if model service unavailable */
    }
    connectLiveSocket()
    overlaySync.start()
    offLive = onLiveMessage((msg) => {
      if (msg.type === 'alert') loadAlerts()
      if (msg.type === 'detection_result') {
        overlaySync.enqueue(msg)
      }
    })
    overlayTimer = setInterval(() => {
      bgLearning.value = overlaySync.extras.bgLearning
      drawDetections()
    }, OVERLAY_TICK_MS)
    window.addEventListener('resize', drawDetections)
  } catch (e) {
    ElMessage.error(e.message || '加载道路监控失败')
  }
})

onUnmounted(() => {
  if (overlayTimer) clearInterval(overlayTimer)
  window.removeEventListener('resize', drawDetections)
  overlaySync.stop()
  offLive?.()
})
</script>

<style scoped>
.scene-page { max-width: 1280px; margin: 0 auto; }
.card-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.video-wrap { position: relative; background: #000; border-radius: 6px; overflow: hidden; aspect-ratio: 16/9; }
.video-player { width: 100%; height: 100%; object-fit: contain; }
.detect-overlay {
  position: absolute; inset: 0; width: 100%; height: 100%;
  pointer-events: none; z-index: 2;
}
.video-overlay {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: #fff; background: rgba(0,0,0,.6); z-index: 3;
}
.bg-learning-tip {
  position: absolute;
  inset: 0;
  z-index: 4;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  pointer-events: none;
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.6);
}
.bg-learning-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.55);
  animation: bg-pulse 1.2s ease-out infinite;
}
@keyframes bg-pulse {
  0% { transform: scale(0.9); box-shadow: 0 0 0 0 rgba(56, 189, 248, 0.55); }
  70% { transform: scale(1.15); box-shadow: 0 0 0 12px rgba(56, 189, 248, 0); }
  100% { transform: scale(0.9); box-shadow: 0 0 0 0 rgba(56, 189, 248, 0); }
}
</style>
