<template>
  <div class="chat-container">
    <!-- 功能：可滚动消息列表容器，ref 绑定供自动滚动到底部｜要点：ref 获取 DOM -->
    <div ref="messageContainer" class="message-list">
      <!-- 功能：无历史时的空状态引导文案｜要点：v-if 条件渲染 -->
      <div v-if="messages.length === 0" class="empty-state">
        <el-icon style="font-size: 48px; color: #dcdfe6;"><ChatDotRound /></el-icon>
        <h3 style="font-size: 20px; color: #909399; margin: 16px 0 8px;">RAG 智能知识库问答</h3>
        <p style="font-size: 14px; color: #c0c4cc;">上传文档并向量化后，即可开始智能问答</p>
      </div>

      <!-- 功能：按角色渲染用户/AI 消息气泡｜要点：v-for 列表渲染 + :class 动态类名 -->
      <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
        <!-- 功能：角色标签；多 Agent 回答额外标注链路来源，让「深度思考」的效果可见｜要点：条件表达式 -->
        <div class="message-role">
          {{ msg.role === 'user' ? '你' : (isAgentMessage(msg) ? 'AI助手 · 多 Agent' : 'AI助手') }}
        </div>

        <!-- 功能：用户消息按纯文本渲染，避免内容被当 HTML 执行｜要点：v-html 仅用于已过滤的 AI 内容 -->
        <div v-if="msg.role === 'user'" class="message-content">{{ msg.content }}</div>

        <!-- 功能：AI 消息经 markdown-it 渲染 HTML 后由 v-html 注入｜要点：v-html + html:false 防 XSS -->
        <div v-else class="message-content markdown-body" v-html="renderMarkdown(msg.content)"></div>

        <!-- 功能：展示 AI 参考来源（相似度+片段），可折叠｜要点：RAG 来源引用 + el-collapse -->
        <div v-if="msg.sources" class="message-sources">
          <el-collapse>
            <el-collapse-item title="参考来源">
              <div v-for="(src, i) in parseSources(msg.sources)" :key="i" class="source-item">
                <div class="source-header">
                  <!-- 功能：RAG 来源有相似度分数，Agent 依据没有分数只有片段，按 type 区分渲染｜要点：不伪造 score -->
                  <el-tag v-if="typeof src.score === 'number'" size="small" type="info">
                    相似度 {{ (src.score * 100).toFixed(1) }}%
                  </el-tag>
                  <el-tag v-else size="small" type="warning">依据片段</el-tag>
                </div>
                <div class="source-content">{{ src.content }}</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <!-- 功能：AI 生成回答时的加载动画占位｜要点：loading 状态驱动 v-if -->
      <div v-if="loading" class="message assistant">
        <div class="message-role">AI助手</div>
        <div class="message-content loading-dots">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
    </div>

    <!-- 功能：问题输入框 + 发送按钮，回车提交｜要点：v-model 双向绑定本质 -->
    <div class="input-area">
      <!-- 功能：「深度思考」开关，切换后端链路（默认 RAG / 多 Agent 编排）｜要点：同一端点靠 mode 字段分流，回答照常落库 -->
      <div class="input-toolbar">
        <el-switch v-model="deepThink" :disabled="loading" active-text="深度思考" />
        <span class="toolbar-hint">
          {{ deepThink ? '多 Agent 编排：意图路由 → 专用 Agent → 反思评审' : '默认 RAG 链路：混合检索 → 精排 → 生成' }}
        </span>
      </div>
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
// 功能：「深度思考」开关，决定这次提问走后端哪条链路｜要点：开关状态只影响请求参数，不影响会话与历史（两条链路共用同一落库流程）
const deepThink = ref(false)
const messageContainer = ref(null)

const sessionId = computed(() =>
  route.params.sessionId ? Number(route.params.sessionId) : null
)

// 功能：初始化 markdown-it 实例，将大模型返回的 Markdown 渲染为 HTML｜要点：Markdown 渲染 + XSS 防护
// 常见问题：为什么 html: false？—— 大模型输出可能含 <script>/onerror 等恶意标签，禁用原始 HTML 可防 XSS；v-html 会执行 HTML，渲染前必须过滤
const md = new MarkdownIt({
  html: false,        // 功能：禁用原始 HTML 标签防 XSS｜要点：v-html 安全
  breaks: true,       // 功能：换行符转 <br>｜要点：markdown-it 配置
  linkify: true,      // 功能：自动识别并链接 URL｜要点：markdown-it 配置
})

function renderMarkdown(content) {
  return md.render(content || '')
}



// 功能：消息列表变化后自动滚动到底部｜要点：nextTick 时机 + watch 深度监听数组
// 常见问题：为什么用 nextTick？—— Vue DOM 更新异步，需在 nextTick 回调里才拿到最新 scrollHeight
// 常见问题：为什么 deep: true？—— messages 为数组，push 不改变引用，浅层 watch 不触发，需深度监听
watch(messages, () => {
  nextTick(() => {
    const el = messageContainer.value
    if (el) el.scrollTop = el.scrollHeight
  })
}, { deep: true })



// 功能：监听路由参数 sessionId，切换会话时重新加载历史｜要点：同组件复用不触发 onMounted + watch 响应式源
// 常见问题：为什么 onMounted 不够？—— Vue Router 复用同一组件实例时不销毁重建，onMounted 只执行一次，需 watch 路由参数

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
    // 功能：按开关决定链路——开启传 mode=agent 走后端多 Agent 编排，关闭不传走默认 RAG｜要点：后端同端点分流
    const res = await askQuestion(sessionId.value, q, deepThink.value ? 'agent' : undefined)
    // 功能：标记本次回答的链路，供气泡上的「多 Agent」标签使用（历史回读时改由 sources 的 type 推断）
    if (deepThink.value && res.data) {
      res.data.agentMode = true
    }
    messages.value.push(res.data)

    // 功能：首条消息后自动用问题前 20 字生成会话标题并同步全局 Store｜要点：全局状态共享 + 标题截断策略
    // 常见问题：为什么用 Store 而不是 emit？—— 标题在侧边栏（App.vue）展示，Store 全局共享可跨组件同步
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

// 功能：判断一条回答是否来自多 Agent 链路｜要点：实时回答带 agentMode 标记，历史回读时改看 sources 里有没有 type=evidence 的依据项
// 常见问题：为什么不把 mode 存进数据库？—— 需要加字段与迁移，而「依据片段」本身就是 agent 链路的产物，足以推断
function isAgentMessage(msg) {
  if (!msg || msg.role === 'user') return false
  if (msg.agentMode) return true
  return parseSources(msg.sources).some((src) => src && src.type === 'evidence')
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

/* 功能：AI 回复的 Markdown 渲染样式（由 markdown-it 生成 HTML）｜要点：v-html 渲染需配合 html:false 防 XSS */
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

/* 功能：AI 参考来源引用区块样式｜要点：RAG 来源展示 */
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

/* 功能：AI 思考中的三点加载动画样式｜要点：CSS 动画 */
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

/* 功能：「深度思考」开关工具条，显示当前链路｜要点：开关 + 说明文案同一行 */
.input-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.toolbar-hint {
  font-size: 12px;
  color: #909399;
}
</style>
