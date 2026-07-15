<template>
  <div class="monitor-page" :class="{ 'video-only': videoOnly }">
    <el-container class="monitor-layout">
      <!-- 左侧：监控点 -->
      <el-aside v-show="!videoOnly" width="240px" class="panel aside-left">
        <div class="panel-title">视频来源</div>
        <el-select v-model="activeCameraSource" class="source-select" @change="handleCameraSourceChange">
          <el-option v-for="source in cameraSources" :key="source.id" :label="source.name" :value="source.id">
            <span>{{ source.name }}</span>
            <el-tag size="small" :type="source.online ? 'success' : 'info'" class="source-tag">
              {{ source.online ? '在线' : '等待' }}
            </el-tag>
          </el-option>
        </el-select>
        <el-button v-if="activeCameraSource === 'phone'" size="small" class="phone-button" @click="openPhoneCapture">
          打开手机采集页
        </el-button>
        <el-button v-if="activeCameraSource === 'ip-camera'" size="small" class="phone-button" @click="showIpCameraGuide">
          Tailscale 配置说明
        </el-button>
        <el-button v-if="activeCameraSource === 'ip-camera'" size="small" type="warning" plain @click="showFacingHint">
          切换前后摄像头
        </el-button>
        <el-divider />
        <div class="panel-title">{{ activeCameraSource === 'sandbox' ? '沙盘监控点' : '来源说明' }}</div>
        <el-scrollbar v-if="activeCameraSource === 'sandbox'" height="calc(100vh - 300px)">
          <el-menu
            :default-active="activeChannel"
            class="channel-menu"
            @select="handleChannelSelect"
          >
            <el-menu-item v-for="item in channels" :key="item.id" :index="item.id">
              <div class="channel-item">
                <span class="channel-name">{{ item.name }}</span>
                <el-tag size="small" type="info">{{ item.id }}</el-tag>
              </div>
              <div class="channel-scene">{{ item.scene }}</div>
            </el-menu-item>
          </el-menu>
        </el-scrollbar>
        <el-alert
          v-else-if="activeCameraSource === 'local'"
          type="info"
          :closable="false"
          title="本机摄像头"
          description="浏览器授权后，可直接查看并进行车辆、车牌识别。"
          show-icon
        />
        <el-alert
          v-else-if="activeCameraSource === 'ip-camera'"
          type="success"
          :closable="false"
          title="手机 IP 摄像头 (Tailscale)"
          :description="ipCameraHint"
          show-icon
        />
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="手机浏览器采集"
          description="在同一局域网的手机上打开采集页并开始发送画面；跨网请改用「手机 IP 摄像头 (Tailscale)」。"
          show-icon
        />
      </el-aside>

      <!-- 中央：视频 -->
      <el-main class="panel center-panel">
        <div class="video-header">
          <div>
            <h2>{{ isRtspSource ? (currentInfo.channelName || '监控画面') : activeSourceName }}</h2>
            <span v-if="!videoOnly" class="sub">
              {{ isRtspSource ? `${currentInfo.channelId} · ${currentInfo.rtspUrl || ipCameraInfo.rtspUrl || ''}` : '浏览器实时采集' }}
            </span>
          </div>
          <div class="video-tags">
            <el-tag :type="cameraOnline ? 'success' : 'danger'">
              {{ cameraOnline ? '画面在线' : '画面离线' }}
            </el-tag>
            <el-tag v-if="!videoOnly" type="primary">{{ stream.fps.toFixed(1) }} FPS</el-tag>
            <el-tag v-if="!videoOnly && activeModel">{{ activeModel.name }}</el-tag>
            <el-button
              size="small"
              :type="videoOnly ? 'warning' : 'primary'"
              plain
              @click="toggleVideoOnly"
            >
              {{ videoOnly ? '退出只看视频' : '只看视频' }}
            </el-button>
          </div>
        </div>

        <div class="video-wrap">
          <video v-show="isRtspSource || activeCameraSource === 'local'" ref="videoRef" class="video-player" controls autoplay muted playsinline />
          <img
            v-show="activeCameraSource === 'phone'"
            :src="phoneFrameUrl"
            class="video-player"
            crossorigin="anonymous"
            alt="手机实时画面"
          />
          <canvas v-show="!videoOnly" ref="overlayRef" class="detect-overlay" />
          <div v-if="currentVideoError" class="video-overlay">
            <p>{{ currentVideoError }}</p>
            <p class="hint">{{ isRtspSource ? '请确认 MediaMTX 已启动，手机 IP 摄像头 App 已开启 RTSP，且 Tailscale 已连接' : '请允许摄像头访问或启动手机采集页' }}</p>
            <el-button type="primary" @click="reloadVideo">重试播放</el-button>
          </div>
          <div v-if="switching" class="video-loading">
            <el-icon class="spin"><Loading /></el-icon>
            <span>切换监控中…</span>
          </div>
          <div v-else-if="bgLearning" class="bg-learning-tip">
            <el-icon class="spin"><Loading /></el-icon>
            <span>正在进行背景学习</span>
          </div>
        </div>

        <!-- 只看视频时保留精简通道切换 -->
        <div v-if="videoOnly && activeCameraSource === 'sandbox'" class="video-only-channels">
          <el-select
            :model-value="activeChannel"
            size="small"
            placeholder="切换监控点"
            style="width: 220px"
            @change="handleChannelSelect"
          >
            <el-option
              v-for="item in channels"
              :key="item.id"
              :label="`${item.name} (${item.id})`"
              :value="item.id"
            />
          </el-select>
        </div>
      </el-main>

      <!-- 右侧：模型 + 状态 -->
      <el-aside v-show="!videoOnly" width="280px" class="panel aside-right">
        <div class="panel-title">车辆与车牌识别</div>
        <el-radio-group v-model="activeModelId" class="model-group" @change="handleModelChange">
          <el-radio
            v-for="model in models"
            :key="model.id"
            :value="model.id"
            border
            class="model-radio"
          >
            <div class="model-name">{{ model.name }}</div>
            <div class="model-desc">{{ model.description }}</div>
          </el-radio>
        </el-radio-group>

        <el-divider />

        <div class="panel-title">流状态</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="HLS">{{ currentInfo.hlsUrl || '-' }}</el-descriptions-item>
          <el-descriptions-item label="累计帧">{{ stream.totalFrames }}</el-descriptions-item>
          <el-descriptions-item label="CPU">{{ system.cpuUsage.toFixed(1) }}%</el-descriptions-item>
          <el-descriptions-item label="内存">{{ system.memoryUsage.toFixed(1) }}%</el-descriptions-item>
        </el-descriptions>

        <el-alert
          class="tip"
          type="info"
          :closable="false"
          show-icon
          title="监控中心仅保留车辆检测与车牌识别"
        />

        <el-divider />

        <div class="panel-title">实时事件</div>
        <el-scrollbar height="160px">
          <div v-for="(item, idx) in liveEvents" :key="idx" class="live-item">
            <el-tag size="small">{{ item.type }}</el-tag>
            <span>{{ item.summary }}</span>
          </div>
          <el-empty v-if="!liveEvents.length" description="等待 WebSocket 推送" :image-size="48" />
        </el-scrollbar>
      </el-aside>
    </el-container>
  </div>
</template>

<script setup>
import { Loading } from '@element-plus/icons-vue'
import Hls from 'hls.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { cameraFrameUrl, fetchCameraSources, selectCameraSource } from '../api/camera.js'
import { fetchModels, switchModel } from '../api/model.js'
import { fetchChannels, fetchCurrentChannel, switchChannel } from '../api/stream.js'
import { fetchStreamStatus, fetchSystemStatus } from '../api/system.js'
import { fetchZones } from '../api/zone.js'
import { drawZones, getContainMapping, syncPlateOverlays } from '../composables/zoneOverlay.js'
import { useDelayedDetections, OVERLAY_TICK_MS } from '../composables/useDelayedDetections.js'
import { connectLiveSocket, disconnectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'
import { clientMediaUrl } from '../config.js'
import { useBrowserCamera } from '../composables/useBrowserCamera.js'

const HIDDEN_CAMERA_SOURCES = new Set(['local', 'phone'])

function visibleCameraSources(sources) {
  return (sources || []).filter((source) => !HIDDEN_CAMERA_SOURCES.has(source.id))
}

const videoRef = ref(null)
const localCamera = useBrowserCamera(videoRef, 'local')
const overlayRef = ref(null)
const cameraSources = ref([])
const phoneCaptureUrls = ref([])
const ipCameraInfo = ref({ rtspUrl: '', tailscaleHost: '', hlsUrl: '', webrtcUrl: '', setupHint: '' })
const activeCameraSource = ref('sandbox')
const phoneFrameUrl = ref('')
const channels = ref([])
const models = ref([])
const activeChannel = ref('live12')
const activeModelId = ref('yolov8n')
const activeModel = ref(null)
const switching = ref(false)
const videoError = ref('')
const videoOnly = ref(false)
const bgLearning = ref(false)
const overlaySync = useDelayedDetections({ hlsLatencyMs: 0, delayMs: 0 })
const parkingZones = ref([])
let hls = null
let webrtcPc = null
let statusTimer = null
let overlayTimer = null
let phoneFrameTimer = null

const activeSourceName = computed(() => cameraSources.value.find((s) => s.id === activeCameraSource.value)?.name || '摄像头')
const isRtspSource = computed(() => activeCameraSource.value === 'sandbox' || activeCameraSource.value === 'ip-camera')
const ipCameraHint = computed(() => {
  const rtsp = ipCameraInfo.value.rtspUrl || `rtsp://${ipCameraInfo.value.tailscaleHost || '100.x.x.x'}:8554/live`
  return `Tailscale 拉流：${rtsp}。前后摄像头请在 IP 摄像头 App 内点击「切换」。`
})
const cameraOnline = computed(() => {
  if (isRtspSource.value) return stream.online
  return !!cameraSources.value.find((s) => s.id === activeCameraSource.value)?.online
})
const currentVideoError = computed(() => {
  if (activeCameraSource.value === 'local') return localCamera.error.value
  if (activeCameraSource.value === 'phone' && !cameraOnline.value) return '尚未收到手机画面'
  return videoError.value
})

function toggleVideoOnly() {
  videoOnly.value = !videoOnly.value
  if (videoOnly.value) {
    clearOverlay()
  } else {
    nextTick(() => drawDetections())
  }
}

watch(videoOnly, () => {
  nextTick(() => {
    window.dispatchEvent(new Event('resize'))
  })
})

const BOX_COLORS = {
  car: '#22c55e',
  bus: '#3b82f6',
  truck: '#f59e0b',
  motorcycle: '#a855f7',
  bicycle: '#14b8a6',
  person: '#ef4444',
  plate: '#f97316',
  debris: '#e11d48',
  anomaly: '#e11d48'
}

const currentInfo = reactive({
  channelId: '',
  channelName: '',
  rtspUrl: '',
  hlsUrl: '',
  webrtcUrl: ''
})

const stream = reactive({
  online: false,
  fps: 0,
  totalFrames: 0
})

const system = reactive({
  cpuUsage: 0,
  memoryUsage: 0
})

const liveEvents = ref([])
let offLive = null
/** Ignore stale WS detections briefly after channel switch */
let ignoreDetectionsUntil = 0
/** Keep "拉流在线" during MediaMTX/HLS settle after switch */
let statusGraceUntil = 0
let switchWatchTimer = null

function clearDetectionState() {
  overlaySync.clear()
  bgLearning.value = false
  clearOverlay()
  // Drop recent detect/plate events so old view text does not linger
  liveEvents.value = liveEvents.value.filter((e) => e.type !== '检测' && e.type !== '车牌')
}

function beginChannelSwitchGrace(statusMs = 8000, detectMs = 1500) {
  const now = Date.now()
  statusGraceUntil = now + statusMs
  ignoreDetectionsUntil = now + detectMs
  clearDetectionState()
  stream.online = true
}

function inStatusGrace() {
  return switching.value || Date.now() < statusGraceUntil
}

function pushLiveEvent(type, summary) {
  liveEvents.value.unshift({ type, summary })
  if (liveEvents.value.length > 8) liveEvents.value.pop()
}

function withCacheBust(url) {
  if (!url) return url
  const sep = url.includes('?') ? '&' : '?'
  return `${url}${sep}_t=${Date.now()}`
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
  if (!isRtspSource.value) {
    clearOverlay()
    return
  }
  if (videoOnly.value) {
    clearOverlay()
    return
  }

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

  const srcW = overlaySync.frameSize.width || video.videoWidth || 1920
  const srcH = overlaySync.frameSize.height || video.videoHeight || 1080
  // object-fit: contain mapping
  const mapping = getContainMapping(cssW, cssH, srcW, srcH)
  const { scale, offsetX, offsetY } = mapping

  if (activeModelId.value === 'parking' && parkingZones.value.length) {
    drawZones(ctx, parkingZones.value, mapping, srcW, srcH)
  }

  const dets = overlaySync.detections.value.filter((d) => d.className !== 'plate')
  const plateDets = syncPlateOverlays(overlaySync.extras.plateOverlays || [], dets)
  const allDets = dets.length || plateDets.length ? [...dets, ...plateDets] : []
  if (!allDets.length) return

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
    const inZone = !!det.inZone
    const color = inZone ? '#ef4444' : (BOX_COLORS[det.className] || '#22d3ee')
    const plateText = det.plateNumber ? ` ${det.plateNumber}` : ''
    const nameMap = { debris: '异物', plate: '车牌' }
    const showName = nameMap[det.className] || det.className || 'obj'
    const tid = det.trackId != null ? `#${det.trackId}` : ''
    const dwell = det.dwellMs != null && inZone ? ` ${Math.floor(det.dwellMs / 1000)}s` : ''
    const label = `${showName}${tid}${plateText}${dwell}${det.confidence != null ? ` ${(det.confidence * 100).toFixed(0)}%` : ''}`

    ctx.strokeStyle = color
    ctx.fillStyle = color
    ctx.strokeRect(left, top, width, height)

    const textW = ctx.measureText(label).width + 8
    const textH = 16
    const labelY = Math.max(0, top - textH)
    ctx.globalAlpha = 0.85
    ctx.fillRect(left, labelY, textW, textH)
    ctx.globalAlpha = 1
    ctx.fillStyle = '#fff'
    ctx.fillText(label, left + 4, labelY + 2)
  }
}

function destroyPlayer() {
  if (webrtcPc) {
    webrtcPc.close()
    webrtcPc = null
  }
  if (hls) {
    hls.destroy()
    hls = null
  }
  if (videoRef.value) {
    videoRef.value.srcObject = null
    videoRef.value.removeAttribute('src')
    videoRef.value.load()
  }
}

async function playWebRtc(whepUrl, { onReady } = {}) {
  videoError.value = ''
  const video = videoRef.value
  if (!video || !whepUrl) {
    onReady?.(false)
    return false
  }
  if (webrtcPc) {
    webrtcPc.close()
    webrtcPc = null
  }
  if (hls) {
    hls.destroy()
    hls = null
  }

  const url = clientMediaUrl(whepUrl)
  let settled = false
  const done = (ok) => {
    if (settled) return
    settled = true
    onReady?.(ok)
  }

  try {
    webrtcPc = new RTCPeerConnection({
      iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
    })
    webrtcPc.addTransceiver('video', { direction: 'recvonly' })
    webrtcPc.addTransceiver('audio', { direction: 'recvonly' })
    webrtcPc.ontrack = (ev) => {
      if (ev.streams?.[0]) {
        video.srcObject = ev.streams[0]
        video.play().catch(() => {})
        done(true)
      }
    }
    const offer = await webrtcPc.createOffer()
    await webrtcPc.setLocalDescription(offer)
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp' },
      body: offer.sdp
    })
    if (!resp.ok) throw new Error(`WHEP HTTP ${resp.status}`)
    const answer = await resp.text()
    await webrtcPc.setRemoteDescription({ type: 'answer', sdp: answer })
    window.setTimeout(() => {
      if (!settled) {
        videoError.value = 'WebRTC 加载超时'
        done(false)
      }
    }, 10000)
    return true
  } catch (e) {
    videoError.value = e?.message || 'WebRTC 播放失败'
    done(false)
    return false
  }
}

function playRtspVideo({ webrtcUrl, hlsUrl }, { onReady } = {}) {
  destroyPlayer()
  videoError.value = ''
  if (webrtcUrl) {
    playWebRtc(webrtcUrl, {
      onReady: (ok) => {
        if (!ok && hlsUrl) {
          playHls(withCacheBust(hlsUrl), { onReady })
        } else {
          onReady?.(ok)
        }
      }
    })
    return
  }
  if (hlsUrl) {
    playHls(withCacheBust(hlsUrl), { onReady })
    return
  }
  onReady?.(false)
}

function playHls(url, { onReady } = {}) {
  if (webrtcPc) {
    webrtcPc.close()
    webrtcPc = null
  }
  if (hls) {
    hls.destroy()
    hls = null
  }
  videoError.value = ''
  const video = videoRef.value
  if (!video || !url) {
    onReady?.(false)
    return
  }
  video.srcObject = null

  let settled = false
  const done = (ok) => {
    if (settled) return
    settled = true
    onReady?.(ok)
  }

  url = clientMediaUrl(url)
  if (Hls.isSupported()) {
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: true,
      liveSyncDuration: 0.5,
      liveMaxLatencyDuration: 2,
      maxLiveSyncPlaybackRate: 1.15
    })
    hls.loadSource(url)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      video.play().catch(() => {})
      done(true)
    })
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        videoError.value = 'HLS 播放失败，请确认 MediaMTX 已运行且 cam1 有画面'
        done(false)
      }
    })
  } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url
    video.addEventListener('loadedmetadata', () => {
      video.play().catch(() => {})
      done(true)
    }, { once: true })
    video.addEventListener('error', () => {
      videoError.value = 'HLS 播放失败'
      done(false)
    }, { once: true })
  } else {
    videoError.value = '当前浏览器不支持 HLS 播放'
    done(false)
  }

  // 超时仍未就绪 → 提示用户（避免只有黑屏+检测框）
  window.setTimeout(() => {
    if (!settled) {
      videoError.value = videoError.value || 'HLS 加载超时，请点「重试播放」或重启 MediaMTX'
      done(false)
    }
  }, 10000)
}

async function reloadVideo() {
  if (isRtspSource.value) {
    playRtspVideo({
      webrtcUrl: activeCameraSource.value === 'ip-camera'
        ? ipCameraInfo.value.webrtcUrl
        : currentInfo.webrtcUrl,
      hlsUrl: currentInfo.hlsUrl || ipCameraInfo.value.hlsUrl
    })
    return
  }
  if (activeCameraSource.value !== 'sandbox') {
    await startCameraPreview()
    return
  }
  const info = await fetchCurrentChannel()
  Object.assign(currentInfo, info)
  playRtspVideo({ webrtcUrl: info.webrtcUrl, hlsUrl: info.hlsUrl })
}

async function loadStatus() {
  try {
    const [streamData, systemData, sourceData] = await Promise.all([
      fetchStreamStatus(),
      fetchSystemStatus(),
      fetchCameraSources()
    ])
    cameraSources.value = visibleCameraSources(sourceData.sources || cameraSources.value)
    phoneCaptureUrls.value = sourceData.phoneCaptureUrls || phoneCaptureUrls.value
    if (sourceData.ipCamera) ipCameraInfo.value = sourceData.ipCamera
    // While switching cameras, RTSP/HLS briefly stalls — don't flash "离线"
    if (inStatusGrace()) {
      stream.online = true
      stream.fps = streamData.fps || stream.fps || 0
      stream.totalFrames = streamData.totalFrames
    } else {
      Object.assign(stream, streamData)
    }
    Object.assign(system, systemData)
  } catch {
    /* ignore polling errors */
  }
}

function restartStatusPolling(intervalMs = 3000) {
  if (statusTimer) clearInterval(statusTimer)
  statusTimer = setInterval(loadStatus, intervalMs)
}

function endSwitchingSoon(delayMs = 200) {
  if (switchWatchTimer) clearTimeout(switchWatchTimer)
  switchWatchTimer = window.setTimeout(() => {
    switching.value = false
    // Allow detections shortly after video is ready
    ignoreDetectionsUntil = Math.min(ignoreDetectionsUntil, Date.now() + 400)
    restartStatusPolling(3000)
  }, delayMs)
}

async function loadParkingZones(channelId) {
  try {
    parkingZones.value = await fetchZones(channelId || activeChannel.value)
  } catch {
    parkingZones.value = []
  }
  nextTick(() => drawDetections())
}

async function loadInitial() {
  const [channelList, modelList, current, sourceData] = await Promise.all([
    fetchChannels(),
    fetchModels(),
    fetchCurrentChannel(),
    fetchCameraSources()
  ])
  channels.value = channelList
  const monitorModelIds = new Set(['yolov8n', 'plate_ocr'])
  models.value = modelList.filter((model) => monitorModelIds.has(model.id))
  activeChannel.value = current.channelId
  let selected = models.value.find((m) => m.active)
  if (selected && !monitorModelIds.has(selected.id)) {
    selected = await switchModel('yolov8n')
    models.value = models.value.map((model) => ({ ...model, active: model.id === 'yolov8n' }))
  } else if (!selected) {
    selected = await switchModel('yolov8n')
    models.value = models.value.map((model) => ({ ...model, active: model.id === 'yolov8n' }))
  }
  activeModelId.value = selected?.id || 'yolov8n'
  activeModel.value = selected || models.value[0] || null
  Object.assign(currentInfo, current)
  cameraSources.value = visibleCameraSources(sourceData.sources || [])
  phoneCaptureUrls.value = sourceData.phoneCaptureUrls || []
  if (sourceData.ipCamera) ipCameraInfo.value = sourceData.ipCamera
  const activeSource = sourceData.activeSource || 'sandbox'
  if (HIDDEN_CAMERA_SOURCES.has(activeSource)) {
    activeCameraSource.value = 'sandbox'
    await selectCameraSource('sandbox')
  } else {
    activeCameraSource.value = activeSource
  }
  await startCameraPreview()
  loadStatus()
  await loadParkingZones(current.channelId)
}

function stopPhonePreview() {
  if (phoneFrameTimer) clearInterval(phoneFrameTimer)
  phoneFrameTimer = null
}

function startPhonePreview() {
  stopPhonePreview()
  const refresh = () => { phoneFrameUrl.value = cameraFrameUrl('phone') }
  refresh()
  phoneFrameTimer = setInterval(refresh, 180)
}

async function startCameraPreview() {
  stopPhonePreview()
  localCamera.stop()
  destroyPlayer()
  clearDetectionState()
  if (activeCameraSource.value === 'sandbox') {
    playRtspVideo({ webrtcUrl: currentInfo.webrtcUrl, hlsUrl: currentInfo.hlsUrl })
  } else if (activeCameraSource.value === 'ip-camera') {
    playRtspVideo({
      webrtcUrl: ipCameraInfo.value.webrtcUrl,
      hlsUrl: ipCameraInfo.value.hlsUrl || currentInfo.hlsUrl
    })
  } else if (activeCameraSource.value === 'local') {
    await nextTick()
    await localCamera.start({ facingMode: 'user' })
  } else {
    startPhonePreview()
  }
}

async function handleCameraSourceChange(sourceId) {
  offLive?.clear?.()
  if (switching.value) return
  switching.value = true
  beginChannelSwitchGrace(12000, 1500)
  restartStatusPolling(800)
  try {
    const result = await selectCameraSource(sourceId)
    if (result?.hlsUrl) Object.assign(currentInfo, result)
    if (result?.rtspUrl && sourceId === 'ip-camera') {
      currentInfo.channelName = result.channelName || '手机 IP 摄像头'
      currentInfo.channelId = 'ip-camera'
      currentInfo.rtspUrl = result.rtspUrl
      currentInfo.hlsUrl = result.hlsUrl
      currentInfo.webrtcUrl = result.webrtcUrl
    }
    if (sourceId === 'sandbox' && result?.channelId) {
      currentInfo.channelId = result.channelId
      currentInfo.channelName = result.channelName || currentInfo.channelName
      currentInfo.rtspUrl = result.rtspUrl || currentInfo.rtspUrl
    }

    stopPhonePreview()
    localCamera.stop()
    destroyPlayer()
    clearDetectionState()

    const needsHls = sourceId === 'sandbox' || sourceId === 'ip-camera'
    if (needsHls) {
      const settleMs = Number(result?.settleMs) || 2000
      await new Promise((resolve) => setTimeout(resolve, settleMs))
      const media = {
        webrtcUrl: result?.webrtcUrl
          || (sourceId === 'ip-camera' ? ipCameraInfo.value.webrtcUrl : currentInfo.webrtcUrl),
        hlsUrl: result?.hlsUrl || currentInfo.hlsUrl || ipCameraInfo.value.hlsUrl
      }
      playRtspVideo(media, {
        onReady: (ok) => {
          if (ok) beginChannelSwitchGrace(6000, 400)
          endSwitchingSoon(ok ? 200 : 800)
        }
      })
    } else {
      await startCameraPreview()
      endSwitchingSoon(300)
    }
    await loadStatus()
    ElMessage.success(`已切换至${activeSourceName.value}`)
  } catch (e) {
    ignoreDetectionsUntil = 0
    statusGraceUntil = 0
    switching.value = false
    restartStatusPolling(3000)
    ElMessage.error(e.message || '视频来源切换失败')
  }
}

function showIpCameraGuide() {
  const host = ipCameraInfo.value.tailscaleHost || '100.71.110.18'
  const rtsp = ipCameraInfo.value.rtspUrl || `rtsp://${host}:8554/live`
  ElMessageBox.alert(
    `<ol style="padding-left:18px;line-height:1.8">
      <li>手机安装 <b>Tailscale</b> 并登录与电脑相同账号（当前手机约 <b>${host}</b>）</li>
      <li>安装 <b>IP 摄像头</b> App，开启 RTSP 服务（默认端口 8554）</li>
      <li>电脑也安装 Tailscale，确保能 ping 通手机 100.x 地址</li>
      <li>本系统会通过 MediaMTX 拉流（若 App 开启鉴权，需在 application.yml 填写账号密码）</li>
      <li>切换前后摄像头：在 IP 摄像头 App 内点击顶部「切换」按钮</li>
      <li><b>若 VLC 报 401：</b>在 App「设置 → 连接/安全」查看 RTSP 用户名密码，或关闭 RTSP 鉴权</li>
      <li>建议勾选 App 内「实时推流」，并取消「无客户端时关闭摄像头」</li>
    </ol>`,
    '手机 IP 摄像头 + Tailscale',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

function showFacingHint() {
  ElMessage.info('请在手机「IP 摄像头」App 内点击顶部「切换」按钮切换前后摄像头，画面会自动更新')
}

function openPhoneCapture() {
  const urls = phoneCaptureUrls.value.length
    ? phoneCaptureUrls.value
    : [`${window.location.origin}/phone-capture`]
  ElMessageBox.alert(
    `<p>请用手机打开以下地址：</p><p style="word-break:break-all;font-weight:600">${urls.join('<br>')}</p><p>手机和电脑必须在同一局域网；移动浏览器还需要可信 HTTPS 才能授权摄像头。</p>`,
    '手机采集地址',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

async function handleChannelSelect(channelId) {
  offLive?.clear?.()
  if (channelId === activeChannel.value || switching.value) return
  switching.value = true
  beginChannelSwitchGrace(9000, 1200)
  restartStatusPolling(800)
  try {
    const result = await switchChannel(channelId)
    activeChannel.value = result.channelId
    Object.assign(currentInfo, result)
    ElMessage.success(`已切换至 ${result.channelName}`)
    clearDetectionState()
    await loadParkingZones(result.channelId)
    // Reload HLS with cache-bust; keep overlay until manifest is ready
    playRtspVideo({ webrtcUrl: result.webrtcUrl, hlsUrl: result.hlsUrl }, {
      onReady: () => {
        beginChannelSwitchGrace(5000, 300)
        endSwitchingSoon(150)
      }
    })
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
    ignoreDetectionsUntil = 0
    statusGraceUntil = 0
    switching.value = false
    restartStatusPolling(3000)
  }
}

async function handleModelChange(modelId) {
  try {
    const model = await switchModel(modelId)
    activeModel.value = model
    activeModelId.value = model.id || modelId
    clearDetectionState()
    beginChannelSwitchGrace(2000, 500)
    ElMessage.success(`已切换模型：${model.name}`)
    await loadModelsOnly()
  } catch (e) {
    ElMessage.error(e.message || '模型切换失败')
  }
}

async function loadModelsOnly() {
  const modelList = await fetchModels()
  const monitorModelIds = new Set(['yolov8n', 'plate_ocr'])
  models.value = modelList.filter((model) => monitorModelIds.has(model.id))
  activeModelId.value = models.value.find((m) => m.active)?.id || activeModelId.value
  activeModel.value = models.value.find((m) => m.active) || activeModel.value
}

onMounted(async () => {
  try {
    await loadInitial()
    restartStatusPolling(3000)
    connectLiveSocket()
    overlaySync.start()
    offLive = onLiveMessage((msg) => {
      if (msg.type === 'detection_result') {
        const resultSource = String(msg.data?.source || '')
        if (!isRtspSource.value && !resultSource.includes('anomaly')) {
          return
        }
        if (Date.now() < ignoreDetectionsUntil) {
          return
        }
        if (msg.data?.detections || msg.data?.summary || msg.data?.vehicleCount != null || msg.data?.plateOverlays) {
          overlaySync.enqueue(msg)
          if (inStatusGrace()) {
            stream.online = true
          }
          const dets = Array.isArray(msg.data?.detections) ? msg.data.detections : []
          const summary =
            msg.data?.summary ||
            `目标 ${msg.data?.vehicleCount ?? dets.length}`
          pushLiveEvent('检测', summary)
        }
      } else if (msg.type === 'plate_event') {
        const plates = Array.isArray(msg.data?.plates) ? msg.data.plates : []
        if (plates.length) {
          for (const p of plates.slice(0, 3)) {
            const decision = p.decision === 'allow' ? '允许' : p.decision === 'deny' ? '拒绝' : ''
            pushLiveEvent('车牌', `${p.plateNumber || '-'}${decision ? ` · ${decision}` : ''}`)
          }
        } else if (msg.data?.plateNumber) {
          const decision = msg.data?.decision === 'allow' ? '允许' : '拒绝'
          pushLiveEvent('车牌', `${msg.data.plateNumber} · ${decision}`)
        }
      } else if (msg.type === 'alert') {
        pushLiveEvent('告警', msg.data?.message || msg.data?.alertType)
      }
    })
    overlayTimer = setInterval(() => {
      bgLearning.value = overlaySync.extras.bgLearning
        && (activeModelId.value === 'anomaly' || overlaySync.extras.source.includes('anomaly'))
      drawDetections()
    }, OVERLAY_TICK_MS)
    window.addEventListener('resize', drawDetections)
  } catch (e) {
    ElMessage.error(e.message || '加载监控页失败')
  }
})

onUnmounted(() => {
  if (statusTimer) clearInterval(statusTimer)
  if (overlayTimer) clearInterval(overlayTimer)
  if (switchWatchTimer) clearTimeout(switchWatchTimer)
  window.removeEventListener('resize', drawDetections)
  offLive?.()
  overlaySync.stop()
  clearOverlay()
  stopPhonePreview()
  localCamera.stop()
  destroyPlayer()
  disconnectLiveSocket()
})
</script>

<style scoped>
.monitor-page {
  height: calc(100vh - 100px);
}
.monitor-page.video-only {
  height: calc(100vh - 100px);
}
.monitor-layout {
  height: 100%;
  gap: 12px;
}
.panel {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  padding: 12px;
}
.panel-title {
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
}
.source-select { width: 100%; }
.source-tag { float: right; margin-left: 14px; }
.phone-button { margin-top: 10px; width: 100%; }
.aside-left,
.aside-right {
  display: flex;
  flex-direction: column;
}
.center-panel {
  display: flex;
  flex-direction: column;
  padding: 16px !important;
  min-width: 0;
}
.video-only .center-panel {
  width: 100%;
}
.video-only .video-wrap {
  min-height: calc(100vh - 220px);
}
.video-only .video-player {
  min-height: calc(100vh - 220px);
}
.video-only-channels {
  margin-top: 10px;
  display: flex;
  justify-content: flex-start;
}
.channel-menu {
  border-right: none;
}
.channel-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.channel-name {
  font-weight: 500;
}
.channel-scene {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  padding-left: 0;
}
.video-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.video-header h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.sub {
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.video-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}
.video-wrap {
  position: relative;
  flex: 1;
  min-height: 420px;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}
.video-player {
  width: 100%;
  height: 100%;
  min-height: 420px;
  object-fit: contain;
  background: #000;
}
.detect-overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 2;
}
.video-overlay,
.video-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  gap: 12px;
  text-align: center;
  padding: 24px;
}
.bg-learning-tip {
  position: absolute;
  inset: 0;
  z-index: 4;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  pointer-events: none;
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.6);
}
.hint {
  font-size: 13px;
  color: #dcdfe6;
}
.spin {
  font-size: 28px;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.model-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.model-radio {
  width: 100%;
  margin-right: 0 !important;
  height: auto !important;
  padding: 10px 12px !important;
}
.model-name {
  font-weight: 500;
}
.model-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.live-item {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 12px;
  padding: 4px 0;
  color: #606266;
}
.tip {
  margin-top: 12px;
}
</style>
