import { defineStore } from 'pinia'
import {
  getSessions,
  createSession as apiCreateSession,
  deleteSession as apiDeleteSession,
  updateSessionTitle as apiUpdateTitle,
} from '../api/chat'

// 功能：会话列表全局 Store，App.vue 侧边栏与 ChatView 共享同一数据源｜要点：Pinia 跨组件共享状态（替代组件内重复 ref）
// 常见问题：Pinia 相比 Vuex 强在哪？—— 无 mutation、API 更简洁、天生 TS 支持、组合式写法灵活
export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [],
    currentSessionId: null,
  }),

  actions: {
    async fetchSessions() {
      const res = await getSessions()
      this.sessions = res.data || []
    },

    async createSession() {
      const res = await apiCreateSession()
      this.sessions.unshift(res.data)
      this.currentSessionId = res.data.id
      return res.data
    },

    async deleteSession(id) {
      await apiDeleteSession(id)
      this.sessions = this.sessions.filter(s => s.id !== id)
      if (this.currentSessionId === id) {
        this.currentSessionId = null
      }
    },

    async updateTitle(id, title) {
      await apiUpdateTitle(id, title)
      const session = this.sessions.find(s => s.id === id)
      if (session) session.title = title
    },

    setCurrentSession(id) {
      this.currentSessionId = id
    },
  },
})
