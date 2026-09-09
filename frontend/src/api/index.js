import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

// 功能：请求拦截器统一注入 Authorization: Bearer <token>｜要点：无状态认证（JWT）的客户端配合
// 常见问题：token 存 localStorage 有什么风险？—— XSS 可读，需配合输出转义/CSP；httpOnly cookie 可防 XSS 但需防 CSRF
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 功能：响应拦截器统一拆包（code=200 返回 data）并集中处理错误｜要点：Axios 拦截器 + 401 统一登出
// 常见问题：401 为何集中处理？—— token 失效/过期时统一清 token 并跳登录，避免每个请求散落重复逻辑
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      // 功能：401 时清除过期 token 并跳转登录页｜要点：统一鉴权失效处理
      localStorage.removeItem('token')
      ElMessage.warning('登录已过期，请重新登录')
      router.push('/login')
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
