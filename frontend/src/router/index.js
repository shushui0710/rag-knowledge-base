import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import DocumentView from '../views/DocumentView.vue'

const routes = [
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

export default router
