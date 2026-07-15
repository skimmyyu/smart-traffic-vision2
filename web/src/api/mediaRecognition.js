import axios from 'axios'
import { SERVER_URL } from '../config.js'

export async function recognizeMedia(file, onProgress) {
  const data = new FormData()
  data.append('file', file)
  const response = await axios.post(`${SERVER_URL}/api/media-recognition/recognize`, data, {
    timeout: 10 * 60 * 1000,
    onUploadProgress: (event) => {
      if (event.total) onProgress?.(Math.round(event.loaded * 100 / event.total))
    }
  })
  if (response.data?.code !== 200) throw new Error(response.data?.message || '识别失败')
  return response.data.data
}

export function absoluteResultUrl(path) {
  return path?.startsWith('http') ? path : `${SERVER_URL}${path || ''}`
}

export async function fetchRecognitionHistory() {
  const response = await axios.get(`${SERVER_URL}/api/media-recognition/history`, { timeout: 20000 })
  if (response.data?.code !== 200) throw new Error(response.data?.message || '加载识别历史失败')
  return response.data.data || []
}

export async function submitRecognitionJob(file, onProgress) {
  const data = new FormData()
  data.append('file', file)
  const response = await axios.post(`${SERVER_URL}/api/media-recognition/jobs`, data, {
    timeout: 10 * 60 * 1000,
    onUploadProgress: (event) => {
      if (event.total) onProgress?.(Math.round(event.loaded * 100 / event.total))
    }
  })
  if (response.data?.code !== 200) throw new Error(response.data?.message || '提交识别任务失败')
  return response.data.data
}

export async function fetchRecognitionJobs() {
  const response = await axios.get(`${SERVER_URL}/api/media-recognition/jobs`, { timeout: 20000 })
  if (response.data?.code !== 200) throw new Error(response.data?.message || '加载识别任务失败')
  return response.data.data || []
}
