import request from './request.js'

export function fetchCongestionLogs(limit = 50) {
  return request.get('/api/congestion/logs', { params: { limit } })
}

export function fetchLatestCongestion() {
  return request.get('/api/congestion/latest')
}

export function fetchLatestCongestionSnapshot() {
  return request.get('/api/congestion/latest-snapshot')
}
