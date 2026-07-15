import { ref } from 'vue'
import { uploadCameraFrame } from '../api/camera.js'

export function useBrowserCamera(videoRef, sourceId) {
  const error = ref('')
  const uploading = ref(false)
  let stream = null
  let timer = null
  let inFlight = false
  const canvas = document.createElement('canvas')

  async function start(constraints = {}) {
    stop()
    error.value = ''
    if (!navigator.mediaDevices?.getUserMedia) {
      error.value = '浏览器不支持摄像头；手机浏览器需通过 HTTPS 打开本页面'
      throw new Error(error.value)
    }
    stream = await navigator.mediaDevices.getUserMedia({
      video: {
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: 15, max: 24 },
        ...constraints
      },
      audio: false
    })
    const video = videoRef.value
    if (video) {
      video.srcObject = stream
      await video.play().catch(() => {})
    }
    timer = window.setInterval(sendFrame, 250)
    return stream
  }

  function sendFrame() {
    const video = videoRef.value
    if (!video || inFlight || video.readyState < 2 || !video.videoWidth) return
    const width = Math.min(960, video.videoWidth)
    const height = Math.max(1, Math.round(width * video.videoHeight / video.videoWidth))
    canvas.width = width
    canvas.height = height
    canvas.getContext('2d', { alpha: false }).drawImage(video, 0, 0, width, height)
    inFlight = true
    uploading.value = true
    canvas.toBlob(async (blob) => {
      try {
        if (blob) await uploadCameraFrame(sourceId, blob)
      } catch (e) {
        error.value = e.message || '画面上传失败'
      } finally {
        inFlight = false
        uploading.value = false
      }
    }, 'image/jpeg', 0.72)
  }

  function stop() {
    if (timer) window.clearInterval(timer)
    timer = null
    stream?.getTracks().forEach((track) => track.stop())
    stream = null
    if (videoRef.value?.srcObject) videoRef.value.srcObject = null
    inFlight = false
    uploading.value = false
  }

  return { error, uploading, start, stop }
}
