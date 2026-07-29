<template>
  <div class="chat-container">
    <!-- 消息列表（可滚动区域） -->
    <div ref="messageContainer" class="message-list">
      <!-- 空状态 -->
      <div v-if="messages.length === 0" class="empty-state">
        <el-icon style="font-size: 48px; color: #dcdfe6;"><ChatDotRound /></el-icon>
        <h3 style="font-size: 20px; color: #909399; margin: 16px 0 8px;">RAG 智能知识库问答</h3>
        <p style="font-size: 14px; color: #c0c4cc;">上传文档并向量化后，即可开始智能问答</p>
      </div>

      <!-- 消息气泡 -->
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
        <div class="message-role">{{ msg.role === 'user' ? '你' : 'AI助手' }}</div>

        <!-- 用户消息：纯文本 -->
        <div v-if="msg.role === 'user'" class="message-content">{{ msg.content }}</div>

        <!-- AI消息：Markdown渲染（TODO 1完成后生效） -->
        <div v-else class="message-content markdown-body" v-html="renderMarkdown(msg.content)"></div>

        <!-- 来源引用（可折叠） -->
        <div v-if="msg.sources" class="message-sources">
          <el-collapse>
            <el-collapse-item title="参考来源">
              <div v-for="(src, i) in parseSources(msg.sources)" :key="i" class="source-item">
                <div class="source-header">
                  <el-tag size="small" type="info">
                    相似度 {{ (src.score * 100).toFixed(1) }}%
                  </el-tag>
                </div>
                <div class="source-content">{{ src.content }}</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <!-- 加载中提示（AI正在思考） -->
      <div v-if="loading" class="message assistant">
        <div class="message-role">AI助手</div>
        <div class="message-content loading-dots">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <el-input
        v-model="question"
        placeholder="输入你的问题..."
        size="large"
        @keyup.enter="sendQuestion"
        :disabled="loading"
      >
        <template #append>
          <el-button type="primary" @click="sendQuestion" :loading="loading">发送</el-button>
        </template>
      </el-input>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, nextTick, computed } from 'vue'
import { useRoute } from 'vue-router'
import { askQuestion, getHistory } from '../api/chat'
import { useChatStore } from '../stores/chat'
import MarkdownIt from 'markdown-it'

const route = useRoute()
const chatStore = useChatStore()
const question = ref('')
const messages = ref([])
const loading = ref(false)
const messageContainer = ref(null)

const sessionId = computed(() =>
  route.params.sessionId ? Number(route.params.sessionId) : null
)

// ============================================================
// TODO 1（⭐⭐ 难度）：初始化 markdown-it 实例 + renderMarkdown 函数
//
// 背景：DeepSeek 的回答是 Markdown 格式（含 ## 标题、- 列表、``` 代码块），
// 直接用 {{ }} 显示是一坨纯文本。需要用 markdown-it 渲染成 HTML。
//
// 提示：
//   const md = new MarkdownIt({
//     html: false,        // 禁止原始HTML标签（防XSS）
//     breaks: true,       // 换行符 → <br>
//     linkify: true,      // 自动识别URL
//   })
//
//   function renderMarkdown(content) {
//     return md.render(content || '')
//   }
//
// 面试考点：
//   - 为什么 html: false？—— 防止大模型输出 <script> 等恶意标签（XSS）
//   - v-html 的安全风险？—— v-html 会执行HTML，所以渲染前必须过滤（html:false）
// ============================================================
const md = new MarkdownIt({
  html: false,        // 禁止原始HTML标签（防XSS）
  breaks: true,       // 换行符 → <br>
  linkify: true,      // 自动识别URL
})

function renderMarkdown(content) {
  return md.render(content || '')
}



// ============================================================
// TODO 2（⭐⭐ 难度）：消息列表自动滚动到底部
//
// 背景：AI 回答很长，新消息会超出可视区域。不自动滚动，用户看不到最新回答。
//
// 提示：
//   watch(messages, () => {
//     nextTick(() => {
//       const el = messageContainer.value
//       if (el) el.scrollTop = el.scrollHeight
//     })
//   }, { deep: true })
//
// 面试考点：
//   - 为什么用 nextTick？—— Vue 更新 DOM 是异步的，直接操作拿到的是旧高度
//   - deep: true？—— messages 是数组，push 不会触发浅层 watch，需要 deep
// ============================================================
watch(messages, () => {
  nextTick(() => {
    const el = messageContainer.value
    if (el) el.scrollTop = el.scrollHeight
  })
}, { deep: true })



// ============================================================
// TODO 3（⭐⭐ 难度）：监听路由参数变化，切换会话时重新加载历史
//
// 背景：onMounted 只在组件首次创建时执行。用户在侧边栏点击另一个会话时，
// URL 从 /chat/3 变成 /chat/5，但 ChatView 组件没有销毁重建（同一路由复用），
// onMounted 不会再次触发，导致历史不刷新。
//
// 提示：
//   watch(() => route.params.sessionId, async (newId) => {
//     if (newId) {
//       try {
//         const res = await getHistory(newId)
//         messages.value = res.data || []
//       } catch (e) {
//         console.warn('加载历史失败', e)
//       }
//     } else {
//       messages.value = []
//     }
//   })
//
// 面试考点：
//   - 为什么 onMounted 不够？—— Vue Router 同组件复用，不触发 onMounted
//   - watch 的第一个参数为什么是函数？—— 监听响应式数据的变化
// ============================================================

watch(() => route.params.sessionId, async (newId) =>{
  if(newId){
    try{
      const res =await getHistory(newId)
      messages.value =res.data ||[]
    }catch (e) {
      console.warn('加载历史失败', e)
    }
  }else{
    messages.value = []
  }
})


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
  if (!question.value.trim() || !sessionId.value || loading.value) return
  const q = question.value.trim()
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  loading.value = true
  try {
    const res = await askQuestion(sessionId.value, q)
    messages.value.push(res.data)

    // ============================================================
    // TODO 4（⭐⭐ 难度）：如果是第一条消息，自动更新会话标题
    //
    // 背景：新会话标题都是"新对话"，侧边栏分不清。第一条消息后，
    // 用问题前20字作为标题，调用 Pinia store 的 updateTitle 方法。
    //
    // 提示：
    //   if (messages.value.length === 2) {  // 1条用户 + 1条AI = 2条
    //     const title = q.length > 20 ? q.substring(0, 20) + '...' : q
    //     await chatStore.updateTitle(sessionId.value, title)
    //   }
    //
    // 面试考点：
    //   - 为什么前端管标题？—— UI 逻辑在前端，后端只管存储
    //   - 为什么截断20字？—— 侧边栏宽度有限
    //   - 为什么用 Store 而不是 emit？—— Store 是全局状态，任何组件都能同步
    // ============================================================
    if (messages.value.length === 2) {
      const title = q.length > 20 ? q.substring(0, 20) + '...' : q
      await chatStore.updateTitle(sessionId.value, title)
    }



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
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.empty-state {
  text-align: center;
  margin-top: 120px;
}

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

/* Markdown 渲染样式 */
.markdown-body h1,
.markdown-body h2,
.markdown-body h3 {
  margin: 12px 0 8px;
  font-weight: 600;
}

.markdown-body h1 { font-size: 18px; }
.markdown-body h2 { font-size: 16px; }
.markdown-body h3 { font-size: 14px; }

.markdown-body p {
  margin: 6px 0;
}

.markdown-body ul,
.markdown-body ol {
  margin: 6px 0;
  padding-left: 20px;
}

.markdown-body li {
  margin: 4px 0;
}

.markdown-body code {
  background: #e8e8e8;
  padding: 2px 4px;
  border-radius: 3px;
  font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
}

.markdown-body pre {
  background: #2d2d2d;
  color: #f8f8f2;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 8px 0;
}

.markdown-body pre code {
  background: none;
  padding: 0;
  color: inherit;
}

.markdown-body blockquote {
  border-left: 3px solid #dcdfe6;
  padding-left: 12px;
  color: #606266;
  margin: 8px 0;
}

.markdown-body table {
  border-collapse: collapse;
  margin: 8px 0;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid #dcdfe6;
  padding: 6px 12px;
}

/* 来源引用 */
.message-sources {
  margin-top: 8px;
}

.source-item {
  margin: 8px 0;
  padding: 8px;
  background: #fafafa;
  border-radius: 6px;
}

.source-header {
  margin-bottom: 4px;
}

.source-content {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
}

/* 加载动画（三个跳动的点） */
.loading-dots {
  display: flex;
  gap: 4px;
  align-items: center;
  min-height: 24px;
}

.loading-dots .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #909399;
  animation: dot-bounce 1.4s infinite ease-in-out both;
}

.loading-dots .dot:nth-child(1) { animation-delay: -0.32s; }
.loading-dots .dot:nth-child(2) { animation-delay: -0.16s; }

@keyframes dot-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

.input-area {
  padding: 16px 20px;
  border-top: 1px solid #e4e7ed;
}
</style>
