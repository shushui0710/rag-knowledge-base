<template>
  <el-container style="height: 100vh">
    <el-aside width="260px" style="border-right: 1px solid #e4e7ed; background: #f5f7fa;">
      <div style="padding: 20px;">
        <h2 style="font-size: 18px; margin: 0 0 20px 0; color: #303133;">RAG 知识库</h2>
        <el-button type="primary" style="width: 100%;" @click="createSession">
          + 新对话
        </el-button>
        <div style="margin-top: 16px;">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session-item"
            :class="{ active: currentSessionId === session.id }"
            @click="switchSession(session.id)"
          >
            {{ session.title }}
          </div>
        </div>
      </div>
      <div style="position: absolute; bottom: 20px; left: 0; width: 260px; padding: 0 20px;">
        <el-menu>
          <el-menu-item index="documents" @click="$router.push('/documents')">
            <el-icon><FolderOpened /></el-icon>
            <span>文档管理</span>
          </el-menu-item>
        </el-menu>
      </div>
    </el-aside>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSessions, createSession as apiCreateSession } from './api/chat'

const router = useRouter()
const sessions = ref([])
const currentSessionId = ref(null)

onMounted(async () => {
  try {
    const res = await getSessions()
    sessions.value = res.data || []
  } catch (e) {
    console.warn('获取会话列表失败', e)
  }
})

async function createSession() {
  const res = await apiCreateSession()
  sessions.value.push(res.data)
  currentSessionId.value = res.data.id
  router.push(`/chat/${res.data.id}`)
}

function switchSession(id) {
  currentSessionId.value = id
  router.push(`/chat/${id}`)
}
</script>

<style>
.session-item {
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
</style>
