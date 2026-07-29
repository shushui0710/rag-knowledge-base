import { defineStore } from 'pinia'
import {
  getSessions,
  createSession as apiCreateSession,
  deleteSession as apiDeleteSession,
  updateSessionTitle as apiUpdateTitle,
} from '../api/chat'

/**
 * 会话状态管理（Pinia Store）
 *
 * 为什么用 Pinia 而不是组件内 ref？
 *   App.vue 管理侧边栏的会话列表，ChatView.vue 负责发消息和更新标题。
 *   两个组件都需要读写 sessions，如果各自维护 ref 会导致数据不同步。
 *   Pinia 把共享状态提到 Store 层，任何组件都能直接读写同一个数据源。
 *
 * 面试考点：Pinia vs Vuex？
 *   Pinia 是 Vue3 官方推荐，API更简洁（没有mutation），TypeScript支持更好
 */
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
