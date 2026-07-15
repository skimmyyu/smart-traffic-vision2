import { reactive, ref } from 'vue'

/** Overlay follows inference immediately; HLS is only ~0.5s behind RTSP. */
export const OVERLAY_DELAY_MS = 0
export const OVERLAY_TICK_MS = 50

function parsePayload(data, frameSize) {
  const d = data || {}
  return {
    detections: Array.isArray(d.detections) ? d.detections : [],
    imageWidth: Number(d.imageWidth) || frameSize.width,
    imageHeight: Number(d.imageHeight) || frameSize.height,
    bgLearning:
      d.bgLearning === true
      || (typeof d.summary === 'string' && d.summary.includes('差分学习中')),
    summary: d.summary ?? '',
    source: d.source ?? '',
    plates: Array.isArray(d.plates) ? d.plates : [],
    plateOverlays: Array.isArray(d.plateOverlays) ? d.plateOverlays : [],
    vehicleCount: d.vehicleCount ?? null,
    raw: d
  }
}

/**
 * Queue detection_result messages; expose overlay state after OVERLAY_DELAY_MS.
 * Inference stays real-time — only canvas overlay is delayed.
 */
export function useDelayedDetections(options = {}) {
  const delayMs = Math.max(0, Number(options.delayMs ?? OVERLAY_DELAY_MS))
  const hlsLatencyMs = Math.max(0, Number(options.hlsLatencyMs ?? delayMs))
  const tickMs = Math.max(10, Number(options.tickMs ?? OVERLAY_TICK_MS))
  const maxQueue = Math.max(1, Number(options.maxQueue ?? 120))

  const detections = ref([])
  const frameSize = reactive({
    width: options.defaultWidth ?? 1920,
    height: options.defaultHeight ?? 1080
  })
  const extras = reactive({
    bgLearning: false,
    summary: '',
    source: '',
    plates: [],
    plateOverlays: [],
    vehicleCount: null,
    raw: null
  })

  const queue = []
  let tickTimer = null

  function applyPayload(payload) {
    detections.value = payload.detections
    frameSize.width = payload.imageWidth
    frameSize.height = payload.imageHeight
    extras.bgLearning = payload.bgLearning
    extras.summary = payload.summary
    extras.source = payload.source
    extras.plates = payload.plates
    extras.plateOverlays = payload.plateOverlays
    extras.vehicleCount = payload.vehicleCount
    extras.raw = payload.raw
  }

  function enqueue(msg) {
    if (msg?.type !== 'detection_result') return
    const payload = parsePayload(msg.data, frameSize)
    if (delayMs === 0 && hlsLatencyMs === 0) {
      applyPayload(payload)
      return
    }
    const capturedAt = Number(msg.data?.capturedAt)
    const releaseAt = Number.isFinite(capturedAt) && capturedAt > 0
      ? Math.min(Date.now(), capturedAt + hlsLatencyMs)
      : Date.now() + delayMs
    queue.push({
      releaseAt,
      payload
    })
    while (queue.length > maxQueue) queue.shift()
  }

  function tick() {
    if (!queue.length) return
    const now = Date.now()
    let newestReady = null
    const waiting = []
    for (const item of queue) {
      if (now >= item.releaseAt) {
        newestReady = item.payload
      } else {
        waiting.push(item)
      }
    }
    queue.length = 0
    queue.push(...waiting)
    if (newestReady) applyPayload(newestReady)
  }

  /** Clear queue and overlay — call when switching cameras. */
  function clear() {
    queue.length = 0
    detections.value = []
    extras.bgLearning = false
    extras.summary = ''
    extras.source = ''
    extras.plates = []
    extras.plateOverlays = []
    extras.vehicleCount = null
    extras.raw = null
  }

  function start() {
    stop()
    tickTimer = window.setInterval(tick, tickMs)
  }

  function stop() {
    if (tickTimer != null) {
      window.clearInterval(tickTimer)
      tickTimer = null
    }
  }

  return {
    detections,
    frameSize,
    extras,
    enqueue,
    clear,
    start,
    stop,
    delayMs,
    hlsLatencyMs
  }
}
