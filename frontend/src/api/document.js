import request from './index'

export function getDocuments() {
  return request.get('/document/list')
}

export function uploadDocument(file) {
  return request.post('/document/upload', file, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function deleteDocument(id) {
  return request.delete(`/document/${id}`)
}

export function embedDocument(id) {
  return request.post(`/document/embed/${id}`)
}
