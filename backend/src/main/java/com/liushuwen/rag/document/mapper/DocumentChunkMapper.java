package com.liushuwen.rag.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liushuwen.rag.document.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档分块表 Mapper：继承 BaseMapper 获得对 document_chunk 表的单表 CRUD。
 * 支撑按 document_id 查分块（向量化前取文本）与删文档时清理分块两类高频操作。
 * 【设计要点】MP 单表能力复用：与 DocumentMapper 同样零 SQL，增删改查全走 BaseMapper 通用方法
 * 【常见问题】按 document_id 批量删分块用什么？——LambdaQueryWrapper 拼条件后调 delete(wrapper)，无需自定义 SQL；为什么不用原生 SQL 拼 IN？——Wrapper 参数化预编译，天然防注入
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
}
