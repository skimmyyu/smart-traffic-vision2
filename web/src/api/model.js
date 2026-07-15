import request from './request.js'

export function fetchModels() {
  return request.get('/api/models')
}

export function switchModel(modelId) {
  return request.post(`/api/models/switch/${modelId}`)
}

export function fetchModelParameters(modelId) {
  return request.get(`/api/models/${modelId}/parameters`)
}

export function updateModelParameters(modelId, parameters) {
  return request.put(`/api/models/${modelId}/parameters`, parameters)
}
