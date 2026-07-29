import request from './index'

export function getSessions() {
  return request.get('/chat/sessions')
}

export function createSession() {
  return request.post('/chat/session')
}

export function askQuestion(sessionId, question) {
  return request.post(`/chat/ask/${sessionId}`, JSON.stringify(question), {
    headers: { 'Content-Type': 'application/json' },
  })
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
