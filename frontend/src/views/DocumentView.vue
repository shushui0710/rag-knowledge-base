<template>
  <div style="padding: 20px;">
    <h3 style="margin: 0 0 20px 0; font-size: 18px;">文档管理</h3>

    <el-upload
      action="/api/document/upload"
      :headers="{}"
      :on-success="handleUploadSuccess"
      :on-error="handleUploadError"
      accept=".pdf,.doc,.docx,.md,.txt"
      :show-file-list="false"
      drag
    >
      <el-icon style="font-size: 40px; color: #c0c4cc;"><UploadFilled /></el-icon>
      <div style="margin-top: 8px; color: #606266;">将文件拖到此处，或点击上传</div>
      <div style="font-size: 12px; color: #909399; margin-top: 4px;">支持 PDF / Word / Markdown / TXT</div>
    </el-upload>

    <el-table :data="documents" style="width: 100%; margin-top: 20px;">
      <el-table-column prop="title" label="文档名称" />
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getDocuments, deleteDocument as apiDelete, embedDocument as apiEmbed } from '../api/document'
import { ElMessage } from 'element-plus'

const documents = ref([])

onMounted(async () => {
  try {
    const res = await getDocuments()
    documents.value = res.data || []
  } catch (e) {
    console.warn('获取文档列表失败', e)
  }
})

function handleUploadSuccess(response) {
  if (response.code === 200) {
    ElMessage.success('文档上传成功')
    documents.value.push(response.data)
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
</script>
