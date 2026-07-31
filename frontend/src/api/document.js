import request from './index'

export function getDocuments() {
  return request.get('/document/list')
}

export function uploadDocument(file, category) {
  const formData = new FormData()
  formData.append('file', file)
  if (category) {
    formData.append('category', category)
  }
  return request.post('/document/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export function deleteDocument(id) {
  return request.delete(`/document/${id}`)
}

export function embedDocument(id) {
  return request.post(`/document/embed/${id}`)
}
