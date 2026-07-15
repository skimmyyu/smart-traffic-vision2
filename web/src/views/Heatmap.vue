<template>
  <div class="heatmap-page">
    <el-row :gutter="16">
      <!-- 俯视路网 -->
      <el-col :span="19">
        <el-card shadow="hover" class="map-card">
          <template #header>
            <div class="card-header">
              <span>沙盘全景路网热力</span>
              <div class="header-actions">
                <el-switch v-model="showAllCameraCoverage" active-text="摄像头区域" @change="redrawMap" />
                <el-button-group>
                  <el-button size="small" @click="zoomBy(-0.2)">－</el-button>
                  <el-button size="small" @click="resetMapView">{{ Math.round(mapZoom * 100) }}%</el-button>
                  <el-button size="small" @click="zoomBy(0.2)">＋</el-button>
                </el-button-group>
                <el-radio-group v-model="mapInteraction" size="small" @change="changeMapInteraction">
                  <el-radio-button value="pan">拖动地图</el-radio-button>
                  <el-radio-button value="edit">编辑区域</el-radio-button>
                </el-radio-group>
                <el-button
                  size="small"
                  :type="mapDrawing && mapDrawMode === 'camera' ? 'warning' : 'success'"
                  @click="toggleCameraCoverageDrawing"
                >
                  {{ mapDrawing && mapDrawMode === 'camera' ? '绘制热力区域中…' : '新增热力区域' }}
                </el-button>
                <el-button
                  size="small"
                  :type="cameraCoverageEditing ? 'warning' : 'info'"
                  @click="toggleCameraCoverageEdit"
                >
                  {{ cameraCoverageEditing ? '编辑热力区域中…' : '修改选中区域' }}
                </el-button>
                <el-button size="small" :disabled="!mapDraft.length" @click="cancelMapDraft">取消</el-button>
              </div>
            </div>
          </template>
          <div class="legend">
            <span class="lg"><i class="swatch c1" />1 辆</span>
            <span class="lg"><i class="swatch c2" />2 辆</span>
            <span class="lg"><i class="swatch c3" />3 辆</span>
            <span class="lg"><i class="swatch c4" />4 辆及以上</span>
            <span class="meta">已建立 {{ mappedRegionCount }} 组一一对应区域 · 当前摄像头 {{ currentCameraCount }} 辆</span>
            <span class="meta db-meta" v-if="dbContext.segmentCount != null">
              DB：路段 {{ dbContext.segmentCount }} · ROI {{ dbContext.roiCount }}
              <template v-if="dbContext.latestLog?.statTime"> · 最近入库 {{ formatDbTime(dbContext.latestLog.statTime) }}</template>
            </span>
            <el-button size="small" type="primary" plain :loading="persisting" @click="saveCongestionToDb">保存统计到数据库</el-button>
            <el-switch v-model="demoMode" size="small" active-text="演示数据" inactive-text="实时统计" @change="redrawMap" />
          </div>
          <div
            ref="mapViewportRef"
            class="map-wrap"
            :class="{ panning: mapPanning }"
            @wheel.prevent="onMapWheel"
            @pointerdown="startMapPan"
            @pointermove="moveMapPan"
            @pointerup="endMapPan"
            @pointerleave="endMapPan"
          >
            <div ref="mapStageRef" class="map-stage" :style="mapStageStyle">
              <img ref="mapImgRef" class="map-img" :src="mapUrl" alt="sandbox map" draggable="false" @load="redrawMap" />
              <canvas
                ref="mapCanvasRef"
                class="map-overlay"
                :class="{ drawing: mapDrawing, editing: mapInteraction === 'edit' }"
                @click="onMapClick"
                @dblclick.prevent="onMapDblClick"
                @pointerdown.stop="onMapEditStart"
                @pointermove.stop="onMapPointerMove"
                @pointerup.stop="endMapEdit"
                @pointerleave="endMapEdit"
              />
               <div v-if="mapDrawing" class="draw-hint">
                 为 {{ channelName }} 新增热力区域：单击加点，双击保存
               </div>
            </div>
            <div class="map-help">每个摄像头可有多个区域 · 左侧热力区域与右侧画面框一一对应</div>
          </div>
          <div v-if="cameraCoverageEditing" class="edit-panel">
            <b>修改 {{ activeRegion?.name }} 的热力区域</b>
            <span class="edit-tip">可拖动整个区域，也可拖动白色顶点改变大小和形状</span>
            <el-button size="small" type="primary" @click="saveCameraCoverageEdit">保存热力区域</el-button>
            <el-button size="small" @click="cancelCameraCoverageEdit">取消修改</el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 当前监控 + ROI -->
      <el-col :span="5">
        <el-card shadow="hover">
          <template #header>
            <div class="card-header">
              <span>监控映射 · {{ channelName }}</span>
              <el-select v-model="channelId" size="small" style="width: 160px" @change="switchScene">
                <el-option
                  v-for="c in channelOptions"
                  :key="c.id"
                  :label="`${c.name} (${c.id})`"
                  :value="c.id"
                />
              </el-select>
            </div>
          </template>
          <div class="toolbar">
            <el-button size="small" type="success" plain @click="enableCongestionMode">启用车辆统计</el-button>
            <el-select v-model="activeRegionId" size="small" placeholder="选择对应区域" style="width: 130px" @change="redrawAll">
              <el-option v-for="region in currentRegions" :key="region.id" :label="region.name" :value="region.id" />
            </el-select>
            <el-button size="small" type="primary" :disabled="!activeRegion" :class="{ 'is-loading': roiDrawing }" @click="toggleVideoRegionDrawing">
              {{ roiDrawing ? '绘制画面框中…' : '绘制/重画画面框' }}
            </el-button>
          </div>
          <div class="video-wrap">
            <video ref="videoRef" class="video-player" controls autoplay muted playsinline />
            <canvas
              ref="videoCanvasRef"
              class="video-overlay"
              :class="{ drawing: roiDrawing }"
              @click="onVideoClick"
              @dblclick.prevent="onVideoDblClick"
              @pointermove="onVideoMove"
            />
            <div v-if="videoError" class="video-error">{{ videoError }}</div>
            <div v-if="roiDrawing" class="draw-hint">为 {{ activeRegion?.name }} 画亮边框：单击加点，双击保存</div>
          </div>
          <div class="region-list">
            <div v-for="region in currentRegions" :key="region.id" class="region-item" :class="{ active: region.id === activeRegionId }" @click="selectCameraRegion(region.id)">
              <span><b>{{ region.name }}</b><small>{{ region.videoPoints?.length >= 3 ? `${region.count || 0} 辆 · 已一一对应` : '等待绘制画面框' }}</small></span>
              <el-button link type="danger" @click.stop="removeCameraRegion(region.id)">删除</el-button>
            </div>
            <el-empty v-if="!currentRegions.length" description="请先在左侧新增热力区域" :image-size="45" />
          </div>
          <el-divider content-position="left">选择摄像头并绘制区域</el-divider>
          <el-scrollbar height="250px" class="camera-list">
            <div
              v-for="camera in demoCameras"
              :key="camera.id"
              class="camera-bind-item"
              :class="{ active: camera.id === channelId }"
              @click="switchScene(camera.id)"
            >
              <span class="camera-code">{{ camera.id }}</span>
              <span class="camera-bind-main"><b>{{ camera.name }}</b><small>{{ camera.regions?.length ? `${camera.regions.length} 个对应区域 · ${cameraVehicleCount(camera.id)} 辆` : '尚未建立区域' }}</small></span>
              <i :style="{ background: cameraCountColor(cameraVehicleCount(camera.id)) }" />
            </div>
          </el-scrollbar>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { switchModel } from '../api/model.js'
import {
  createCameraRoi,
  createRoadSegment,
  deleteCameraRoi,
  deleteRoadSegment,
  fetchCameraRois,
  fetchRoadCongestion,
  fetchRoadCongestionDbContext,
  fetchLatestRoadSnapshot,
  fetchRoadSegments,
  persistRoadCongestion,
  updateCameraRoi,
  updateRoadSegment
} from '../api/road.js'
import { fetchChannels, switchChannel } from '../api/stream.js'
import { useHlsPlayer } from '../composables/useHlsPlayer.js'
import {
  getContainMapping,
  parseZonePoints,
  screenToNorm,
  normToScreen
} from '../composables/zoneOverlay.js'
import { useDelayedDetections, OVERLAY_TICK_MS } from '../composables/useDelayedDetections.js'
import { connectLiveSocket, onLiveMessage } from '../ws/liveSocket.js'

const mapUrl = '/sandbox-map-demo.svg?v=10'
const MAP_LAYOUT_VERSION = 'sandbox-topology-20260714-v5'
const ROI_PLACEHOLDER = [[0.02, 0.02], [0.03, 0.02], [0.025, 0.03]]
function parseJsonPoints(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function regionFromPair(roi, seg) {
  return {
    id: `pair-${roi.id}`,
    segmentId: Number(seg?.id ?? roi.segmentId),
    roiId: Number(roi.id),
    name: roi.name || seg?.name || '区域',
    mapPoints: parseJsonPoints(seg?.mapPoints),
    videoPoints: parseJsonPoints(roi.points),
    count: 0
  }
}

function regionDisplayCount(region) {
  if (demoMode.value) return Math.max(0, Number(region.count) || 0)
  if (region?.segmentId != null) return liveCounts.value[region.segmentId]?.count ?? 0
  return Math.max(0, Number(region.count) || 0)
}

function applyCongestionToRegions() {
  for (const camera of CAMERA_LAYOUT) {
    for (const region of camera.regions || []) {
      if (region.segmentId != null) {
        region.count = liveCounts.value[region.segmentId]?.count ?? 0
      }
    }
  }
}

async function hydrateAllRegionsFromDb() {
  await loadSegments()
  await Promise.all(CAMERA_LAYOUT.map(async (camera) => {
    const roiList = await fetchCameraRois(camera.id).catch(() => [])
    camera.regions = roiList.map((roi) => {
      const seg = segments.value.find((s) => Number(s.id) === Number(roi.segmentId))
      return regionFromPair(roi, seg)
    })
  }))
  applyCongestionToRegions()
  redrawMap()
}

async function hydrateCurrentCameraRegions() {
  const camera = currentCamera.value
  if (!camera) return
  const roiList = await fetchCameraRois(channelId.value).catch(() => [])
  camera.regions = roiList.map((roi) => {
    const seg = segments.value.find((s) => Number(s.id) === Number(roi.segmentId))
    return regionFromPair(roi, seg)
  })
  applyCongestionToRegions()
}
const mapImgRef = ref(null)
const mapCanvasRef = ref(null)
const mapViewportRef = ref(null)
const mapStageRef = ref(null)
const videoCanvasRef = ref(null)
const { videoRef, videoError, playHls } = useHlsPlayer()

const channelId = ref('live12')
const channelName = ref('道路1')
const channelOptions = ref([])
const segments = ref([])
const rois = ref([])
const liveCounts = ref({}) // segmentId -> { count, level }
const vehicleCount = ref(0)
const dbContext = ref({ segmentCount: 0, roiCount: 0, latestLog: null })
const persisting = ref(false)

const mapDrawing = ref(false)
const mapDrawMode = ref('segment')
const mapDraft = ref([])
const mapHover = ref(null)
const roiDrawing = ref(false)
const roiDraft = ref([])
const roiHover = ref(null)
const bindSegmentId = ref(null)
const demoMode = ref(false)
const showAllCameraCoverage = ref(true)
const mapZoom = ref(1)
const mapPan = reactive({ x: 0, y: 0 })
const mapPanning = ref(false)
const mapInteraction = ref('pan')
const videoInteraction = ref('view')
const selectedSegmentId = ref(null)
const selectedRoiId = ref(null)
const activeRegionId = ref(null)
const cameraCoverageEditing = ref(false)
const cameraEditPoints = ref([])
const mapEditPoints = ref([])
const roiEditPoints = ref([])
const mapEditForm = reactive({ name: '', capacity: 4 })
const roiEditForm = reactive({ name: '', segmentId: null })
const savingMapEdit = ref(false)
const savingRoiEdit = ref(false)
let panStart = null
let mapEditDrag = null
let cameraEditDrag = null
let roiEditDrag = null

const DEMO_SEGMENTS = [
  { name: '高架桥路段', capacity: 6, mapPoints: [[0.535,0.045],[0.825,0.045],[0.920,0.100],[0.920,0.550],[0.828,0.550],[0.828,0.205],[0.800,0.180],[0.560,0.180]] },
  { name: '道路1·中央主干道', capacity: 8, mapPoints: [[0.414,0.176],[0.555,0.176],[0.555,0.826],[0.414,0.826]] },
  { name: '道路2·西侧道路', capacity: 4, mapPoints: [[0.128,0.076],[0.190,0.076],[0.190,0.916],[0.128,0.916]] },
  { name: '道路3·东侧道路', capacity: 6, mapPoints: [[0.828,0.550],[0.921,0.550],[0.921,0.826],[0.828,0.826]] },
  { name: '南侧主干道', capacity: 10, mapPoints: [[0.552,0.574],[0.923,0.574],[0.923,0.822],[0.552,0.822]] },
  { name: '隧道路段', capacity: 3, mapPoints: [[0.042,0.000],[0.107,0.000],[0.107,1.000],[0.042,1.000]] },
  { name: '停车场通道', capacity: 5, mapPoints: [[0.208,0.344],[0.397,0.344],[0.397,0.594],[0.208,0.594]] },
  { name: '中央十字路口', capacity: 8, mapPoints: [[0.414,0.574],[0.555,0.574],[0.555,0.774],[0.414,0.774]] },
  { name: '停车场B通道', capacity: 6, mapPoints: [[0.127,0.622],[0.555,0.622],[0.555,0.774],[0.127,0.774]] },
  { name: '停车场后方双车道', capacity: 4, mapPoints: [[0.128,0.238],[0.444,0.238],[0.444,0.330],[0.128,0.330]] },
  { name: '后方高架引道', capacity: 5, mapPoints: [[0.072,0.078],[0.560,0.078],[0.560,0.174],[0.072,0.174]] },
  { name: '停车位A-01', capacity: 1, mapPoints: [[0.219,0.362],[0.268,0.362],[0.268,0.420],[0.219,0.420]] },
  { name: '停车位A-02', capacity: 1, mapPoints: [[0.278,0.362],[0.327,0.362],[0.327,0.420],[0.278,0.420]] },
  { name: '停车位A-03', capacity: 1, mapPoints: [[0.337,0.362],[0.386,0.362],[0.386,0.420],[0.337,0.420]] },
  { name: '停车位B-01', capacity: 1, mapPoints: [[0.219,0.437],[0.268,0.437],[0.268,0.495],[0.219,0.495]] },
  { name: '停车位B-02', capacity: 1, mapPoints: [[0.278,0.437],[0.327,0.437],[0.327,0.495],[0.278,0.495]] },
  { name: '停车位B-03', capacity: 1, mapPoints: [[0.337,0.437],[0.386,0.437],[0.386,0.495],[0.337,0.495]] },
  { name: '停车位C-01', capacity: 1, mapPoints: [[0.219,0.512],[0.268,0.512],[0.268,0.570],[0.219,0.570]] },
  { name: '停车位C-02', capacity: 1, mapPoints: [[0.278,0.512],[0.327,0.512],[0.327,0.570],[0.278,0.570]] },
  { name: '停车位C-03', capacity: 1, mapPoints: [[0.337,0.512],[0.386,0.512],[0.386,0.570],[0.337,0.570]] }
]

const CAMERA_LAYOUT = reactive([
  { id: 'live1', name: '桥面', regions: [] },
  { id: 'live5', name: '桥出口', regions: [] },
  { id: 'live6', name: '桥入口', regions: [] },
  { id: 'live12', name: '道路1', regions: [] },
  { id: 'live3', name: '隧道旁道路', regions: [] },
  { id: 'live7', name: '道路2', regions: [] },
  { id: 'live10', name: '道路3', regions: [] },
  { id: 'live8', name: '隧道出口', regions: [] },
  { id: 'live9', name: '隧道入口', regions: [] },
  { id: 'live2', name: '停车场出口', regions: [] },
  { id: 'live11', name: '停车场入口', regions: [] },
  { id: 'live4', name: '道路4', regions: [] }
])
const DEMO_COUNTS = { '高架桥路段': 2, '道路1·中央主干道': 5, '道路2·西侧道路': 2, '道路3·东侧道路': 3, '南侧主干道': 4, '隧道路段': 2, '停车场通道': 4, '中央十字路口': 3, '停车场B通道': 2, '停车场后方双车道': 1, '后方高架引道': 3, '停车位A-01': 1, '停车位A-02': 0, '停车位A-03': 1, '停车位B-01': 0, '停车位B-02': 1, '停车位B-03': 0, '停车位C-01': 1, '停车位C-02': 0, '停车位C-03': 1 }

const overlaySync = useDelayedDetections()
const cameraCounts = ref({})
let offLive = null
let redrawTimer = null
let onResize = null

const channelNames = computed(() => {
  const m = {}
  for (const c of channelOptions.value) m[c.id] = c.name
  return m
})
const demoCameras = computed(() => CAMERA_LAYOUT.map((item) => ({
  ...item,
  name: channelOptions.value.find((channel) => channel.id === item.id)?.name || item.name
})))
const currentCamera = computed(() => CAMERA_LAYOUT.find((item) => item.id === channelId.value))
const currentRegions = computed(() => currentCamera.value?.regions || [])
const activeRegion = computed(() => currentRegions.value.find((region) => region.id === activeRegionId.value) || null)
const selectedSegment = computed(() => segments.value.find((item) => Number(item.id) === Number(selectedSegmentId.value)))
const selectedRoi = computed(() => rois.value.find((item) => Number(item.id) === Number(selectedRoiId.value)))
const mapStageStyle = computed(() => ({ transform: `translate(${mapPan.x}px, ${mapPan.y}px) scale(${mapZoom.value})` }))
const mappedRegionCount = computed(() => CAMERA_LAYOUT.reduce((sum, camera) => sum + (camera.regions?.length || 0), 0))
const currentCameraCount = computed(() => cameraVehicleCount(channelId.value))

function cameraVehicleCount(id) {
  const camera = CAMERA_LAYOUT.find((item) => item.id === id)
  const paired = camera?.regions?.filter((region) => region.videoPoints?.length >= 3) || []
  if (paired.length) return paired.reduce((sum, region) => sum + Math.max(0, Number(region.count) || 0), 0)
  return Math.max(0, Number(cameraCounts.value[id]) || 0)
}

function cameraCountColor(count) {
  const value = Math.max(0, Number(count) || 0)
  if (value <= 0) return 'rgba(0, 0, 0, 0)'
  if (value === 1) return 'rgba(34, 197, 94, 0.62)'
  if (value === 2) return 'rgba(250, 204, 21, 0.70)'
  if (value === 3) return 'rgba(249, 115, 22, 0.76)'
  return 'rgba(239, 68, 68, 0.82)'
}

function displayCount(segment) {
  return demoMode.value ? (DEMO_COUNTS[segment.name] || 0) : (liveCounts.value[segment.id]?.count ?? 0)
}

function segmentCount(id) {
  const segment = segments.value.find((item) => item.id === id)
  return segment ? displayCount(segment) : 0
}

function displayLevel(segment) {
  if (!demoMode.value) return liveCounts.value[segment.id]?.level ?? 0
  return Math.min(1, displayCount(segment) / Math.max(1, segment.capacity || 4))
}

function segmentColorByName(name) {
  const segment = segments.value.find((item) => item.name === name)
  return segment ? cameraCountColor(displayCount(segment)) : 'rgba(0, 0, 0, 0)'
}

function segmentColorById(id) {
  const segment = segments.value.find((item) => Number(item.id) === Number(id))
  return segment ? cameraCountColor(displayCount(segment)) : 'rgba(0, 0, 0, 0)'
}

function segmentName(id) {
  return segments.value.find((s) => s.id === id)?.name || `#${id}`
}

function levelColor(level) {
  const t = Math.min(1, Math.max(0, Number(level) || 0))
  if (t <= 0) return 'rgba(0, 0, 0, 0)'
  if (t < 0.25) return 'rgba(34, 197, 94, 0.20)'
  if (t < 0.5) return 'rgba(234, 179, 8, 0.24)'
  if (t < 0.75) return 'rgba(249, 115, 22, 0.28)'
  return 'rgba(239, 68, 68, 0.34)'
}

async function loadSegments() {
  segments.value = await fetchRoadSegments()
  redrawMap()
}

async function loadRois() {
  rois.value = await fetchCameraRois(channelId.value)
  redrawVideo()
  redrawMap()
}

async function loadCongestion() {
  try {
    const data = await fetchRoadCongestion()
    if (data && (Array.isArray(data.segments) || data.vehicleCount != null)) {
      applyCongestion(data)
      return
    }
  } catch {
    /* memory snapshot empty */
  }
  try {
    const snap = await fetchLatestRoadSnapshot()
    if (snap) {
      applyCongestion(snap)
    }
  } catch {
    /* no persisted snapshot */
  }
}

function applyCongestion(data) {
  if (!data) return
  vehicleCount.value = data.vehicleCount ?? 0
  const map = {}
  for (const s of data.segments || []) {
    map[s.id] = { count: s.count ?? 0, level: s.level ?? 0 }
  }
  liveCounts.value = map
  applyCongestionToRegions()
  redrawMap()
}

async function loadDbContext() {
  try {
    dbContext.value = await fetchRoadCongestionDbContext()
  } catch {
    dbContext.value = { segmentCount: segments.value.length, roiCount: 0, latestLog: null }
  }
}

async function saveCongestionToDb() {
  persisting.value = true
  try {
    const result = await persistRoadCongestion()
    applyCongestion(result)
    await loadDbContext()
    ElMessage.success('路段车数已写入 congestion_logs')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    persisting.value = false
  }
}

function formatDbTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleString()
}

async function switchScene(id) {
  try {
    offLive?.clear?.()
    cancelRoiEdit()
    cancelCameraCoverageEdit()
    const result = await switchChannel(id)
    channelId.value = result.channelId || id
    channelName.value = result.channelName || channelNames.value[id] || id
    activeRegionId.value = currentCamera.value?.regions?.[0]?.id || null
    roiDraft.value = []
    roiDrawing.value = false
    overlaySync.clear()
    await loadRois()
    setTimeout(() => playHls(result.hlsUrl), 1500)
    redrawMap()
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

async function enableCongestionMode() {
  try {
    await switchModel('congestion')
    ElMessage.success('已启用拥堵热力检测')
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

function toggleMapDrawing() {
  const starting = !mapDrawing.value || mapDrawMode.value !== 'segment'
  mapDrawMode.value = 'segment'
  mapDrawing.value = starting
  if (mapDrawing.value) {
    cancelCameraCoverageEdit()
    mapDraft.value = []
    mapHover.value = null
    mapInteraction.value = 'edit'
    cancelMapEdit()
  }
  if (!mapDrawing.value) {
    mapDraft.value = []
    mapHover.value = null
  }
  redrawMap()
}

function toggleCameraCoverageDrawing() {
  const starting = !mapDrawing.value || mapDrawMode.value !== 'camera'
  mapDrawMode.value = 'camera'
  mapDrawing.value = starting
  mapDraft.value = []
  mapHover.value = null
  if (mapDrawing.value) {
    cancelCameraCoverageEdit()
    mapInteraction.value = 'edit'
    cancelMapEdit()
    ElMessage.info(`正在为 ${channelName.value} 绘制地图覆盖区`)
  }
  redrawMap()
}

function toggleCameraCoverageEdit() {
  if (cameraCoverageEditing.value) {
    cancelCameraCoverageEdit()
    return
  }
  const points = parseZonePoints(activeRegion.value?.mapPoints)
  if (!activeRegion.value || points.length < 3) {
    ElMessage.warning('请先选择要修改的热力区域')
    return
  }
  cancelMapEdit()
  mapDrawing.value = false
  mapDraft.value = []
  mapHover.value = null
  mapInteraction.value = 'edit'
  showAllCameraCoverage.value = true
  cameraEditPoints.value = clonePoints(points)
  cameraCoverageEditing.value = true
  redrawMap()
}

async function saveCameraCoverageEdit() {
  if (!activeRegion.value || cameraEditPoints.value.length < 3 || !activeRegion.value.segmentId) return
  try {
    const mapPoints = cameraEditPoints.value.map((point) => [point.x, point.y])
    await updateRoadSegment(activeRegion.value.segmentId, { mapPoints })
    activeRegion.value.mapPoints = mapPoints
    cameraCoverageEditing.value = false
    cameraEditPoints.value = []
    cameraEditDrag = null
    await loadSegments()
    await hydrateCurrentCameraRegions()
    redrawMap()
    ElMessage.success(`${channelName.value} 的地图对应区域已保存到数据库`)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

function cancelCameraCoverageEdit() {
  cameraCoverageEditing.value = false
  cameraEditPoints.value = []
  cameraEditDrag = null
  redrawMap()
}

function cancelMapDraft() {
  mapDraft.value = []
  mapHover.value = null
  mapDrawing.value = false
  redrawMap()
}

function redrawAll() {
  redrawMap()
  redrawVideo()
}

function selectCameraRegion(id) {
  activeRegionId.value = id
  cancelCameraCoverageEdit()
  roiDrawing.value = false
  roiDraft.value = []
  roiHover.value = null
  redrawAll()
}

async function removeCameraRegion(id) {
  const camera = currentCamera.value
  if (!camera) return
  const region = (camera.regions || []).find((item) => item.id === id)
  if (!region) return
  try {
    await ElMessageBox.confirm(`删除「${region.name}」及其数据库映射？`, '确认', { type: 'warning' })
    if (region.roiId) await deleteCameraRoi(region.roiId)
    if (region.segmentId) await deleteRoadSegment(region.segmentId)
    camera.regions = (camera.regions || []).filter((item) => item.id !== id)
    activeRegionId.value = camera.regions[0]?.id || null
    await loadSegments()
    await loadRois()
    await loadCongestion()
    redrawAll()
    ElMessage.success('已删除路段与 ROI 映射')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

function toggleVideoRegionDrawing() {
  if (!activeRegion.value) return ElMessage.warning('请先选择一个热力区域')
  roiDrawing.value = !roiDrawing.value
  roiDraft.value = []
  roiHover.value = null
  redrawVideo()
}

function toggleRoiDrawing() {
  if (!bindSegmentId.value) {
    ElMessage.warning('请先选择要绑定的路段')
    return
  }
  roiDrawing.value = !roiDrawing.value
  if (roiDrawing.value) {
    videoInteraction.value = 'edit'
    cancelRoiEdit()
  }
  if (!roiDrawing.value) {
    roiDraft.value = []
    roiHover.value = null
  }
  redrawVideo()
}

function changeMapInteraction(mode) {
  if (mode === 'pan') {
    mapDrawing.value = false
    mapDraft.value = []
    mapHover.value = null
    cancelMapEdit()
    cancelCameraCoverageEdit()
  }
  redrawMap()
}

function changeVideoInteraction(mode) {
  if (mode === 'view') {
    roiDrawing.value = false
    roiDraft.value = []
    roiHover.value = null
    cancelRoiEdit()
  }
  redrawVideo()
}

function zoomBy(delta) {
  mapZoom.value = Math.min(3, Math.max(0.8, Number((mapZoom.value + delta).toFixed(2))))
}

function resetMapView() {
  mapZoom.value = 1
  mapPan.x = 0
  mapPan.y = 0
}

function onMapWheel(event) {
  zoomBy(event.deltaY < 0 ? 0.15 : -0.15)
}

function startMapPan(event) {
  if (mapInteraction.value !== 'pan' || mapDrawing.value || event.button !== 0) return
  mapPanning.value = true
  panStart = { x: event.clientX, y: event.clientY, px: mapPan.x, py: mapPan.y }
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function moveMapPan(event) {
  if (!mapPanning.value || !panStart) return
  mapPan.x = panStart.px + event.clientX - panStart.x
  mapPan.y = panStart.py + event.clientY - panStart.y
}

function endMapPan() {
  mapPanning.value = false
  panStart = null
}

function mapMeta() {
  const img = mapImgRef.value
  const canvas = mapCanvasRef.value
  if (!img || !canvas || !img.naturalWidth) return null
  const cssW = Math.max(1, Math.round(img.clientWidth))
  const cssH = Math.max(1, Math.round(img.clientHeight))
  if (canvas.width !== cssW || canvas.height !== cssH) {
    canvas.width = cssW
    canvas.height = cssH
  }
  const srcW = img.naturalWidth
  const srcH = img.naturalHeight
  // object-fit: contain equivalent for img filling wrap
  const mapping = getContainMapping(cssW, cssH, srcW, srcH)
  return { mapping, srcW, srcH, cssW, cssH }
}

function videoMeta() {
  const video = videoRef.value
  const canvas = videoCanvasRef.value
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

function localPos(evt, el) {
  const rect = el.getBoundingClientRect()
  return {
    x: (evt.clientX - rect.left) * (el.width || el.clientWidth) / Math.max(1, rect.width),
    y: (evt.clientY - rect.top) * (el.height || el.clientHeight) / Math.max(1, rect.height)
  }
}

function pointInPolygon(point, points) {
  let inside = false
  for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
    const a = points[i]
    const b = points[j]
    const crossed = ((a.y > point.y) !== (b.y > point.y)) &&
      (point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y) || 1e-9) + a.x)
    if (crossed) inside = !inside
  }
  return inside
}

function clonePoints(points) {
  return points.map((point) => ({ x: Number(point.x), y: Number(point.y) }))
}

function movePointsWithinFrame(origin, dx, dy) {
  const minX = Math.min(...origin.map((p) => p.x))
  const maxX = Math.max(...origin.map((p) => p.x))
  const minY = Math.min(...origin.map((p) => p.y))
  const maxY = Math.max(...origin.map((p) => p.y))
  const safeDx = Math.max(-minX, Math.min(1 - maxX, dx))
  const safeDy = Math.max(-minY, Math.min(1 - maxY, dy))
  return origin.map((point) => ({ x: point.x + safeDx, y: point.y + safeDy }))
}

function nearestVertex(points, pos, meta, radius = 12) {
  let best = -1
  let distance = radius
  points.forEach((point, index) => {
    const screen = normToScreen(point.x, point.y, meta.mapping, meta.srcW, meta.srcH)
    const d = Math.hypot(screen.x - pos.x, screen.y - pos.y)
    if (d <= distance) { best = index; distance = d }
  })
  return best
}

function selectSegment(row) {
  if (!row) return
  cancelCameraCoverageEdit()
  selectedSegmentId.value = row.id
  mapEditPoints.value = clonePoints(parseZonePoints(row.mapPoints))
  mapEditForm.name = row.name || ''
  mapEditForm.capacity = row.capacity || 4
  mapInteraction.value = 'edit'
  mapDrawing.value = false
  redrawMap()
}

function cancelMapEdit() {
  selectedSegmentId.value = null
  mapEditPoints.value = []
  mapEditDrag = null
  redrawMap()
}

async function saveMapEdit() {
  if (!selectedSegment.value || mapEditPoints.value.length < 3) return
  if (!mapEditForm.name.trim()) return ElMessage.warning('请填写地图区域名称')
  savingMapEdit.value = true
  try {
    await updateRoadSegment(selectedSegment.value.id, {
      name: mapEditForm.name.trim(),
      capacity: mapEditForm.capacity,
      mapPoints: mapEditPoints.value.map((p) => [p.x, p.y]),
      enabled: true
    })
    await loadSegments()
    ElMessage.success('地图区域的位置、形状和名称已保存')
  } catch (e) {
    ElMessage.error(e.message || '地图区域保存失败')
  } finally {
    savingMapEdit.value = false
  }
}

function onMapEditStart(event) {
  if (mapInteraction.value !== 'edit' || mapDrawing.value || event.button !== 0) return
  const meta = mapMeta()
  if (!meta) return
  const pos = localPos(event, mapCanvasRef.value)
  const norm = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  if (cameraCoverageEditing.value) {
    const points = cameraEditPoints.value
    const vertex = nearestVertex(points, pos, meta)
    if (vertex < 0 && !pointInPolygon(norm, points)) return
    cameraEditDrag = vertex >= 0
      ? { type: 'vertex', index: vertex }
      : { type: 'move', start: norm, origin: clonePoints(points) }
    event.currentTarget.setPointerCapture?.(event.pointerId)
    return
  }
  return
}

function onMapPointerMove(event) {
  if (mapDrawing.value) {
    onMapMove(event)
    return
  }
  if (mapInteraction.value !== 'edit') return
  const meta = mapMeta()
  if (!meta) return
  const pos = localPos(event, mapCanvasRef.value)
  const norm = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  if (cameraCoverageEditing.value && cameraEditDrag) {
    if (cameraEditDrag.type === 'vertex') {
      const next = clonePoints(cameraEditPoints.value)
      next[cameraEditDrag.index] = norm
      cameraEditPoints.value = next
    } else {
      cameraEditPoints.value = movePointsWithinFrame(cameraEditDrag.origin, norm.x - cameraEditDrag.start.x, norm.y - cameraEditDrag.start.y)
    }
    redrawMap()
    return
  }
  if (!mapEditDrag) return
  if (mapEditDrag.type === 'vertex') {
    const next = clonePoints(mapEditPoints.value)
    next[mapEditDrag.index] = norm
    mapEditPoints.value = next
  } else {
    mapEditPoints.value = movePointsWithinFrame(mapEditDrag.origin, norm.x - mapEditDrag.start.x, norm.y - mapEditDrag.start.y)
  }
  redrawMap()
}

function endMapEdit() {
  mapEditDrag = null
  cameraEditDrag = null
}

function selectRoi(row) {
  if (!row) return
  selectedRoiId.value = row.id
  roiEditPoints.value = clonePoints(parseZonePoints(row.points))
  roiEditForm.name = row.name || ''
  roiEditForm.segmentId = row.segmentId
  videoInteraction.value = 'edit'
  roiDrawing.value = false
  redrawVideo()
}

function cancelRoiEdit() {
  selectedRoiId.value = null
  roiEditPoints.value = []
  roiEditDrag = null
  redrawVideo()
}

async function saveRoiEdit() {
  if (!selectedRoi.value || roiEditPoints.value.length < 3) return
  if (!roiEditForm.name.trim()) return ElMessage.warning('请填写画面 ROI 名称')
  if (!roiEditForm.segmentId) return ElMessage.warning('请选择对应的地图区域')
  savingRoiEdit.value = true
  try {
    await updateCameraRoi(selectedRoi.value.id, {
      name: roiEditForm.name.trim(),
      segmentId: roiEditForm.segmentId,
      points: roiEditPoints.value.map((p) => [p.x, p.y]),
      enabled: true
    })
    await loadRois()
    ElMessage.success('画面 ROI 与地图区域的对应关系已保存')
  } catch (e) {
    ElMessage.error(e.message || '画面 ROI 保存失败')
  } finally {
    savingRoiEdit.value = false
  }
}

function onVideoEditStart(event) {
  if (videoInteraction.value !== 'edit' || roiDrawing.value || event.button !== 0) return
  const meta = videoMeta()
  if (!meta) return
  const pos = localPos(event, videoCanvasRef.value)
  const norm = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  let points = roiEditPoints.value
  let vertex = selectedRoi.value ? nearestVertex(points, pos, meta) : -1
  if (vertex < 0 && (!selectedRoi.value || !pointInPolygon(norm, points))) {
    const hit = [...rois.value].reverse().find((roi) => pointInPolygon(norm, parseZonePoints(roi.points)))
    if (!hit) {
      cancelRoiEdit()
      return
    }
    selectRoi(hit)
    points = roiEditPoints.value
    vertex = nearestVertex(points, pos, meta)
  }
  roiEditDrag = vertex >= 0
    ? { type: 'vertex', index: vertex }
    : { type: 'move', start: norm, origin: clonePoints(points) }
  event.currentTarget.setPointerCapture?.(event.pointerId)
}

function onVideoPointerMove(event) {
  if (roiDrawing.value) {
    onVideoMove(event)
    return
  }
  if (!roiEditDrag || videoInteraction.value !== 'edit') return
  const meta = videoMeta()
  if (!meta) return
  const pos = localPos(event, videoCanvasRef.value)
  const norm = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  if (roiEditDrag.type === 'vertex') {
    const next = clonePoints(roiEditPoints.value)
    next[roiEditDrag.index] = norm
    roiEditPoints.value = next
  } else {
    roiEditPoints.value = movePointsWithinFrame(roiEditDrag.origin, norm.x - roiEditDrag.start.x, norm.y - roiEditDrag.start.y)
  }
  redrawVideo()
}

function endVideoEdit() {
  roiEditDrag = null
}

function onMapClick(evt) {
  if (!mapDrawing.value) return
  const meta = mapMeta()
  if (!meta) return
  const pos = localPos(evt, mapCanvasRef.value)
  mapDraft.value = [...mapDraft.value, screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)]
  redrawMap()
}

async function onMapDblClick() {
  if (!mapDrawing.value || mapDraft.value.length < 3) {
    if (mapDrawing.value) ElMessage.warning('至少 3 个点')
    return
  }
  if (mapDrawMode.value === 'camera') {
    const camera = CAMERA_LAYOUT.find((item) => item.id === channelId.value)
    if (!camera) return ElMessage.warning('当前摄像头没有可保存的地图映射')
    const index = Math.max(0, ...(camera.regions || []).map((item) => Number(String(item.name || '').match(/\d+/)?.[0]) || 0)) + 1
    const regionName = `${channelName.value}·区域${index}`
    try {
      const mapPoints = mapDraft.value.map((p) => [p.x, p.y])
      const createdSeg = await createRoadSegment({
        name: regionName,
        capacity: 4,
        mapPoints,
        enabled: true
      })
      const createdRoi = await createCameraRoi({
        channelId: channelId.value,
        segmentId: createdSeg.id,
        name: regionName,
        points: ROI_PLACEHOLDER,
        enabled: false
      })
      const region = regionFromPair(createdRoi, createdSeg)
      region.mapPoints = mapPoints
      region.videoPoints = []
      camera.regions.push(region)
      activeRegionId.value = region.id
      mapDraft.value = []
      mapDrawing.value = false
      mapHover.value = null
      await loadSegments()
      await loadRois()
      redrawMap()
      ElMessage.success(`${regionName} 已写入 road_segments，请在右侧绘制画面 ROI`)
    } catch (e) {
      ElMessage.error(e.message || '保存失败')
    }
    return
  }
  try {
    const name = `路段${segments.value.length + 1}`
    const created = await createRoadSegment({
      name,
      capacity: 4,
      mapPoints: mapDraft.value.map((p) => [p.x, p.y]),
      enabled: true
    })
    ElMessage.success('路段已保存')
    mapDraft.value = []
    mapDrawing.value = false
    mapHover.value = null
    await loadSegments()
    await loadCongestion()
    selectSegment(segments.value.find((item) => Number(item.id) === Number(created.id)))
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

function onMapMove(evt) {
  if (!mapDrawing.value || !mapDraft.value.length) return
  const meta = mapMeta()
  if (!meta) return
  const pos = localPos(evt, mapCanvasRef.value)
  mapHover.value = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  redrawMap()
}

function onVideoClick(evt) {
  if (!roiDrawing.value) return
  const meta = videoMeta()
  if (!meta) return
  const pos = localPos(evt, videoCanvasRef.value)
  roiDraft.value = [...roiDraft.value, screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)]
  redrawVideo()
}

async function onVideoDblClick() {
  if (!roiDrawing.value || roiDraft.value.length < 3) {
    if (roiDrawing.value) ElMessage.warning('至少 3 个点')
    return
  }
  if (!activeRegion.value?.segmentId) return ElMessage.warning('请先选择对应的热力区域')
  const videoPoints = roiDraft.value.map((point) => [point.x, point.y])
  try {
    if (activeRegion.value.roiId) {
      await updateCameraRoi(activeRegion.value.roiId, {
        channelId: channelId.value,
        segmentId: activeRegion.value.segmentId,
        name: activeRegion.value.name,
        points: videoPoints,
        enabled: true
      })
    } else {
      const created = await createCameraRoi({
        channelId: channelId.value,
        segmentId: activeRegion.value.segmentId,
        name: activeRegion.value.name,
        points: videoPoints,
        enabled: true
      })
      activeRegion.value.roiId = created.id
      activeRegion.value.id = `pair-${created.id}`
    }
    activeRegion.value.videoPoints = videoPoints
    roiDraft.value = []
    roiDrawing.value = false
    roiHover.value = null
    await loadRois()
    await hydrateCurrentCameraRegions()
    redrawVideo()
    ElMessage.success(`${activeRegion.value.name} 已写入 camera_road_rois`)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

function onVideoMove(evt) {
  if (!roiDrawing.value || !roiDraft.value.length) return
  const meta = videoMeta()
  if (!meta) return
  const pos = localPos(evt, videoCanvasRef.value)
  roiHover.value = screenToNorm(pos.x, pos.y, meta.mapping, meta.srcW, meta.srcH)
  redrawVideo()
}

async function removeSegment(row) {
  try {
    await ElMessageBox.confirm(`删除路段「${row.name}」？`, '确认', { type: 'warning' })
    await deleteRoadSegment(row.id)
    if (Number(selectedSegmentId.value) === Number(row.id)) cancelMapEdit()
    await loadSegments()
    await loadCongestion()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

async function removeRoi(row) {
  try {
    await ElMessageBox.confirm(`删除 ROI「${row.name}」？`, '确认', { type: 'warning' })
    await deleteCameraRoi(row.id)
    if (Number(selectedRoiId.value) === Number(row.id)) cancelRoiEdit()
    await loadRois()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

function drawPoly(ctx, pts, mapping, srcW, srcH, { fill, stroke, dash, label, lineWidth = 2, glow = false } = {}) {
  if (!pts.length) return
  const screen = pts.map((p) => normToScreen(p.x, p.y, mapping, srcW, srcH))
  ctx.beginPath()
  screen.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)))
  if (pts.length >= 3) ctx.closePath()
  if (fill) {
    ctx.fillStyle = fill
    ctx.fill()
  }
  ctx.strokeStyle = stroke || '#3b82f6'
  ctx.lineWidth = lineWidth
  if (glow) { ctx.shadowColor = stroke || '#22d3ee'; ctx.shadowBlur = 16 }
  ctx.setLineDash(dash || [])
  ctx.stroke()
  ctx.shadowBlur = 0
  ctx.setLineDash([])
  if (label) {
    ctx.fillStyle = stroke || '#3b82f6'
    ctx.font = '12px sans-serif'
    ctx.fillText(label, screen[0].x + 4, screen[0].y + 14)
  }
}

function drawEditHandles(ctx, points, mapping, srcW, srcH) {
  for (const point of points) {
    const screen = normToScreen(point.x, point.y, mapping, srcW, srcH)
    ctx.beginPath()
    ctx.arc(screen.x, screen.y, 6, 0, Math.PI * 2)
    ctx.fillStyle = '#fff'
    ctx.fill()
    ctx.strokeStyle = '#8b5cf6'
    ctx.lineWidth = 3
    ctx.stroke()
  }
}

function redrawMap() {
  const canvas = mapCanvasRef.value
  const meta = mapMeta()
  if (!canvas || !meta) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, meta.cssW, meta.cssH)

  // 底图：road_segments 俯视多边形 + 实时车数着色
  for (const seg of segments.value) {
    const pts = parseZonePoints(parseJsonPoints(seg.mapPoints))
    if (pts.length < 3) continue
    drawPoly(ctx, pts, meta.mapping, meta.srcW, meta.srcH, {
      fill: cameraCountColor(displayCount(seg)),
      stroke: 'rgba(100,116,139,.35)',
      lineWidth: 1
    })
  }

  if (showAllCameraCoverage.value) {
    for (const camera of CAMERA_LAYOUT) {
      for (const region of camera.regions || []) {
        const active = camera.id === channelId.value && region.id === activeRegionId.value
        const points = active && cameraCoverageEditing.value ? cameraEditPoints.value : parseZonePoints(region.mapPoints)
        if (points.length < 3) continue
        const count = regionDisplayCount(region)
        drawPoly(ctx, points, meta.mapping, meta.srcW, meta.srcH, {
          fill: cameraCountColor(count),
          stroke: active ? '#22d3ee' : 'rgba(71,85,105,.58)',
          lineWidth: active ? 5 : 1.5,
          dash: active ? [14, 9] : [],
          glow: active,
          label: active ? `${cameraCoverageEditing.value ? '修改中' : region.name} · ${count}辆` : undefined
        })
        if (active && cameraCoverageEditing.value) drawEditHandles(ctx, points, meta.mapping, meta.srcW, meta.srcH)
      }
    }
  }

  if (mapDraft.value.length) {
    drawPoly(ctx, mapDraft.value, meta.mapping, meta.srcW, meta.srcH, {
      stroke: '#f97316',
      dash: [4, 3]
    })
    if (mapHover.value) {
      const pts = [...mapDraft.value, mapHover.value]
      drawPoly(ctx, pts, meta.mapping, meta.srcW, meta.srcH, {
        stroke: '#f97316',
        dash: [4, 3]
      })
    }
    for (const p of mapDraft.value) {
      const s = normToScreen(p.x, p.y, meta.mapping, meta.srcW, meta.srcH)
      ctx.beginPath()
      ctx.arc(s.x, s.y, 4, 0, Math.PI * 2)
      ctx.fillStyle = '#f97316'
      ctx.fill()
    }
  }
}

function redrawVideo() {
  const canvas = videoCanvasRef.value
  const meta = videoMeta()
  if (!canvas || !meta) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, meta.cssW, meta.cssH)

  for (const region of currentRegions.value) {
    const points = parseZonePoints(region.videoPoints)
    if (points.length < 3) continue
    const active = region.id === activeRegionId.value
    drawPoly(ctx, points, meta.mapping, meta.srcW, meta.srcH, {
      stroke: active ? '#22d3ee' : '#a3e635',
      lineWidth: active ? 5 : 3,
      dash: active ? [] : [10, 6],
      glow: true,
      label: `${region.name} ↔ 热力区 · ${regionDisplayCount(region)}辆`
    })
  }

  if (roiDraft.value.length) {
    const points = roiHover.value ? [...roiDraft.value, roiHover.value] : roiDraft.value
    drawPoly(ctx, points, meta.mapping, meta.srcW, meta.srcH, {
      stroke: '#22d3ee',
      lineWidth: 4,
      glow: true,
      dash: [8, 5]
    })
  }

  for (const det of overlaySync.detections.value) {
    const bbox = det.bbox
    if (!Array.isArray(bbox) || bbox.length < 4) continue
    const [x1, y1, x2, y2] = bbox
    const left = meta.mapping.offsetX + x1 * meta.mapping.scale
    const top = meta.mapping.offsetY + y1 * meta.mapping.scale
    const w = Math.max(1, (x2 - x1) * meta.mapping.scale)
    const h = Math.max(1, (y2 - y1) * meta.mapping.scale)
    const inRoi = !!det.inRoadRoi
    ctx.strokeStyle = inRoi ? '#ef4444' : '#22c55e'
    ctx.lineWidth = 2
    ctx.strokeRect(left, top, w, h)
  }
}

function updateCameraRegionCounts(detections) {
  const camera = currentCamera.value
  if (!camera) return
  const vehicleNames = new Set(['car', 'truck', 'bus', 'motorcycle', 'vehicle', '汽车', '轿车', '货车', '卡车', '公交车'])
  const vehicles = detections.filter((item) => vehicleNames.has(String(item.className || item.label || '').toLowerCase()))
  for (const region of camera.regions || []) {
    const polygon = parseZonePoints(region.videoPoints)
    if (polygon.length < 3) {
      region.count = 0
      continue
    }
    region.count = vehicles.filter((item) => {
      const box = item.bbox
      if (!Array.isArray(box) || box.length < 4) return false
      let x = (Number(box[0]) + Number(box[2])) / 2
      let y = (Number(box[1]) + Number(box[3])) / 2
      if (x > 1 || y > 1) {
        x /= Math.max(1, overlaySync.frameSize.width)
        y /= Math.max(1, overlaySync.frameSize.height)
      }
      return pointInPolygon({ x, y }, polygon)
    }).length
  }
}

async function syncDemoLayout() {
  const createdByName = {}
  const upgradeGeometry = localStorage.getItem('sandbox-map-layout-version') !== MAP_LAYOUT_VERSION
  if (upgradeGeometry) {
    const legacyRamp = segments.value.find((item) => item.name === '后方斜向合流道路')
    const alreadyMigrated = segments.value.some((item) => item.name === '后方高架引道')
    const replacement = DEMO_SEGMENTS.find((item) => item.name === '后方高架引道')
    if (legacyRamp && !alreadyMigrated && replacement) {
      await updateRoadSegment(legacyRamp.id, { ...replacement, enabled: true })
      await loadSegments()
    }
  }
  for (const segment of DEMO_SEGMENTS) {
    const existing = segments.value.find((item) => item.name === segment.name)
    let created = existing
    if (existing && upgradeGeometry) {
      created = await updateRoadSegment(existing.id, { ...segment, enabled: true })
    } else if (!existing) {
      created = await createRoadSegment({ ...segment, enabled: true })
    }
    createdByName[created.name] = created.id
  }
  if (upgradeGeometry) localStorage.setItem('sandbox-map-layout-version', MAP_LAYOUT_VERSION)
  await loadSegments()
  for (const camera of CAMERA_LAYOUT) {
    const cameraRois = await fetchCameraRois(camera.id)
    const demoRoi = cameraRois.find((item) => item.name?.includes('演示ROI'))
    const segmentId = createdByName[camera.segment]
    if (demoRoi && segmentId && Number(demoRoi.segmentId) !== Number(segmentId)) {
      await updateCameraRoi(demoRoi.id, { segmentId })
    }
  }
  return createdByName
}

async function seedDemoBindings(createdByName) {
  const defaultRoi = [[0.06, 0.18], [0.94, 0.18], [0.98, 0.96], [0.02, 0.96]]
  for (const camera of CAMERA_LAYOUT) {
    const segmentId = createdByName[camera.segment]
    if (!segmentId) continue
    await createCameraRoi({
      channelId: camera.id,
      segmentId,
      name: `${camera.name}·演示ROI`,
      points: defaultRoi,
      enabled: true
    })
  }
  ElMessage.success('已根据12路摄像头建立临时沙盘路段与覆盖区域')
}

function syncDelayedOverlayStats() {
  const detections = overlaySync.detections.value
  updateCameraRegionCounts(detections)
  const vehicleNames = new Set(['car', 'truck', 'bus', 'motorcycle', 'vehicle', '汽车', '轿车', '货车', '卡车', '公交车'])
  const reported = Number(overlaySync.extras.vehicleCount)
  const count = Number.isFinite(reported)
    ? reported
    : detections.filter((item) => vehicleNames.has(String(item.className || item.label || '').toLowerCase())).length
  cameraCounts.value = { ...cameraCounts.value, [channelId.value]: Math.max(0, count) }
}

onMounted(async () => {
  try {
    channelOptions.value = await fetchChannels()
  } catch {
    channelOptions.value = [
      { id: 'live12', name: '道路1' },
      { id: 'live7', name: '道路2' },
      { id: 'live10', name: '道路3' }
    ]
  }
  await loadSegments()
  if (!segments.value.length) {
    try {
      await syncDemoLayout()
      ElMessage.info('已自动创建沙盘演示路段（road_segments）')
    } catch {
      /* backend may be offline */
    }
  }
  await hydrateAllRegionsFromDb()
  await loadDbContext()
  await loadCongestion()
  await switchScene('live12')
  await loadRois()
  try {
    await switchModel('congestion')
  } catch {
    /* optional */
  }
  connectLiveSocket()
  overlaySync.start()
  offLive = onLiveMessage((msg) => {
    if (msg.type === 'road_congestion') {
      applyCongestion(msg.data)
      return
    }
    if (msg.type === 'detection_result') {
      overlaySync.enqueue(msg)
    }
  })
  redrawTimer = setInterval(() => {
    syncDelayedOverlayStats()
    redrawMap()
    redrawVideo()
  }, OVERLAY_TICK_MS)
  onResize = () => {
    nextTick(() => {
      redrawMap()
      redrawVideo()
    })
  }
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  if (redrawTimer) clearInterval(redrawTimer)
  if (onResize) window.removeEventListener('resize', onResize)
  overlaySync.stop()
  offLive?.()
})
</script>

<style scoped>
.heatmap-page { width: 100%; max-width: 2200px; min-width: 1450px; margin: 0 auto; }
.card-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.header-actions, .toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.toolbar { margin-bottom: 10px; }
.legend { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 8px; font-size: 12px; color: #606266; }
.lg { display: inline-flex; align-items: center; gap: 4px; }
.swatch { display: inline-block; width: 14px; height: 10px; border-radius: 2px; }
.swatch.c1 { background: #22c55e; }
.swatch.c2 { background: #facc15; }
.swatch.c3 { background: #f97316; }
.swatch.c4 { background: #ef4444; }
.meta { margin-left: auto; }
.map-wrap, .video-wrap {
  position: relative; background: #111; border-radius: 6px; overflow: hidden;
  aspect-ratio: 16/9;
}
.map-wrap { min-height: 760px; cursor: grab; touch-action: none; background: #fff; border: 1px solid #dcdfe6; }
.map-wrap.panning { cursor: grabbing; }
.map-stage { position: absolute; inset: 0; transform-origin: center center; will-change: transform; }
.map-img, .video-player { width: 100%; height: 100%; object-fit: contain; display: block; }
.map-img { object-fit: fill; user-select: none; pointer-events: none; }
.map-overlay, .video-overlay {
  position: absolute; inset: 0; width: 100%; height: 100%;
  pointer-events: none; z-index: 2;
}
.map-overlay.drawing, .video-overlay.drawing { pointer-events: auto; cursor: crosshair; }
.map-overlay.editing, .video-overlay.editing { pointer-events: auto; cursor: move; touch-action: none; }
.map-help { position: absolute; right: 12px; bottom: 10px; z-index: 5; padding: 6px 10px; border-radius: 6px; background: rgba(15,23,42,.76); color: #e2e8f0; font-size: 12px; pointer-events: none; }
.video-error {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: #fff; background: rgba(0,0,0,.55); z-index: 3;
}
.draw-hint {
  position: absolute; left: 10px; bottom: 10px; z-index: 4;
  background: rgba(0,0,0,.65); color: #fff; font-size: 12px;
  padding: 4px 8px; border-radius: 4px;
}
.mt { margin-top: 12px; }
.mode-row { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:10px; color:#606266; font-size:13px; }
.edit-panel { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-top:12px; padding:10px 12px; border:1px solid #c4b5fd; border-radius:8px; background:#f5f3ff; }
.edit-panel .el-input { width:190px; }.edit-panel .el-select { width:190px; }.edit-tip { color:#7c3aed; font-size:12px; }
.roi-edit-panel { align-items:stretch; flex-direction:column; }.roi-edit-panel .el-input,.roi-edit-panel .el-select { width:100%; }
.camera-list { padding-right: 4px; }
.camera-bind-item { display: flex; align-items: center; gap: 9px; margin-bottom: 7px; padding: 9px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; cursor: pointer; transition: .18s; }
.camera-bind-item:hover, .camera-bind-item.active { border-color: #409eff; background: #eff6ff; }
.camera-code { flex: none; padding: 3px 6px; border-radius: 5px; color: #2563eb; background: #dbeafe; font-size: 11px; font-weight: 700; }
.camera-bind-main { min-width: 0; flex: 1; }.camera-bind-main b, .camera-bind-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.camera-bind-main small { margin-top: 2px; color: #64748b; font-size: 11px; }
.camera-bind-item i { flex: none; width: 13px; height: 13px; border-radius: 50%; border: 2px solid rgba(255,255,255,.9); box-shadow: 0 0 0 1px #cbd5e1; }
.region-list { display:flex; flex-direction:column; gap:6px; margin-top:10px; }
.region-item { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:8px 10px; border:1px solid #dbeafe; border-radius:7px; cursor:pointer; background:#fff; }
.region-item.active { border-color:#22d3ee; box-shadow:0 0 0 2px rgba(34,211,238,.18); }
.region-item span { min-width:0; }.region-item b,.region-item small { display:block; }.region-item small { color:#64748b; margin-top:2px; }
</style>
