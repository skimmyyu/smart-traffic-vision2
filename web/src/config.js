// 使用打开页面时的 host，手机/其它电脑通过局域网 IP 访问时，API 也指向同一地址
const host = window.location.hostname || '127.0.0.1'
const secure = window.location.protocol === 'https:'
const port = window.location.port
const devViaProxy = import.meta.env.DEV
const isLocalHost = host === '127.0.0.1' || host === 'localhost'

// 开发态：API/WS 经 Vite 代理；HLS 本机直连 8888，远程才走 /hls-proxy
export const SERVER_URL = devViaProxy
  ? window.location.origin
  : `${secure ? 'https' : 'http'}://${host}:8080`

export const HLS_BASE_URL = devViaProxy && !isLocalHost
  ? `${window.location.origin}/hls-proxy`
  : `http://${host}:8888`

export const WEBRTC_BASE_URL = devViaProxy && !isLocalHost
  ? `${window.location.origin}/webrtc-proxy`
  : `http://${host}:8889`

export const WS_URL = devViaProxy
  ? `${secure ? 'wss' : 'ws'}://${host}${port ? `:${port}` : ''}/ws/live`
  : `${secure ? 'wss' : 'ws'}://${host}:8080/ws/live`

export function clientMediaUrl(url) {
  if (!url) return url
  let normalized = url.replace(/(127\.0\.0\.1|localhost)/gi, host)
  if (devViaProxy && !isLocalHost) {
    normalized = normalized.replace(/https?:\/\/[^/]+:8888/gi, HLS_BASE_URL)
    normalized = normalized.replace(/https?:\/\/[^/]+:8889/gi, WEBRTC_BASE_URL)
  }
  return normalized
}
