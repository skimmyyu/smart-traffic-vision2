import request from './request.js'

export function fetchChannels() {
  return request.get('/api/stream/channels')
}

export function fetchCurrentChannel() {
  return request.get('/api/stream/current')
}

export function switchChannel(channelId) {
  return request.post(`/api/stream/switch/${channelId}`)
}
