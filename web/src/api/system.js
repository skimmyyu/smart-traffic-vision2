import request from './request.js'

export function fetchSystemStatus() {
  return request.get('/api/system/status')
}

export function fetchStreamStatus() {
  return request.get('/api/system/stream')
}
