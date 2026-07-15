import { ref, onUnmounted } from 'vue'
import Hls from 'hls.js'
import { clientMediaUrl } from '../config.js'

export function useHlsPlayer(options = {}) {
  const lowLatency = !!options.lowLatency
  const videoRef = ref(null)
  const videoError = ref('')
  let hls = null
  let webrtcPc = null

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

  function playHls(url) {
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
    if (!video || !url) return
    video.srcObject = null
    url = clientMediaUrl(url)

    if (Hls.isSupported()) {
      hls = new Hls({
        enableWorker: true,
        lowLatencyMode: lowLatency,
        liveSyncDuration: lowLatency ? 0.6 : 1,
        liveMaxLatencyDuration: lowLatency ? 2.5 : 3,
        maxLiveSyncPlaybackRate: lowLatency ? 1.2 : 1.1
      })
      hls.loadSource(url)
      hls.attachMedia(video)
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(() => {})
      })
      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
          videoError.value = 'HLS 播放失败，请确认 MediaMTX 已运行'
        }
      })
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url
      video.play().catch(() => {})
    } else {
      videoError.value = '当前浏览器不支持 HLS 播放'
    }
  }

  async function playWebRtc(whepUrl) {
    destroyPlayer()
    videoError.value = ''
    const video = videoRef.value
    if (!video || !whepUrl) return false

    const url = clientMediaUrl(whepUrl)
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
      return true
    } catch (e) {
      videoError.value = e?.message || 'WebRTC 播放失败'
      return false
    }
  }

  /** Prefer WebRTC (WHEP), fall back to HLS. */
  async function playRtsp({ webrtcUrl, hlsUrl }) {
    if (webrtcUrl) {
      const ok = await playWebRtc(webrtcUrl)
      if (ok) return
    }
    if (hlsUrl) playHls(hlsUrl)
  }

  onUnmounted(destroyPlayer)

  return { videoRef, videoError, playHls, playWebRtc, playRtsp, destroyPlayer }
}
