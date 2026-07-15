import request from './request.js'

export function fetchDevices() {
  return request.get('/api/devices')
}

export function registerDevice(data) {
  return request.post('/api/devices', data)
}

export function sendHeartbeat(id) {
  return request.post(`/api/devices/${id}/heartbeat`)
}
