import request from './request.js'

export function fetchPlateRecords(limit = 50) {
  return request.get('/api/plate-records', { params: { limit } })
}

export function addPlateRecord(plateNumber) {
  return request.post('/api/plate-records', { plateNumber })
}
