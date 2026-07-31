<template>
  <el-container style="height: 100vh">
    <el-aside width="260px" style="border-right: 1px solid #e4e7ed; background: #f5f7fa; position: relative;">
      <div style="padding: 20px;">
        <h2 style="font-size: 18px; margin: 0 0 20px 0; color: #303133;">RAG 知识库</h2>
        <el-button type="primary" style="width: 100%;" @click="createSession">
          + 新对话
        </el-button>
        <div style="margin-top: 16px;">
          <div
            v-for="session in chatStore.sessions"
            :key="session.id"
            class="session-item"
            :class="{ active: chatStore.currentSessionId === session.id }"
            @click="switchSession(session.id)"
          >
            <span class="session-title">{{ session.title }}</span>
            <el-icon
              class="session-delete"
              @click.stop="deleteSession(session.id)"
            >
              <Close />
            </el-icon>
          </div>
        </div>
      </div>
      <div style="position: absolute; bottom: 20px; left: 0; width: 260px; padding: 0 20px;">
        <el-menu>
          <el-menu-item index="documents" @click="$router.push('/documents')">
            <el-icon><FolderOpened /></el-icon>
            <span>文档管理</span>
          </el-menu-item>
          <el-menu-item index="chat" @click="$router.push('/chat')">
            <el-icon><ChatDotRound /></el-icon>
            <span>返回对话</span>
          </el-menu-item>
        </el-menu>
        <div class="user-info">
          <el-icon><UserFilled /></el-icon>
          <span class="username">{{ authStore.user?.nickname || authStore.user?.username || '用户' }}</span>
          <el-button text size="small" @click="handleLogout">退出</el-button>
        </div>
      </div>
    </el-aside>
    <el-main style="padding: 0;">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useChatStore } from './stores/chat'
import { useAuthStore } from './stores/auth'

const router = useRouter()
const chatStore = useChatStore()
const authStore = useAuthStore()

onMounted(async () => {
  // 先恢复用户信息
  if (authStore.token && !authStore.user) {
    await authStore.fetchUser()
  }
  try {
    await chatStore.fetchSessions()
  } catch (e) {
    console.warn('获取会话列表失败', e)
  }
})

async function createSession() {
  try {
    const session = await chatStore.createSession()
    router.push(`/chat/${session.id}`)
  } catch (e) {
    ElMessage.error('创建会话失败')
  }
}

function switchSession(id) {
  chatStore.setCurrentSession(id)
  router.push(`/chat/${id}`)
}

async function deleteSession(id) {
  try {
    await ElMessageBox.confirm('确定删除这个会话？删除后不可恢复。', '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await chatStore.deleteSession(id)
    ElMessage.success('删除成功')
    if (router.currentRoute.value.params.sessionId == id) {
      router.push('/chat')
    }
  } catch (e) {
    if (e !== 'cancel') console.warn('删除失败', e)
  }
}

function handleLogout() {
  authStore.logout()
  router.push('/login')
}

</script>

<style>
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  margin: 4px 0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: #606266;
  transition: background 0.2s;
}

.session-item:hover {
  background: #ecf5ff;
}

.session-item.active {
  background: #409eff;
  color: #fff;
}

.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-delete {
  opacity: 0;
  transition: opacity 0.2s;
  font-size: 14px;
  flex-shrink: 0;
  margin-left: 8px;
}

.session-item:hover .session-delete {
  opacity: 0.6;
}

.session-item:hover .session-delete:hover {
  opacity: 1;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid #e4e7ed;
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}

.username {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
