<template>
  <div class="scene-page">
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>禁停监控 · {{ channelName }}</span>
              <el-select v-model="channelId" size="small" style="width:220px" filterable @change="switchScene">
                <el-option v-for="ch in channelOptions" :key="ch.id" :label="`${ch.name} (${ch.id})`" :value="ch.id" />
              </el-select>
            </div>
          </template>
          <div class="toolbar">
            <el-button
              size="small"
              :type="drawing ? 'warning' : 'primary'"
              @click="toggleDrawing"
            >
              {{ drawing ? '绘制中… 双击结束' : '绘制禁停区' }}
            </el-button>
            <el-button size="small" :disabled="!draftPoints.length" @click="cancelDraft">取消绘制</el-button>
            <el-button size="small" type="success" plain @click="enableParkingMode">启用禁停检测</el-button>
            <span class="dwell-setting">禁停时间 <el-input-number v-model="dwellSeconds" :min="1" :max="3600" size="small" controls-position="right" /> 秒</span>
            <el-button size="small" type="warning" :loading="savingDwell" @click="saveDwell">应用时间</el-button>
            <el-tag size="small" type="info">静止 ≥ {{ dwellSeconds }} 秒告警</el-tag>
          </div>
          <div class="video-wrap">
            <video ref="videoRef" class="video-player" controls autoplay muted playsinline />
            <canvas
              ref="overlayRef"
              class="detect-overlay"
              :class="{ drawing }"
              @click="onCanvasClick"
              @dblclick.prevent="onCanvasDblClick"
              @mousemove="onCanvasMove"
            />
            <div v-if="videoError" class="video-overlay">{{ videoError }}</div>
            <div v-if="drawing" class="draw-hint">单击加点，双击闭合保存（至少 3 点）</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="hover" class="mb-card">
          <template #header>
            <div class="card-header">
              <span>本路禁停区</span>
              <el-button link type="primary" @click="loadZones">刷新</el-button>
            </div>
          </template>
          <el-table :data="zones" size="small" max-height="200" stripe empty-text="尚未绘制禁停区">
            <el-table-column prop="name" label="名称" min-width="100" />
            <el-table-column label="顶点" width="70">
              <template #default="{ row }">{{ pointCount(row) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeZone(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>禁停告警</span>
              <el-button link type="primary" @click="downloadExport('alerts')">导出 CSV</el-button>
            </div>
          </template>
          <el-table :data="parkingAlerts" size="small" max-height="320" stripe empty-text="暂无禁停告警">
            <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
            <el-table-column prop="occurredAt" label="时间" width="160" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchAlerts } from '../api/alert.js'
import { fetchModelParameters, switchModel, updateModelParameters } from '../api/model.js'
import { fetchChannels, switchChannel } from '../api/stream.js'
import { downloadExport } from '../api/export.js'
import { createZone, deleteZone, fetchZones } from '../api/zone.js'
import { useHlsPlayer } from '../composables/useHlsPlayer.js'
import {
  drawZones,
  getContainMapping,
  parseZonePoints,
  screenToNorm
} from '../composables/zoneOverlay.js'
import { useDelayedDetections, OVERLAY_TICK_MS } from '../composables/useDelayedDetections.js'
import { connectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const channelId = ref('live2')
const channelName = ref('停车场出口')
const channelOptions = ref([])
const alerts = ref([])
const zones = ref([])
const drawing = ref(false)
const draftPoints = ref([])
const hoverPoint = ref(null)
const overlaySync = useDelayedDetections()
const overlayRef = ref(null)
const dwellSeconds = ref(20)
const savingDwell = ref(false)
const { videoRef, videoError, playHls } = useHlsPlayer()
let offLive = null
let overlayTimer = null

const parkingAlerts = computed(() =>
  alerts.value.filter((a) => a.alertType === 'parking_violation' || (a.description || '').includes('禁停'))
)

const BOX_COLORS = {
  car: '#22c55e',
  bus: '#3b82f6',
  truck: '#f59e0b',
  motorcycle: '#a855f7',
  bicycle: '#14b8a6'
}

function pointCount(row) {
  return parseZonePoints(row.points).length
}

async function loadAlerts() {
  alerts.value = await fetchAlerts(100)
}

async function loadZones() {
  zones.value = await fetchZones(channelId.value)
  nextTick(() => redraw())
}

async function switchScene(id) {
  offLive?.clear?.()
  try {
    const result = await switchChannel(id)
    channelName.value = result.channelName
    channelId.value = result.channelId || id
    draftPoints.value = []
    drawing.value = false
    overlaySync.clear()
    setTimeout(() => playHls(result.hlsUrl), 1500)
    await loadZones()
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

async function enableParkingMode() {
  try {
    await switchModel('parking')
    ElMessage.success(`已切换为禁停检测（ByteTrack + ${dwellSeconds.value}秒静止告警）`)
  } catch (e) {
    ElMessage.error(e.message || '切换模型失败')
  }
}

async function loadDwell() {
  const params = await fetchModelParameters('parking')
  dwellSeconds.value = Number(params.dwellSeconds || 20)
}

async function saveDwell() {
  savingDwell.value = true
  try {
    const params = await fetchModelParameters('parking')
    params.dwellSeconds = Number(dwellSeconds.value)
    const saved = await updateModelParameters('parking', params)
    dwellSeconds.value = Number(saved.dwellSeconds || dwellSeconds.value)
    ElMessage.success(`禁停告警时间已设置为 ${dwellSeconds.value} 秒，并立即生效`)
  } catch (e) { ElMessage.error(e.message || '保存禁停时间失败') }
  finally { savingDwell.value = false }
}

function toggleDrawing() {
  drawing.value = !drawing.value
  if (!drawing.value) {
    draftPoints.value = []
    hoverPoint.value = null
  }
  redraw()
}

function cancelDraft() {
  draftPoints.value = []
  hoverPoint.value = null
  drawing.value = false
  redraw()
}

function canvasLocalPos(evt) {
  const canvas = overlayRef.value
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  return { x: evt.clientX - rect.left, y: evt.clientY - rect.top }
}

function mappingForCanvas() {
  const video = videoRef.value
  const canvas = overlayRef.value
  if (!video || !canvas) return null
  const rect = video.getBoundingClientRect()
  const cssW = Math.max(1, Math.round(rect.width))
  const cssH = Math.max(1, Math.round(rect.height))
  if (canvas.width !== cssW || canvas.height !== cssH) {
    canvas.width = cssW
    canvas.height = cssH
  }
  const srcW = overlaySync.frameSize.width || video.videoWidth || 1920
  const srcH = overlaySync.frameSize.height || video.videoHeight || 1080
  return { mapping: getContainMapping(cssW, cssH, srcW, srcH), srcW, srcH, cssW, cssH }
}

function onCanvasClick(evt) {
  if (!drawing.value) return
  const pos = canvasLocalPos(evt)
  const meta = mappingForCanvas()
  if (!pos || !meta) return
  const n = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  draftPoints.value = [...draftPoints.value, n]
  redraw()
}

async function onCanvasDblClick() {
  if (!drawing.value || draftPoints.value.length < 3) {
    if (drawing.value) ElMessage.warning('至少需要 3 个点')
    return
  }
  try {
    const name = `禁停区${zones.value.length + 1}`
    await createZone({
      channelId: channelId.value,
      name,
      points: draftPoints.value.map((p) => [p.x, p.y]),
      enabled: true
    })
    ElMessage.success('禁停区已保存')
    draftPoints.value = []
    drawing.value = false
    hoverPoint.value = null
    await loadZones()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

function onCanvasMove(evt) {
  if (!drawing.value || !draftPoints.value.length) return
  const pos = canvasLocalPos(evt)
  const meta = mappingForCanvas()
  if (!pos || !meta) return
  hoverPoint.value = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  redraw()
}

async function removeZone(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.name}」？`, '确认', { type: 'warning' })
    await deleteZone(row.id)
    ElMessage.success('已删除')
    await loadZones()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

function redraw() {
  const canvas = overlayRef.value
  const video = videoRef.value
  if (!canvas || !video) return
  const meta = mappingForCanvas()
  if (!meta) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, meta.cssW, meta.cssH)

  drawZones(ctx, zones.value, meta.mapping, meta.srcW, meta.srcH, {
    draftPoints: draftPoints.value,
    hover: hoverPoint.value
  })

  const dets = overlaySync.detections.value
  ctx.lineWidth = 2
  ctx.font = '12px sans-serif'
  ctx.textBaseline = 'top'
  for (const det of dets) {
    const bbox = det.bbox
    if (!Array.isArray(bbox) || bbox.length < 4) continue
    const [x1, y1, x2, y2] = bbox
    const left = meta.mapping.offsetX + x1 * meta.mapping.scale
    const top = meta.mapping.offsetY + y1 * meta.mapping.scale
    const width = Math.max(1, (x2 - x1) * meta.mapping.scale)
    const height = Math.max(1, (y2 - y1) * meta.mapping.scale)
    const inZone = !!det.inZone
    const color = inZone ? '#ef4444' : BOX_COLORS[det.className] || '#22d3ee'
    const dwellSec = det.dwellMs != null ? Math.floor(Number(det.dwellMs) / 1000) : 0
    const tid = det.trackId != null ? `#${det.trackId}` : ''
    const label = `${det.className || 'car'}${tid}${inZone ? ` 禁停${dwellSec}s` : ''}`

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
  await loadDwell().catch(() => {})
  channelOptions.value = await fetchChannels().catch(() => [])
  const initial = channelOptions.value.some((channel) => channel.id === 'live2') ? 'live2' : channelOptions.value[0]?.id
  if (initial) await switchScene(initial)
  await loadAlerts()
  try {
    await switchModel('parking')
  } catch {
    /* optional */
  }
  connectLiveSocket()
  overlaySync.start()
  offLive = onLiveMessage((msg) => {
    if (msg.type === 'alert') loadAlerts()
    if (msg.type === 'detection_result') {
      overlaySync.enqueue(msg)
    }
  })
  overlayTimer = setInterval(redraw, OVERLAY_TICK_MS)
  window.addEventListener('resize', redraw)
})

onUnmounted(() => {
  if (overlayTimer) clearInterval(overlayTimer)
  window.removeEventListener('resize', redraw)
  overlaySync.stop()
  offLive?.()
})
</script>

<style scoped>
.scene-page { max-width: 1280px; margin: 0 auto; }
.card-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; align-items: center; }
.dwell-setting { display: inline-flex; align-items: center; gap: 6px; color: #475569; font-size: 13px; }.dwell-setting :deep(.el-input-number) { width: 105px; }
.video-wrap { position: relative; background: #000; border-radius: 6px; overflow: hidden; aspect-ratio: 16/9; }
.video-player { width: 100%; height: 100%; object-fit: contain; }
.detect-overlay {
  position: absolute; inset: 0; width: 100%; height: 100%;
  pointer-events: none; z-index: 2;
}
.detect-overlay.drawing { pointer-events: auto; cursor: crosshair; }
.video-overlay {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: #fff; background: rgba(0,0,0,.6); z-index: 3;
}
.draw-hint {
  position: absolute; left: 12px; bottom: 12px; z-index: 4;
  background: rgba(0,0,0,.65); color: #fff; font-size: 12px;
  padding: 4px 10px; border-radius: 4px;
}
.mb-card { margin-bottom: 16px; }
</style>
