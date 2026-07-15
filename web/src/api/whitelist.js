import request from './request.js'

export function fetchWhitelist() {
  return request.get('/api/whitelist')
}

export function addWhitelist(plateNumber) {
  return request.post('/api/whitelist', { plateNumber })
}

export function removeWhitelist(plateNumber) {
  return request.delete(`/api/whitelist/${encodeURIComponent(plateNumber)}`)
}
