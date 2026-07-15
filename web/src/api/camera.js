import request from './request.js'
import { SERVER_URL } from '../config.js'

export function fetchCameraSources() {
  return request.get('/api/camera/sources')
}

export function selectCameraSource(sourceId) {
  return request.post(`/api/camera/source/${sourceId}`)
}

export function fetchAnomalyStatus() {
  return request.get('/api/scene-anomaly/status')
}

export async function captureAnomalyBaseline(blob = null) {
  if (blob && blob.size > 0) {
    const response = await fetch(`${SERVER_URL}/api/scene-anomaly/baseline`, {
      method: 'POST',
      headers: { 'Content-Type': 'image/jpeg' },
      body: blob
    })
    const data = await response.json()
    if (data?.code === 200) return data.data
    throw new Error(data?.message || '设置基准失败')
  }
  return request.post('/api/scene-anomaly/baseline')
}

export function cameraFrameUrl(sourceId) {
  return `${SERVER_URL}/api/camera/frame/${sourceId}?t=${Date.now()}`
}

export function anomalyBaselineImageUrl() {
  return `${SERVER_URL}/api/scene-anomaly/baseline/image?t=${Date.now()}`
}

export async function uploadCameraFrame(sourceId, blob) {
  const response = await fetch(`${SERVER_URL}/api/camera/frame/${sourceId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'image/jpeg' },
    body: blob
  })
  if (!response.ok) throw new Error(`画面上传失败 (${response.status})`)
}
