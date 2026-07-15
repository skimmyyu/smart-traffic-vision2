import request from './request.js'

export function fetchAlerts(limit = 50) {
  return request.get('/api/alerts', { params: { limit } })
}

export function addAlert(payload) {
  return request.post('/api/alerts', payload)
}
