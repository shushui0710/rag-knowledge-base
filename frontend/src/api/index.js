import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

/**
 * 请求拦截器 - 自动添加 JWT Token
 *
 * 每次发请求前，从 localStorage 取 token，加到 Authorization 头里。
 * 后端 JwtInterceptor 会从这个头解析 userId。
 */
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

/**
 * 响应拦截器 - 统一处理响应和错误
 *
 * - 业务成功（code=200）：返回 res
 * - 业务失败（code≠200）：弹错误提示
 * - HTTP 401（token无效/过期）：清理 token + 跳转登录页
 * - 网络错误：弹错误提示
 */
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
      // token 无效或过期，清理并跳转登录
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
