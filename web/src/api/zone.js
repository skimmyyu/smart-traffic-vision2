import request from './request.js'

export function fetchZones(channelId) {
  return request.get('/api/zones', { params: { channelId } })
}

export function createZone(payload) {
  return request.post('/api/zones', payload)
}

export function updateZone(id, payload) {
  return request.put(`/api/zones/${id}`, payload)
}

export function deleteZone(id) {
  return request.delete(`/api/zones/${id}`)
}
