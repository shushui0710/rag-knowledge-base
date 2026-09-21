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

// 功能：索引重建的二次确认串，必须与后端 IndexRebuildService.CONFIRM_TOKEN 保持一致
// 常见问题：为什么放在前端也写一遍？——它只是"防误触"的确认口令，不承担鉴权职责（鉴权看角色），
//   但两端不一致会直接 400，所以这里留注释指向后端常量，改一处要同步另一处
export const REBUILD_CONFIRM_TOKEN = 'CONFIRM-REBUILD'

// 功能：受理索引重建（异步，立即返回任务快照）｜要点：仅 ADMIN 角色可调用，普通账号返回业务码 403
export function rebuildIndex() {
  return request.post('/document/rebuild-index', { confirm: REBUILD_CONFIRM_TOKEN })
}

// 功能：查询运维台状态（allowed = 当前账号可否运维；task = 最近一次任务快照，无权时为 null）
export function getRebuildStatus() {
  return request.get('/document/rebuild-index')
}
