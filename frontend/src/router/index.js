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

// 功能：全局前置守卫 beforeEach 校验登录态，未登录重定向 /login、已登录访问登录页跳回 /chat｜要点：导航守卫 + 白名单机制
// 常见问题：next() 为何要每个分支显式调用？—— 不调用会卡住导航；Vue Router 4 更推荐直接 return 目标路由名替代 next()
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
