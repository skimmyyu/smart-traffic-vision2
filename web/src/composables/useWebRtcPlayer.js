import { ref } from 'vue'
import { clientMediaUrl } from '../config.js'

/**
 * MediaMTX WHEP WebRTC playback — sub-second latency vs HLS.
 */
export function useWebRtcPlayer() {
  const videoRef = ref(null)
  const videoError = ref('')
  let pc = null

  function destroyPlayer() {
    if (pc) {
      pc.close()
      pc = null
    }
    const video = videoRef.value
    if (video) {
      video.srcObject = null
      video.removeAttribute('src')
      video.load()
    }
  }

  async function playWebRtc(whepUrl, { onReady } = {}) {
    destroyPlayer()
    videoError.value = ''
    const video = videoRef.value
    if (!video || !whepUrl) {
      onReady?.(false)
      return false
    }

    const url = clientMediaUrl(whepUrl)
    let settled = false
    const done = (ok) => {
      if (settled) return
      settled = true
      onReady?.(ok)
    }

    try {
      pc = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
      })
      pc.addTransceiver('video', { direction: 'recvonly' })
      pc.addTransceiver('audio', { direction: 'recvonly' })
      pc.ontrack = (ev) => {
        if (ev.streams?.[0]) {
          video.srcObject = ev.streams[0]
          video.play().catch(() => {})
          done(true)
        }
      }
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: offer.sdp
      })
      if (!resp.ok) {
        throw new Error(`WHEP HTTP ${resp.status}`)
      }
      const answer = await resp.text()
      await pc.setRemoteDescription({ type: 'answer', sdp: answer })
      window.setTimeout(() => {
        if (!settled) {
          videoError.value = 'WebRTC 加载超时，将尝试 HLS'
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

  return { videoRef, videoError, playWebRtc, destroyPlayer }
}
