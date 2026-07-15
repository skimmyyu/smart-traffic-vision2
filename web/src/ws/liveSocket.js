import { WS_URL } from '../config.js'

let socket = null
let reconnectTimer = null
const listeners = new Set()

function notify(message) {
  listeners.forEach((fn) => {
    try {
      fn(message)
    } catch (e) {
      console.error(e)
    }
  })
}

export function connectLiveSocket() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }

  socket = new WebSocket(WS_URL)

  socket.onmessage = (event) => {
    try {
      notify(JSON.parse(event.data))
    } catch (e) {
      console.warn('WS 消息解析失败', event.data)
    }
  }

  socket.onclose = () => {
    reconnectTimer = window.setTimeout(connectLiveSocket, 3000)
  }

  socket.onerror = () => {
    socket?.close()
  }
}

export function disconnectLiveSocket() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (socket) {
    socket.close()
    socket = null
  }
}

export function onLiveMessage(handler, options = {}) {
  const alignmentMs = Math.max(0, Number(options.captureAlignmentDelayMs) || 0)
  const timers = new Set()
  const wrapped = (message) => {
    const capturedAt = Number(message?.data?.capturedAt)
    const waitMs = Number.isFinite(capturedAt)
      ? Math.max(0, capturedAt + alignmentMs - Date.now())
      : 0
    if (alignmentMs > 0 && waitMs > 0 && message?.type === 'detection_result') {
      const timer = window.setTimeout(() => {
        timers.delete(timer)
        handler(message)
      }, waitMs)
      timers.add(timer)
      return
    }
    handler(message)
  }
  const clear = () => {
    timers.forEach((timer) => window.clearTimeout(timer))
    timers.clear()
  }
  listeners.add(wrapped)
  const unsubscribe = () => {
    clear()
    listeners.delete(wrapped)
  }
  unsubscribe.clear = clear
  return unsubscribe
}
