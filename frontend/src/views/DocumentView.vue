<template>
  <div style="padding: 20px;">
    <h3 style="margin: 0 0 20px 0; font-size: 18px;">文档管理</h3>

    <div style="margin-bottom: 16px;">
      <el-select v-model="selectedCategory" placeholder="选择分类" style="width: 200px;" @change="loadDocuments">
        <el-option label="全部分类" value="" />
        <el-option label="规章制度" value="规章制度" />
        <el-option label="技术文档" value="技术文档" />
        <el-option label="培训资料" value="培训资料" />
        <el-option label="其他" value="其他" />
      </el-select>
    </div>

    <!-- 【缺陷修复·前后端白名单不一致】accept 原为 ".pdf,.doc,.docx,.md,.txt"，
         而后端 ALLOWED_FILE_TYPES 只有 {pdf, docx, md, txt}：用户按界面提示选中 .doc（Word 97-2003）
         能通过前端筛选，提交后才被后端拒绝。此处去掉 .doc，与后端白名单严格对齐 -->
    <el-upload
      :action="uploadUrl"
      :headers="uploadHeaders"
      :data="{ category: uploadCategory }"
      :on-success="handleUploadSuccess"
      :on-error="handleUploadError"
      accept=".pdf,.docx,.md,.txt"
      :show-file-list="false"
      drag
    >
      <el-icon style="font-size: 40px; color: #c0c4cc;"><UploadFilled /></el-icon>
      <div style="margin-top: 8px; color: #606266;">将文件拖到此处，或点击上传</div>
      <div style="font-size: 12px; color: #909399; margin-top: 4px;">支持 PDF / DOCX / Markdown / TXT，单个不超过 50MB</div>
    </el-upload>

    <div style="margin-top: 12px;">
      <span style="font-size: 13px; color: #909399; margin-right: 8px;">上传分类：</span>
      <el-select v-model="uploadCategory" size="small" style="width: 150px;">
        <el-option label="规章制度" value="规章制度" />
        <el-option label="技术文档" value="技术文档" />
        <el-option label="培训资料" value="培训资料" />
        <el-option label="其他" value="其他" />
      </el-select>
    </div>

    <el-table :data="documents" style="width: 100%; margin-top: 20px;">
      <el-table-column prop="title" label="文档名称" />
      <el-table-column prop="category" label="分类" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="categoryTagType(row.category)">{{ row.category || '其他' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fileType" label="类型" width="80" />
      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">
          {{ formatSize(row.fileSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="embeddingStatus" label="向量化状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.embeddingStatus === 1 ? 'success' : 'warning'" size="small">
            {{ row.embeddingStatus === 1 ? '已入库' : '待入库' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button
            v-if="row.embeddingStatus !== 1"
            type="primary"
            size="small"
            @click="embedDocument(row.id)"
          >
            向量化
          </el-button>
          <el-button type="danger" size="small" @click="deleteDocument(row.id)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ==================== 运维专区：向量索引重建 ====================
         【设计要点】普通账号请求状态接口同样会成功返回（不是 403），但 allowed=false ⇒ 整块不渲染。
           即 UI 与后端闸门同源：前端不复制任何白名单/角色判断，后端才是唯一判据，
           避免"前端藏了按钮、接口却裸奔"的假安全 -->
    <div
      v-if="rebuildAllowed"
      style="margin-top: 28px; padding: 16px; border: 1px solid #e4e7ed; border-radius: 6px; background: #fafafa;"
    >
      <div style="display: flex; align-items: center; justify-content: space-between;">
        <div>
          <div style="font-size: 14px; font-weight: 600; color: #303133;">运维专区 · 向量索引重建</div>
          <div style="font-size: 12px; color: #909399; margin-top: 4px;">
            按 MySQL 分块重新向量化全库文档并重灌 Milvus 索引；执行期间检索服务不中断
          </div>
        </div>
        <el-button
          type="warning"
          :loading="rebuildRunning"
          :disabled="rebuildRunning"
          @click="handleRebuild"
        >
          {{ rebuildRunning ? '重建进行中…' : '重建索引' }}
        </el-button>
      </div>

      <div
        v-if="rebuildTask"
        style="margin-top: 16px; border-top: 1px dashed #dcdfe6; padding-top: 12px;"
      >
        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap;">
          <el-tag :type="rebuildTagType" size="small">{{ rebuildTask.phase }}</el-tag>
          <span style="font-size: 12px; color: #909399;">
            任务号 {{ rebuildTask.taskId }} · 操作人 {{ rebuildTask.operator }}
          </span>
        </div>

        <el-progress
          :percentage="rebuildPercent"
          :status="rebuildTask.status === 'FAILED' ? 'exception' : (rebuildTask.status === 'SUCCESS' ? 'success' : '')"
          :stroke-width="14"
        />

        <div style="display: flex; gap: 24px; margin-top: 10px; font-size: 12px; color: #606266; flex-wrap: wrap;">
          <span>已回放 <b>{{ rebuildTask.replayedDocs }}</b> / {{ rebuildTask.totalDocs }} 篇</span>
          <span>已处理 {{ rebuildTask.processedDocs }} 篇</span>
          <span>耗时 {{ formatDuration(rebuildTask.elapsedMillis) }}</span>
          <span>路径：{{ rebuildTask.needDrop ? '影子表切换' : '原地重灌' }}</span>
        </div>

        <div
          v-if="rebuildTask.message"
          style="margin-top: 8px; font-size: 12px;"
          :style="{ color: rebuildTask.status === 'FAILED' ? '#f56c6c' : '#909399' }"
        >
          {{ rebuildTask.message }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  getDocuments,
  deleteDocument as apiDelete,
  embedDocument as apiEmbed,
  rebuildIndex,
  getRebuildStatus,
} from '../api/document'
import { ElMessage, ElMessageBox } from 'element-plus'

const documents = ref([])
const selectedCategory = ref('')
const uploadCategory = ref('技术文档')

// 功能：运维台状态——allowed 决定按钮是否可见，task 是最近一次重建任务快照
const rebuildAllowed = ref(false)
const rebuildTask = ref(null)
let rebuildTimer = null

// 功能：任务进行中则按钮转圈并轮询进度｜要点：进度来自 GET /document/rebuild-index，不阻塞页面其他请求
const rebuildRunning = computed(() => rebuildTask.value?.status === 'RUNNING')
const rebuildTagType = computed(() => {
  const status = rebuildTask.value?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
})

// 功能：进度百分比｜要点：分母用 totalDocs（待回放文档数）而非分块数——运维关心的是"还剩几篇"
const rebuildPercent = computed(() => {
  const task = rebuildTask.value
  if (!task) return 0
  if (task.status === 'SUCCESS') return 100
  if (!task.totalDocs) return 0
  const percent = Math.round(((task.processedDocs || 0) / task.totalDocs) * 100)
  return Math.min(100, Math.max(0, percent))
})

// 功能：el-upload 的 action 与动态 headers 配置（手动注入 Bearer token）｜要点：el-upload 自定义上传 + 鉴权
const uploadUrl = '/api/document/upload'
const uploadHeaders = computed(() => {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
})

onMounted(async () => {
  await loadDocuments()
  // 功能：进页面就确认"我是不是运维账号、有没有任务在跑"｜要点：普通账号也成功返回，只是 allowed=false
  await loadRebuildStatus()
})

// 功能：离页清轮询定时器｜要点：不讲道理的定时器会拖着组件实例不放，切页后仍在偷偷发请求
onUnmounted(() => {
  stopRebuildPolling()
})

async function loadDocuments() {
  try {
    const res = await getDocuments()
    let docs = res.data || []
    if (selectedCategory.value) {
      docs = docs.filter(d => d.category === selectedCategory.value)
    }
    documents.value = docs
  } catch (e) {
    console.warn('获取文档列表失败', e)
  }
}

function handleUploadSuccess(response) {
  if (response.code === 200) {
    ElMessage.success('文档上传成功')
    loadDocuments()
  } else {
    ElMessage.error(response.message || '上传失败')
  }
}

function handleUploadError() {
  ElMessage.error('文档上传失败')
}

async function embedDocument(id) {
  try {
    await apiEmbed(id)
    ElMessage.success('向量化入库完成')
    const doc = documents.value.find(d => d.id === id)
    if (doc) doc.embeddingStatus = 1
  } catch (e) {
    ElMessage.error('向量化失败')
  }
}

async function deleteDocument(id) {
  try {
    await apiDelete(id)
    ElMessage.success('删除成功')
    documents.value = documents.value.filter(d => d.id !== id)
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (bytes >= 1024 && i < units.length - 1) {
    bytes /= 1024
    i++
  }
  return bytes.toFixed(1) + ' ' + units[i]
}

// 功能：把毫秒耗时转成人话｜要点：全库重建是分钟级，只显示"123s"读起来费劲
function formatDuration(ms) {
  if (ms === null || ms === undefined) return '-'
  if (ms < 1000) return ms + ' ms'
  const totalSeconds = Math.floor(ms / 1000)
  if (totalSeconds < 60) return totalSeconds + ' 秒'
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes + ' 分 ' + seconds + ' 秒'
}

function categoryTagType(category) {
  const map = {
    '规章制度': 'danger',
    '技术文档': 'primary',
    '培训资料': 'success',
    '其他': 'info',
  }
  return map[category] || 'info'
}

// ==================== 运维操作：索引重建 ====================

// 功能：拉取运维台状态｜要点：普通账号也会成功返回 allowed=false，因此这里不该弹错
async function loadRebuildStatus() {
  try {
    const res = await getRebuildStatus()
    rebuildAllowed.value = !!res.data?.allowed
    rebuildTask.value = res.data?.task || null
    if (rebuildRunning.value) startRebuildPolling()
  } catch (e) {
    rebuildAllowed.value = false
    rebuildTask.value = null
  }
}

// 功能：任务在跑时每 2s 轮询一次进度，结束即停
function startRebuildPolling() {
  if (rebuildTimer) return
  rebuildTimer = setInterval(async () => {
    try {
      const res = await getRebuildStatus()
      rebuildTask.value = res.data?.task || null
      if (!rebuildRunning.value) stopRebuildPolling()
    } catch (e) {
      stopRebuildPolling()
    }
  }, 2000)
}

function stopRebuildPolling() {
  if (rebuildTimer) {
    clearInterval(rebuildTimer)
    rebuildTimer = null
  }
}

// 功能：二次确认后受理重建｜要点：真正的权限校验在后端，这里只做误触拦截与体验反馈
async function handleRebuild() {
  try {
    await ElMessageBox.confirm(
      '将按 MySQL 分块重新向量化全库文档并重灌 Milvus 索引（耗时从数秒到数分钟不等），执行期间检索服务不中断。确定继续？',
      '运维操作确认',
      { type: 'warning', confirmButtonText: '确定重建', cancelButtonText: '取消' },
    )
  } catch (e) {
    return
  }
  try {
    const res = await rebuildIndex()
    rebuildTask.value = res.data
    ElMessage.success('已受理，任务在后台执行')
    startRebuildPolling()
  } catch (e) {
    // 无权限 / 确认串错误 / 已有任务在跑：错误提示由 axios 响应拦截器统一弹出，这里不再重复提示
  }
}
</script>
