import { SERVER_URL } from '../config.js'

export function downloadExport(type) {
  const map = {
    plates: '/api/export/plate-records',
    alerts: '/api/export/alerts',
    congestion: '/api/export/congestion'
  }
  const path = map[type]
  if (!path) return
  window.open(`${SERVER_URL}${path}`, '_blank')
}
