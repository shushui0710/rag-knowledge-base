<template>
  <div style="display: flex; flex-direction: column; height: 100%; padding: 0;">
    <div style="flex: 1; overflow-y: auto; padding: 20px;">
      <div v-if="messages.length === 0" style="text-align: center; color: #c0c4cc; margin-top: 120px;">
        <h3 style="font-size: 20px; color: #909399;">RAG 智能知识库问答</h3>
        <p style="font-size: 14px;">上传文档后，即可开始智能问答</p>
      </div>
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
        <div class="message-role">{{ msg.role === 'user' ? '你' : 'AI助手' }}</div>
        <div class="message-content">{{ msg.content }}</div>
        <div v-if="msg.sources" class="message-sources">
          <span style="color: #909399; font-size: 12px;">来源引用：</span>
          <el-tag v-for="(src, i) in parseSources(msg.sources)" :key="i" size="small" type="info">
            {{ src }}
          </el-tag>
        </div>
      </div>
    </div>
    <div style="padding: 16px 20px; border-top: 1px solid #e4e7ed;">
      <el-input
        v-model="question"
        placeholder="输入你的问题..."
        size="large"
        @keyup.enter="sendQuestion"
      >
        <template #append>
          <el-button type="primary" @click="sendQuestion" :loading="loading">发送</el-button>
        </template>
      </el-input>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { askQuestion, getHistory } from '../api/chat'

const route = useRoute()
const question = ref('')
const messages = ref([])
const loading = ref(false)

const sessionId = computed(() => route.params.sessionId)

onMounted(async () => {
  if (sessionId.value) {
    try {
      const res = await getHistory(sessionId.value)
      messages.value = res.data || []
    } catch (e) {
      console.warn('加载历史失败', e)
    }
  }
})

async function sendQuestion() {
  if (!question.value.trim() || !sessionId.value) return
  const q = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  loading.value = true
  try {
    const res = await askQuestion(sessionId.value, q)
    messages.value.push(res.data)
  } catch (e) {
    messages.value.push({ role: 'assistant', content: '抱歉，回答生成失败，请重试。' })
  }
  loading.value = false
}

function parseSources(sources) {
  if (!sources) return []
  try {
    return JSON.parse(sources)
  } catch {
    return [sources]
  }
}
</script>

<style>
.message {
  margin: 12px 0;
  max-width: 80%;
}
.message.user {
  margin-left: auto;
  text-align: right;
}
.message.assistant {
  margin-right: auto;
  text-align: left;
}
.message-role {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.message-content {
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
}
.message.user .message-content {
  background: #409eff;
  color: #fff;
}
.message.assistant .message-content {
  background: #f4f4f5;
  color: #303133;
}
.message-sources {
  margin-top: 8px;
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
</style>
