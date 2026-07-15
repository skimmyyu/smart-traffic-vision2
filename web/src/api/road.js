import request from './request.js'

export function fetchRoadSegments() {
  return request.get('/api/road-segments')
}

export function createRoadSegment(payload) {
  return request.post('/api/road-segments', payload)
}

export function updateRoadSegment(id, payload) {
  return request.put(`/api/road-segments/${id}`, payload)
}

export function deleteRoadSegment(id) {
  return request.delete(`/api/road-segments/${id}`)
}

export function fetchCameraRois(channelId) {
  return request.get('/api/camera-rois', { params: { channelId } })
}

export function createCameraRoi(payload) {
  return request.post('/api/camera-rois', payload)
}

export function updateCameraRoi(id, payload) {
  return request.put(`/api/camera-rois/${id}`, payload)
}

export function deleteCameraRoi(id) {
  return request.delete(`/api/camera-rois/${id}`)
}

export function fetchRoadCongestion() {
  return request.get('/api/road-congestion/current')
}

export function fetchLatestRoadSnapshot() {
  return request.get('/api/road-congestion/latest-snapshot')
}

export function persistRoadCongestion() {
  return request.post('/api/road-congestion/persist')
}

export function fetchRoadCongestionDbContext() {
  return request.get('/api/road-congestion/db-context')
}
