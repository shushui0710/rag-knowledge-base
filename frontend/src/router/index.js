import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import DocumentView from '../views/DocumentView.vue'
import LoginView from '../views/LoginView.vue'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
  },
  {
    path: '/',
    redirect: '/chat',
  },
  {
    path: '/chat/:sessionId?',
    name: 'chat',
    component: ChatView,
  },
  {
    path: '/documents',
    name: 'documents',
    component: DocumentView,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 全局路由守卫 - 未登录用户重定向到登录页
 *
 * beforeEach 在每次路由跳转前执行：
 *   - 有 token → 放行
 *   - 无 token + 不是登录页 → 重定向到 /login
 *   - 无 token + 是登录页 → 放行
 */
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (!token && to.name !== 'login') {
    next({ name: 'login' })
  } else if (token && to.name === 'login') {
    next({ name: 'chat' })
  } else {
    next()
  }
})

export default router
