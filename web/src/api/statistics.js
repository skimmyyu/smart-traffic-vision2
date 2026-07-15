import request from './request.js'

export function fetchStatisticsOverview() {
  return request.get('/api/statistics/overview')
}
