<template>
  <div class="scene-page">
    <el-row :gutter="16">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>闸机监控 · {{ channelName }}</span>
              <el-select v-model="channelId" size="small" style="width:220px" filterable @change="switchGateChannel">
                <el-option v-for="ch in channelOptions" :key="ch.id" :label="`${ch.name} (${ch.id})`" :value="ch.id" />
              </el-select>
            </div>
          </template>
          <div class="video-wrap">
            <video ref="videoRef" class="video-player" controls autoplay muted playsinline />
            <canvas ref="overlayRef" class="detect-overlay" />
            <div v-if="videoError" class="video-overlay">{{ videoError }}</div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover" class="decision-card">
          <template #header>最新通行决策</template>
          <div v-if="latestRecord" class="decision-box">
            <div class="plate">{{ latestRecord.plateNumber }}</div>
            <el-tag :type="latestRecord.passResult === 'allow' ? 'success' : 'danger'" size="large">
              {{ latestRecord.passResult === 'allow' ? '允许通行' : '拒绝通行' }}
            </el-tag>
            <div class="time">{{ latestRecord.cameraName || latestRecord.cameraId || '历史记录未标注摄像头' }}</div>
            <div class="time">{{ latestRecord.recognizedAt }}</div>
          </div>
          <el-empty v-else description="等待识别结果" :image-size="64" />
        </el-card>

        <el-card shadow="hover" class="list-card">
          <template #header>
            <div class="card-header">
              <span>识别记录</span>
              <el-button link type="primary" @click="downloadExport('plates')">导出 CSV</el-button>
            </div>
          </template>
          <el-table :data="records" size="small" max-height="320" stripe>
            <el-table-column prop="plateNumber" label="车牌" width="100" />
            <el-table-column prop="passResult" label="结果" width="80">
              <template #default="{ row }">
                <el-tag :type="row.passResult === 'allow' ? 'success' : 'danger'" size="small">
                  {{ row.passResult === 'allow' ? '允许' : '拒绝' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="摄像头" min-width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ row.cameraName || row.cameraId || '未标注' }}</template>
            </el-table-column>
            <el-table-column prop="recognizedAt" label="时间" min-width="140" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchPlateRecords } from '../api/plate.js'
import { switchModel } from '../api/model.js'
import { fetchChannels, switchChannel } from '../api/stream.js'
import { downloadExport } from '../api/export.js'
import { useHlsPlayer } from '../composables/useHlsPlayer.js'
import { getContainMapping, syncPlateOverlays } from '../composables/zoneOverlay.js'
import { useDelayedDetections, OVERLAY_TICK_MS } from '../composables/useDelayedDetections.js'
import { connectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const channelOptions = ref([])
const channelId = ref('live11')
const channelName = ref('停车场入口')
const records = ref([])
const overlaySync = useDelayedDetections({ hlsLatencyMs: 0, delayMs: 0 })
const overlayRef = ref(null)
const { videoRef, videoError, playRtsp } = useHlsPlayer()
let offLive = null
let overlayTimer = null

const BOX_COLORS = {
  car: '#22c55e',
  bus: '#3b82f6',
  truck: '#f59e0b',
  motorcycle: '#a855f7',
  bicycle: '#14b8a6',
  plate: '#f97316'
}

const latestRecord = computed(() => records.value[0] || null)

async function loadRecords() {
  records.value = await fetchPlateRecords(30)
}

async function switchGateChannel(id) {
  offLive?.clear?.()
  overlaySync.clear()
  const gateChannel = channelOptions.value.find((ch) => ch.id === id)
  try {
    const result = await switchChannel(id)
    channelId.value = id
    channelName.value = result.channelName || gateChannel?.name || id
    clearOverlay()
    setTimeout(() => playRtsp({ webrtcUrl: result.webrtcUrl, hlsUrl: result.hlsUrl }), 1500)
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

  const dets = overlaySync.detections.value.filter((d) => d.className !== 'plate')
  const plateDets = syncPlateOverlays(overlaySync.extras.plateOverlays || [], dets)
  const allDets = [...dets, ...plateDets]
  if (!allDets.length) return

  const srcW = overlaySync.frameSize.width || video.videoWidth || 1920
  const srcH = overlaySync.frameSize.height || video.videoHeight || 1080
  const { scale, offsetX, offsetY } = getContainMapping(cssW, cssH, srcW, srcH)

  ctx.lineWidth = 2
  ctx.font = '12px sans-serif'
  ctx.textBaseline = 'top'

  for (const det of allDets) {
    const bbox = det.bbox
    if (!Array.isArray(bbox) || bbox.length < 4) continue
    const [x1, y1, x2, y2] = bbox
    const left = offsetX + x1 * scale
    const top = offsetY + y1 * scale
    const width = Math.max(1, (x2 - x1) * scale)
    const height = Math.max(1, (y2 - y1) * scale)
    const color = BOX_COLORS[det.className] || '#22d3ee'
    const nameMap = { plate: '车牌' }
    const showName = nameMap[det.className] || det.className || 'obj'
    const plateText = det.plateNumber ? ` ${det.plateNumber}` : ''
    const label = `${showName}${plateText}${det.confidence != null ? ` ${(det.confidence * 100).toFixed(0)}%` : ''}`

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
    channelOptions.value = await fetchChannels()
    const initial = channelOptions.value.some((channel) => channel.id === 'live11') ? 'live11' : channelOptions.value[0]?.id
    if (initial) await switchGateChannel(initial)
    await loadRecords()
    try {
      await switchModel('plate_ocr')
    } catch {
      /* optional if model service unavailable */
    }
    connectLiveSocket()
    overlaySync.start()
    offLive = onLiveMessage((msg) => {
      if (msg.type === 'detection_result') {
        overlaySync.enqueue(msg)
      } else if (msg.type === 'plate_event') {
        loadRecords()
      }
    })
    overlayTimer = setInterval(drawDetections, OVERLAY_TICK_MS)
    window.addEventListener('resize', drawDetections)
  } catch (e) {
    ElMessage.error(e.message || '加载闸机页失败')
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
  color: #fff; background: rgba(0,0,0,.6); padding: 16px; text-align: center; z-index: 3;
}
.decision-card { margin-bottom: 16px; }
.decision-box { text-align: center; padding: 12px 0; }
.plate { font-size: 28px; font-weight: 700; margin-bottom: 12px; }
.time { margin-top: 10px; color: #909399; font-size: 13px; }
</style>
