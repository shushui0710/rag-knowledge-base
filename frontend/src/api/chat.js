import request from './index'

export function getSessions() {
  return request.get('/chat/sessions')
}

export function createSession() {
  return request.post('/chat/session')
}

export function askQuestion(sessionId, question) {
  // 后端 @RequestBody AskRequest 期望对象 {"question": "..."}，不能发裸字符串
  return request.post(`/chat/ask/${sessionId}`, { question })
}

export function getHistory(sessionId) {
  return request.get(`/chat/history/${sessionId}`)
}

export function deleteSession(sessionId) {
  return request.delete(`/chat/session/${sessionId}`)
}

export function updateSessionTitle(sessionId, title) {
  return request.put(`/chat/session/${sessionId}/title`, JSON.stringify(title), {
    headers: { 'Content-Type': 'application/json' },
  })
}
