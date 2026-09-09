import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, getCurrentUser as apiGetMe } from '../api/auth'

// 功能：认证 Store 管理 token（localStorage 持久化）+ user 信息（刷新后凭 token 重新拉取）｜要点：Pinia vs Vuex（去 mutation、TS 友好、组合式）
// 常见问题：token 为何存 localStorage 而非 cookie？—— 不随请求自动发送，天然规避 CSRF；代价是需手动注入 Authorization 头且要防 XSS
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: null,
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
  },

  actions: {
    async login(username, password) {
      const res = await apiLogin(username, password)
      this.token = res.data.token
      this.user = res.data.user
      localStorage.setItem('token', this.token)
    },

    async register(username, password) {
      const res = await apiRegister(username, password)
      return res.data
    },

    async fetchUser() {
      if (!this.token) return
      try {
        const res = await apiGetMe()
        this.user = res.data
      } catch (e) {
        // 功能：拉取用户信息失败说明 token 已失效，调用 logout 清理｜要点：token 过期自愈
        this.logout()
      }
    },

    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
    },
  },
})
