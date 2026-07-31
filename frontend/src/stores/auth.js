import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, getCurrentUser as apiGetMe } from '../api/auth'

/**
 * 认证状态管理（Pinia Store）
 *
 * 职责：
 *   - 管理 token（存 localStorage）
 *   - 管理 user 信息（存内存，刷新页面时从 localStorage 恢复 token + 重新拉取 user）
 *   - 提供 login / register / logout 方法
 *
 * 为什么 token 存 localStorage 而不是 cookie？
 *   - localStorage 不会被自动发送到服务端，避免 CSRF
 *   - 需要手动在 axios 拦截器里加 Authorization 头，可控性强
 *   - cookie 方案需要配置 httpOnly + Secure，前后端分离时跨域配置更复杂
 */
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
        // token 无效，清理
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
