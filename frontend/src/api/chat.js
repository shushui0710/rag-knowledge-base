import request from './index'

export function getSessions() {
  return request.get('/chat/sessions')
}

export function createSession() {
  return request.post('/chat/session')
}

export function askQuestion(sessionId, question, mode) {
  // 后端 @RequestBody AskRequest 期望对象 {"question": "..."}，不能发裸字符串
  // mode 为 'agent' 时走多 Agent 编排链路（深度思考），不传则后端按默认 RAG 链路处理
  const body = mode ? { question, mode } : { question }
  return request.post(`/chat/ask/${sessionId}`, body)
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
