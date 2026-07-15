<template>
  <div class="anomaly-page">
    <div class="page-head">
      <div>
        <h2>场景异常对比</h2>
        <p>冻结正常画面作为基准；出现有效场景变化后立即提示，车辆和行人区域会被动态遮罩。</p>
      </div>
      <div class="head-actions">
        <input ref="videoUploadRef" class="hidden-input" type="file" accept="video/*,.mkv,.avi,.mov,.webm" @change="loadTestVideo" />
        <el-button @click="videoUploadRef?.click()">上传测试视频</el-button>
        <el-select v-model="activeSource" style="width: 250px" filterable @change="changeSource">
          <el-option v-for="item in sources" :key="item.id" :label="item.name" :value="item.id">
            <span>{{ item.name }}</span>
            <span class="source-state">{{ item.online ? '在线' : '等待画面' }}</span>
          </el-option>
        </el-select>
        <el-button v-if="sourceKind === 'phone'" @click="openPhoneCapture">打开手机采集页</el-button>
        <el-button type="danger" :loading="capturing" @click="setBaseline">
          将当前画面设为基准
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="18">
        <el-card shadow="never" class="video-card">
          <template #header>
            <div class="card-head">
              <span>{{ sourceName }} · {{ testVideoMode ? '视频持续对比' : '实时画面' }}</span>
              <div>
                <el-tag :type="status.cameraOnline ? 'success' : 'danger'">
                  {{ status.cameraOnline ? '画面在线' : '等待画面' }}
                </el-tag>
                <el-tag :type="status.ready ? 'success' : 'info'" class="tag-gap">
                  {{ status.ready ? '基准已锁定' : '尚未设置基准' }}
                </el-tag>
              </div>
            </div>
          </template>

          <div class="video-wrap">
            <video v-show="sourceKind !== 'phone'" ref="videoRef" autoplay muted playsinline controls class="preview" />
            <img
              v-show="sourceKind === 'phone'"
              ref="phoneImageRef"
              :src="phoneFrame"
              crossorigin="anonymous"
              class="preview"
              alt="手机实时画面"
            />
            <canvas ref="overlayRef" class="overlay" />
            <div v-if="displayError" class="video-message">
              <strong>{{ displayError }}</strong>
              <span v-if="sourceKind === 'phone'">请先在手机上打开采集页并点击“开始采集”。</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="never">
          <template #header>
            <div class="baseline-title"><span>正常基准画面</span><el-tag v-if="status.ready" type="success" size="small">已冻结</el-tag></div>
          </template>
          <div class="baseline-panel">
            <img v-if="baselineFrame" :src="baselineFrame" class="baseline-image" alt="正常基准画面" />
            <el-empty v-else description="点击设置基准" :image-size="42" />
          </div>
          <el-divider content-position="left">检测说明</el-divider>
          <el-steps direction="vertical" :active="status.ready ? 2 : 1" finish-status="success">
            <el-step title="选择摄像头" description="与监控中心使用同一个当前来源" />
            <el-step title="设置正常基准" description="建议等车辆和行人离开后点击" />
            <el-step title="实时对比" description="固定背景差分，有效变化首次出现即可提示" />
          </el-steps>
          <el-divider />
          <el-alert
            type="success"
            :closable="false"
            title="动态对象抑制已启用"
            description="车辆和行人检测框及其周边不会进入异常差分；有效异物变化无需等待持续多帧即可显示。"
            show-icon
          />
          <el-alert
            class="baseline-tip"
            type="warning"
            :closable="false"
            title="请使用无测试异物的干净路面作为基准"
            description="基准画面中已有的摆件被移走，同样属于相对基准发生变化，也会触发异常。设置基准后请等待数秒，再放入测试物体。"
            show-icon
          />
          <el-divider content-position="left">异常面积辅助筛选</el-divider>
          <div class="area-filter">
            <span>忽略小于整屏</span>
            <el-input-number v-model="anomalyParameters.minAreaPercent" :min="0.01" :max="12" :step="0.05" :precision="2" size="small" controls-position="right" />
            <span>% 的变化</span>
            <el-button type="primary" size="small" :loading="savingAreaFilter" @click="saveAreaFilter">应用</el-button>
            <el-button size="small" :disabled="savingAreaFilter" @click="applyRecommendedArea">默认值 3.00%</el-button>
          </div>
          <p class="area-help">默认忽略小于整屏 3% 的变化，以过滤灯带、反光和树叶等小范围干扰；需要识别更小异物时可手动调低。</p>
          <el-divider content-position="left">最低显示置信度</el-divider>
          <div class="area-filter">
            <span>只显示置信度达到</span>
            <el-input-number v-model="anomalyParameters.minConfidencePercent" :min="0" :max="100" :step="5" :precision="0" size="small" controls-position="right" />
            <span>% 的异常框</span>
            <el-button type="primary" size="small" :loading="savingConfidence" @click="saveConfidenceFilter">应用</el-button>
          </div>
          <p class="area-help">低于阈值的候选区域仍会继续跟踪并增加置信度，达到阈值后才显示。建议先用 60%，误框多就调高，出现太慢就调低。</p>
          <div v-if="baselineInfo.baselineSetAt" class="baseline-info">
            <div>基准来源：{{ baselineInfo.source }}</div>
            <div>分辨率：{{ baselineInfo.width }} × {{ baselineInfo.height }}</div>
            <div>设置时间：{{ formatTime(baselineInfo.baselineSetAt) }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  anomalyBaselineImageUrl,
  cameraFrameUrl,
  captureAnomalyBaseline,
  fetchAnomalyStatus,
  fetchCameraSources,
  selectCameraSource,
  uploadCameraFrame
} from '../api/camera.js'
import { fetchModelParameters, switchModel, updateModelParameters } from '../api/model.js'
import { fetchChannels, fetchCurrentChannel, switchChannel } from '../api/stream.js'
import { clientMediaUrl } from '../config.js'
import { useBrowserCamera } from '../composables/useBrowserCamera.js'
import { useHlsPlayer } from '../composables/useHlsPlayer.js'
import { connectLiveSocket, disconnectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const { videoRef, videoError, playRtsp, destroyPlayer } = useHlsPlayer({ lowLatency: true })
const localCamera = useBrowserCamera(videoRef, 'local')
const phoneImageRef = ref(null)
const overlayRef = ref(null)
const baselineCaptureCanvas = document.createElement('canvas')
const sources = ref([])
const sandboxChannels = ref([])
const phoneCaptureUrls = ref([])
const ipCameraInfo = ref({ hlsUrl: '', webrtcUrl: '', rtspUrl: '', tailscaleHost: '' })
const activeSource = ref('sandbox:live12')
const phoneFrame = ref('')
const capturing = ref(false)
const detections = ref([])
const frameSize = reactive({ width: 1920, height: 1080 })
const status = reactive({ ready: false, cameraOnline: false, baselineSetAt: 0, source: 'sandbox' })
const baselineInfo = reactive({})
const baselineFrame = ref('')
const videoUploadRef = ref(null)
const testVideoMode = ref(false)
const testVideoName = ref('')
const testCanvas = document.createElement('canvas')
const anomalyParameters = reactive({ minAreaPercent: 3, minConfidencePercent: 50 })
const savingAreaFilter = ref(false)
const savingConfidence = ref(false)
let phoneTimer = null
let statusTimer = null
let overlayTimer = null
let offLive = null
let testVideoTimer = null
let testVideoUrl = ''
let testFrameUploading = false
let lastDetections = []
let lastDetectionAt = 0
const DETECTION_HOLD_MS = 200

const sourceKind = computed(() => activeSource.value.startsWith('sandbox:') ? 'sandbox' : activeSource.value)
const sourceName = computed(() => testVideoMode.value ? `测试视频：${testVideoName.value}` : (sources.value.find((s) => s.id === activeSource.value)?.name || '摄像头'))
const displayError = computed(() => {
  if (testVideoMode.value) return ''
  if (sourceKind.value === 'local') return localCamera.error.value
  if (sourceKind.value === 'sandbox' || sourceKind.value === 'ip-camera') return videoError.value
  return status.cameraOnline ? '' : '尚未收到手机画面'
})

function rebuildSources(sourceData) {
  const sandboxState = sourceData.sources?.find((item) => item.id === 'sandbox') || {}
  const external = (sourceData.sources || []).filter((item) => item.id !== 'sandbox')
  sources.value = [
    ...sandboxChannels.value.map((channel) => ({
      id: `sandbox:${channel.id}`,
      name: `沙盘 · ${channel.name} (${channel.id})`,
      online: !!sandboxState.online
    })),
    ...external
  ]
}

function stopPhonePolling() {
  if (phoneTimer) window.clearInterval(phoneTimer)
  phoneTimer = null
}

function startPhonePolling() {
  stopPhonePolling()
  const refresh = () => { phoneFrame.value = cameraFrameUrl('phone') }
  refresh()
  phoneTimer = window.setInterval(refresh, 180)
}

function stopTestVideo() {
  if (testVideoTimer) window.clearInterval(testVideoTimer)
  testVideoTimer = null
  testFrameUploading = false
  testVideoMode.value = false
  testVideoName.value = ''
  if (testVideoUrl) URL.revokeObjectURL(testVideoUrl)
  testVideoUrl = ''
}

async function pushTestVideoFrame() {
  const video = videoRef.value
  if (!testVideoMode.value || !video || video.readyState < 2 || testFrameUploading) return
  const width = video.videoWidth
  const height = video.videoHeight
  if (!width || !height) return
  testCanvas.width = Math.min(width, 1280)
  testCanvas.height = Math.round(height * testCanvas.width / width)
  testCanvas.getContext('2d').drawImage(video, 0, 0, testCanvas.width, testCanvas.height)
  testFrameUploading = true
  testCanvas.toBlob(async (blob) => {
    try { if (blob) await uploadCameraFrame('local', blob) }
    catch { /* 下一帧继续尝试 */ }
    finally { testFrameUploading = false }
  }, 'image/jpeg', 0.82)
}

async function loadTestVideo(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    stopPhonePolling()
    stopTestVideo()
    localCamera.stop()
    destroyPlayer()
    detections.value = []
    await switchModel('anomaly')
    await selectCameraSource('local')
    activeSource.value = 'local'
    testVideoUrl = URL.createObjectURL(file)
    testVideoMode.value = true
    testVideoName.value = file.name
    await nextTick()
    const video = videoRef.value
    video.src = testVideoUrl
    video.loop = true
    video.muted = true
    await video.play()
    // Match backend anomaly tick (~150ms) so diff tracks accumulate quickly.
    testVideoTimer = window.setInterval(pushTestVideoFrame, 120)
    await pushTestVideoFrame()
    await loadStatus()
    ElMessage.success('测试视频已播放：请在无立牌画面点击「设为基准」，再观察立牌出现时的红框')
  } catch (e) {
    stopTestVideo()
    ElMessage.error(e.message || '测试视频加载失败')
  }
}

async function startPreview() {
  stopPhonePolling()
  localCamera.stop()
  destroyPlayer()
  detections.value = []
  if (sourceKind.value === 'sandbox') {
    const current = await fetchCurrentChannel()
    playRtsp({ webrtcUrl: current.webrtcUrl, hlsUrl: clientMediaUrl(current.hlsUrl) })
  } else if (sourceKind.value === 'ip-camera') {
    const hlsUrl = ipCameraInfo.value.hlsUrl
    const webrtcUrl = ipCameraInfo.value.webrtcUrl
    if (hlsUrl || webrtcUrl) {
      playRtsp({ webrtcUrl, hlsUrl: clientMediaUrl(hlsUrl) })
    } else {
      const sourceData = await fetchCameraSources()
      if (sourceData.ipCamera) ipCameraInfo.value = sourceData.ipCamera
      playRtsp({
        webrtcUrl: sourceData.ipCamera?.webrtcUrl,
        hlsUrl: clientMediaUrl(sourceData.ipCamera?.hlsUrl || '')
      })
    }
  } else if (sourceKind.value === 'local') {
    await nextTick()
    await localCamera.start({ facingMode: 'user' })
  } else {
    startPhonePolling()
  }
}

async function changeSource(sourceId) {
  try {
    stopTestVideo()
    if (sourceId.startsWith('sandbox:')) {
      const channelId = sourceId.slice('sandbox:'.length)
      await switchChannel(channelId)
      await selectCameraSource('sandbox')
    } else {
      await selectCameraSource(sourceId)
    }
    await startPreview()
    await loadStatus()
    ElMessage.success(`已切换至${sourceName.value}`)
  } catch (e) {
    ElMessage.error(e.message || '摄像头切换失败')
  }
}

async function captureDisplayFrameBlob() {
  const media = sourceKind.value === 'phone' ? phoneImageRef.value : videoRef.value
  if (!media) return null
  let width = 0
  let height = 0
  for (let i = 0; i < 40; i++) {
    width = media.videoWidth || media.naturalWidth || media.width
    height = media.videoHeight || media.naturalHeight || media.height
    if (width > 0 && height > 0) break
    await new Promise((resolve) => window.setTimeout(resolve, 100))
  }
  if (!width || !height) return null
  const maxWidth = 960
  const scale = Math.min(1, maxWidth / width)
  baselineCaptureCanvas.width = Math.round(width * scale)
  baselineCaptureCanvas.height = Math.round(height * scale)
  baselineCaptureCanvas.getContext('2d', { alpha: false }).drawImage(
    media,
    0,
    0,
    baselineCaptureCanvas.width,
    baselineCaptureCanvas.height
  )
  return new Promise((resolve) => {
    baselineCaptureCanvas.toBlob((blob) => resolve(blob), 'image/jpeg', 0.94)
  })
}

async function setBaseline() {
  capturing.value = true
  try {
    const frameBlob = await captureDisplayFrameBlob()
    if (!frameBlob?.size) {
      if (sourceKind.value === 'sandbox' || sourceKind.value === 'ip-camera') {
        throw new Error('视频尚未就绪，请等画面出现后再设基准（或确认沙盘/摄像头在线）')
      }
      throw new Error('当前没有可截取的画面，请确认摄像头已开启')
    }
    const result = await captureAnomalyBaseline(frameBlob)
    Object.assign(baselineInfo, result)
    Object.assign(status, result)
    baselineFrame.value = anomalyBaselineImageUrl()
    const note = result.dynamicObjects > 0
      ? `，画面内检测到 ${result.dynamicObjects} 个动态对象，建议其离开后重新设置`
      : ''
    ElMessage.success(`基准画面已锁定${note}`)
  } catch (e) {
    ElMessage.error(e.message || '设置基准失败')
  } finally {
    capturing.value = false
  }
}

async function loadStatus() {
  try {
    const [sourceData, anomalyData] = await Promise.all([fetchCameraSources(), fetchAnomalyStatus()])
    rebuildSources(sourceData)
    phoneCaptureUrls.value = sourceData.phoneCaptureUrls || phoneCaptureUrls.value
    if (sourceData.ipCamera) ipCameraInfo.value = sourceData.ipCamera
    Object.assign(status, anomalyData)
    if (anomalyData.baselineSetAt && anomalyData.baselineSetAt !== baselineInfo.baselineSetAt) {
      baselineInfo.baselineSetAt = anomalyData.baselineSetAt
      baselineFrame.value = anomalyBaselineImageUrl()
    }
    if (!anomalyData.ready) baselineFrame.value = ''
  } catch {
    // polling errors are transient
  }
}

function drawOverlay() {
  const canvas = overlayRef.value
  const media = sourceKind.value === 'phone' ? phoneImageRef.value : videoRef.value
  if (!canvas || !media) return
  const rect = media.getBoundingClientRect()
  const w = Math.max(1, Math.round(rect.width))
  const h = Math.max(1, Math.round(rect.height))
  if (canvas.width !== w) canvas.width = w
  if (canvas.height !== h) canvas.height = h
  const ctx = canvas.getContext('2d')
  ctx.clearRect(0, 0, w, h)
  const srcW = frameSize.width || 1920
  const srcH = frameSize.height || 1080
  const scale = Math.min(w / srcW, h / srcH)
  const ox = (w - srcW * scale) / 2
  const oy = (h - srcH * scale) / 2
  ctx.lineWidth = 3
  ctx.font = '14px sans-serif'
  for (const det of detections.value) {
    if (!Array.isArray(det.bbox)) continue
    const [x1, y1, x2, y2] = det.bbox
    const x = ox + x1 * scale
    const y = oy + y1 * scale
    const bw = (x2 - x1) * scale
    const bh = (y2 - y1) * scale
    const confidence = Math.max(0, Math.min(1, Number(det.confidence ?? det.conf ?? 0)))
    const label = `场景异常 · 置信度 ${(confidence * 100).toFixed(1)}%`
    ctx.strokeStyle = '#ef4444'
    ctx.fillStyle = '#ef4444'
    ctx.strokeRect(x, y, bw, bh)
    const labelWidth = ctx.measureText(label).width + 10
    const labelY = Math.max(0, y - 23)
    ctx.globalAlpha = 0.88
    ctx.fillRect(x, labelY, labelWidth, 22)
    ctx.globalAlpha = 1
    ctx.fillStyle = '#fff'
    ctx.fillText(label, x + 5, labelY + 3)
  }
}

async function saveAreaFilter() {
  savingAreaFilter.value = true
  try {
    const updated = await updateModelParameters('anomaly', anomalyParameters)
    Object.assign(anomalyParameters, updated)
    ElMessage.success(`已忽略小于整屏 ${Number(anomalyParameters.minAreaPercent).toFixed(2)}% 的变化`)
  } catch (e) {
    ElMessage.error(e.message || '异常面积阈值保存失败')
  } finally {
    savingAreaFilter.value = false
  }
}

async function applyRecommendedArea() {
  anomalyParameters.minAreaPercent = 3.00
  await saveAreaFilter()
}

async function saveConfidenceFilter() {
  savingConfidence.value = true
  try {
    const updated = await updateModelParameters('anomaly', anomalyParameters)
    Object.assign(anomalyParameters, updated)
    ElMessage.success(`最低显示置信度已设为 ${Number(anomalyParameters.minConfidencePercent).toFixed(0)}%`)
  } catch (e) {
    ElMessage.error(e.message || '异常置信度保存失败')
  } finally {
    savingConfidence.value = false
  }
}

function openPhoneCapture() {
  const urls = phoneCaptureUrls.value.length
    ? phoneCaptureUrls.value
    : [`${window.location.origin}/phone-capture`]
  ElMessageBox.alert(
    `<p>请用手机打开：</p><p style="word-break:break-all;font-weight:600">${urls.join('<br>')}</p><p>手机与电脑需在同一局域网，摄像头权限需要可信 HTTPS。</p>`,
    '手机采集地址',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

function formatTime(ms) {
  return new Date(ms).toLocaleString()
}

onMounted(async () => {
  try {
    await switchModel('anomaly')
    const [sourceData, channels, currentChannel, modelParameters] = await Promise.all([
      fetchCameraSources(), fetchChannels(), fetchCurrentChannel(), fetchModelParameters('anomaly')
    ])
    sandboxChannels.value = channels
    rebuildSources(sourceData)
    phoneCaptureUrls.value = sourceData.phoneCaptureUrls || []
    if (sourceData.ipCamera) ipCameraInfo.value = sourceData.ipCamera
    activeSource.value = sourceData.activeSource === 'sandbox'
      ? `sandbox:${currentChannel.channelId || 'live12'}`
      : (sourceData.activeSource || 'local')
    Object.assign(anomalyParameters, modelParameters)
    await startPreview()
    await loadStatus()
  } catch (e) {
    ElMessage.error(e.message || '异常检测初始化失败')
  }
  connectLiveSocket()
  offLive = onLiveMessage((msg) => {
    if (msg.type !== 'detection_result' || !String(msg.data?.source || '').includes('anomaly')) return
    const incoming = msg.data?.detections || []
    frameSize.width = msg.data?.imageWidth || frameSize.width
    frameSize.height = msg.data?.imageHeight || frameSize.height
    if (incoming.length > 0) {
      lastDetections = incoming
      lastDetectionAt = Date.now()
      detections.value = incoming
      return
    }
    if (Date.now() - lastDetectionAt < DETECTION_HOLD_MS && lastDetections.length > 0) {
      detections.value = lastDetections
      return
    }
    detections.value = []
  })
  statusTimer = window.setInterval(loadStatus, 1500)
  overlayTimer = window.setInterval(drawOverlay, 100)
})

onUnmounted(() => {
  stopTestVideo()
  stopPhonePolling()
  localCamera.stop()
  destroyPlayer()
  if (statusTimer) window.clearInterval(statusTimer)
  if (overlayTimer) window.clearInterval(overlayTimer)
  offLive?.()
  disconnectLiveSocket()
})
</script>

<style scoped>
.anomaly-page { min-height: calc(100vh - 100px); }
.page-head, .card-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p { margin: 0; color: #64748b; }
.head-actions { display: flex; gap: 10px; align-items: center; }
.hidden-input { display: none; }
.source-state { float: right; margin-left: 18px; color: #94a3b8; font-size: 12px; }
.video-card { height: calc(100vh - 145px); }
.video-wrap { position: relative; height: calc(100vh - 230px); min-height: 480px; background: #050b16; overflow: hidden; border-radius: 8px; }
.preview { width: 100%; height: 100%; object-fit: contain; display: block; }
.overlay { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.video-message { position: absolute; inset: 0; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 8px; color: #fff; background: rgba(2, 6, 23, .72); }
.baseline-title { display: flex; justify-content: space-between; align-items: center; }
.baseline-panel { width: 100%; aspect-ratio: 16 / 10; background: #0f172a; border-radius: 8px; overflow: hidden; display: grid; place-items: center; }
.baseline-image { width: 100%; height: 100%; object-fit: contain; background: #0f172a; }
.baseline-panel :deep(.el-empty) { padding: 12px 0 0; color: #cbd5e1; }
.tag-gap { margin-left: 8px; }
.baseline-info { margin-top: 16px; padding: 12px; line-height: 1.9; background: #f8fafc; border-radius: 6px; color: #475569; font-size: 13px; }
.area-filter { display:flex; align-items:center; flex-wrap:wrap; gap:7px; color:#475569; font-size:13px; }
.area-filter .el-input-number { width:105px; }
.area-help { margin:8px 0 0; color:#64748b; font-size:12px; line-height:1.6; }
</style>
