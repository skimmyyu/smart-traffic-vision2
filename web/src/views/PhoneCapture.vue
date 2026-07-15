<template>
  <div class="phone-page">
    <el-card class="capture-card" shadow="never">
      <template #header>
        <div class="header-row">
          <strong>手机摄像头采集</strong>
          <el-tag :type="mode === 'ip' ? 'success' : (running ? 'success' : 'info')">
            {{ mode === 'ip' ? 'IP 摄像头模式' : (running ? '浏览器传输中' : '未启动') }}
          </el-tag>
        </div>
      </template>

      <el-radio-group v-model="mode" class="mode-switch" @change="onModeChange">
        <el-radio-button value="ip">IP 摄像头 + Tailscale（推荐跨网）</el-radio-button>
        <el-radio-button value="browser">浏览器直传（同局域网）</el-radio-button>
      </el-radio-group>

      <template v-if="mode === 'ip'">
        <el-alert type="success" :closable="false" show-icon title="IP 摄像头 + Tailscale 模式" class="notice">
          <template #default>
            <ol class="steps">
              <li>手机安装 <b>Tailscale</b>，与电脑登录同一账号</li>
              <li>安装 <b>IP 摄像头</b> App，开启 RTSP（默认端口 8554）</li>
              <li>在 App 顶部点击 <b>「切换」</b> 可切换前后摄像头</li>
              <li>电脑监控中心选择「手机 IP 摄像头 (Tailscale)」即可观看</li>
            </ol>
            <p v-if="ipCamera.rtspUrl" class="rtsp-line">RTSP：<code>{{ ipCamera.rtspUrl }}</code></p>
          </template>
        </el-alert>
        <div class="actions">
          <el-button type="primary" size="large" @click="selectIpCamera">在监控中心启用 IP 摄像头</el-button>
          <el-button size="large" @click="showFacingHint">如何切换前后摄像头？</el-button>
        </div>
      </template>

      <template v-else>
        <div class="phone-video-wrap">
          <video ref="videoRef" autoplay muted playsinline class="phone-video" />
          <div v-if="!running" class="start-cover">点击下方按钮并允许访问摄像头</div>
        </div>
        <el-alert
          v-if="camera.error.value"
          type="error"
          :closable="false"
          :title="camera.error.value"
          show-icon
          class="notice"
        />
        <el-alert
          type="info"
          :closable="false"
          title="手机与电脑需连接同一局域网"
          description="移动浏览器通常要求 HTTPS 才能授权摄像头；跨网请改用 IP 摄像头 + Tailscale 模式。"
          show-icon
          class="notice"
        />
        <el-alert
          :type="secureContext ? 'success' : 'warning'"
          :closable="false"
          :title="secureContext ? '当前网页具备摄像头授权条件' : '当前是非安全网页，手机浏览器可能直接禁用摄像头'"
          :description="secureContext ? currentAddress : `当前地址：${currentAddress}`"
          show-icon
          class="notice"
        />
        <div class="actions">
          <el-button v-if="!running" type="primary" size="large" @click="startCapture">开始采集</el-button>
          <el-button v-else type="danger" size="large" @click="stopCapture">停止采集</el-button>
          <el-button :disabled="running" size="large" @click="toggleFacing">切换前后摄像头</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchCameraSources, selectCameraSource } from '../api/camera.js'
import { useBrowserCamera } from '../composables/useBrowserCamera.js'

const router = useRouter()
const videoRef = ref(null)
const running = ref(false)
const facing = ref('environment')
const mode = ref('ip')
const ipCamera = ref({ rtspUrl: '', tailscaleHost: '' })
const camera = useBrowserCamera(videoRef, 'phone')
const secureContext = computed(() => window.isSecureContext)
const currentAddress = window.location.href

async function loadIpInfo() {
  try {
    const data = await fetchCameraSources()
    if (data.ipCamera) ipCamera.value = data.ipCamera
  } catch {
    /* ignore */
  }
}

async function selectIpCamera() {
  try {
    await selectCameraSource('ip-camera')
    ElMessage.success('已切换为手机 IP 摄像头，正在跳转监控中心…')
    router.push('/monitor')
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

function showFacingHint() {
  ElMessageBox.alert(
    '前后摄像头需要在手机「IP 摄像头」App 内点击顶部「切换」按钮，本系统会自动接收更新后的 RTSP 画面。',
    '切换前后摄像头',
    { confirmButtonText: '知道了' }
  )
}

function onModeChange() {
  if (mode.value === 'browser') return
  stopCapture()
}

async function startCapture() {
  try {
    await camera.start({ facingMode: { ideal: facing.value } })
    await selectCameraSource('phone')
    running.value = true
    ElMessage.success('手机画面已发送到监控中心')
  } catch (e) {
    running.value = false
    ElMessage.error(e.message || '无法启动手机摄像头')
  }
}

function stopCapture() {
  camera.stop()
  running.value = false
}

function toggleFacing() {
  facing.value = facing.value === 'environment' ? 'user' : 'environment'
  ElMessage.info(facing.value === 'environment' ? '将使用后置摄像头' : '将使用前置摄像头')
}

onMounted(loadIpInfo)
onUnmounted(stopCapture)
</script>

<style scoped>
.phone-page { max-width: 760px; margin: 0 auto; }
.capture-card { border-radius: 12px; }
.header-row { display: flex; justify-content: space-between; align-items: center; }
.mode-switch { margin-bottom: 16px; width: 100%; display: flex; }
.mode-switch :deep(.el-radio-button) { flex: 1; }
.mode-switch :deep(.el-radio-button__inner) { width: 100%; }
.phone-video-wrap { position: relative; width: 100%; aspect-ratio: 16 / 9; background: #020617; border-radius: 10px; overflow: hidden; }
.phone-video { width: 100%; height: 100%; object-fit: contain; }
.start-cover { position: absolute; inset: 0; display: grid; place-items: center; color: #e2e8f0; }
.notice { margin-top: 14px; }
.steps { margin: 0; padding-left: 18px; line-height: 1.8; }
.rtsp-line { margin: 10px 0 0; word-break: break-all; }
.actions { margin-top: 18px; display: flex; justify-content: center; gap: 12px; flex-wrap: wrap; }
</style>
