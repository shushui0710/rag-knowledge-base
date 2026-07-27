package com.liushuwen.rag.document.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.common.BusinessException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus向量数据库操作服务
 *
 * Milvus是什么？
 * 专门存"向量"的数据库。MySQL存的是文字数字，Milvus存的是向量（2048个浮点数）。
 * 它的核心能力是"向量相似度搜索"——给一个查询向量，找出最相似的K个向量。
 *
 * 本类提供三个核心操作：
 * 1. ensureCollection() - 建表（如果不存在的话）
 * 2. insertVectors() - 插入向量数据
 * 3. search() - 向量搜索（下周问答功能用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusServiceClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    /**
     * 启动时自动建表
     * @PostConstruct = "这个Bean创建后自动执行这个方法"
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("Milvus初始化失败（可能Milvus还没启动）: {}", e.getMessage());
        }
    }

    /**
     * 创建Collection（相当于MySQL的CREATE TABLE）
     *
     * Milvus的Collection需要定义"字段"（Schema），就像MySQL建表要定义列。
     * 我们需要4个字段：
     * - id: 主键（对应MySQL document_chunk.id）
     * - document_id: 文档ID（用于按文档过滤）
     * - content: 文本内容（搜索时直接返回，不用再查MySQL）
     * - embedding: 向量字段（2048维浮点数组，核心字段）
     */
    public void ensureCollection() {
        try {
            // 先检查collection是否已存在
            R<ShowCollectionsResponse> showResp = milvusServiceClient.showCollections(
                    ShowCollectionsParam.newBuilder().build());
            for (String name : showResp.getData().getCollectionNamesList()) {
                if (name.equals(collectionName)) {
                    log.info("Milvus collection已存在: {}", collectionName);
                    return;
                }
            }

            // ============================================================
            // TODO 4（⭐⭐⭐ 难度）：定义Collection的Schema（字段列表）
            //
            // 需要定义4个字段，每个字段用 FieldType 描述：
            //
            // 字段1 - id（主键）：
            //   FieldType.newBuilder()
            //       .withName("id")              // 字段名
            //       .withDataType(DataType.Int64) // 数据类型：64位整数
            //       .withPrimaryKey(true)         // 是主键
            //       .withAutoID(false)            // 不自动生成ID（用MySQL的chunk id）
            //       .build()
            //
            // 字段2 - document_id（文档ID）：
            //   FieldType.newBuilder()
            //       .withName("document_id")
            //       .withDataType(DataType.Int64)
            //       .build()
            //
            // 字段3 - content（文本内容）：
            //   FieldType.newBuilder()
            //       .withName("content")
            //       .withDataType(DataType.VarChar) // 变长字符串
            //       .withMaxLength(2048)            // 最大长度
            //       .build()
            //
            // 字段4 - embedding（向量，核心字段）：
            //   FieldType.newBuilder()
            //       .withName("embedding")
            //       .withDataType(DataType.FloatVector) // 浮点向量
            //       .withDimension(dimension)           // 维度2048
            //       .build()
            //
            // 然后创建 CreateCollectionParam：
            //   CreateCollectionParam.newBuilder()
            //       .withCollectionName(collectionName)
            //       .withFieldTypes(List.of(idField, documentIdField, contentField, embeddingField))
            //       .build()
            //
            // 把下面这段替换成你的实现：
            // ============================================================
            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();
            FieldType documentIdField=FieldType.newBuilder()
                    .withName("document_id")
                    .withDataType(DataType.Int64)
                    .build();
            FieldType contentField=FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(2048)
                    .build();
            FieldType embeddingField=FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build();

            List<FieldType> fieldTypes = List.of(idField, documentIdField, contentField, embeddingField);
            CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                    .withFieldTypes(fieldTypes) 
                    .build();
            
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withSchema(schema)
                    .build();



            milvusServiceClient.createCollection(createParam);
            log.info("Milvus collection创建成功: {}", collectionName);

            // 创建向量索引（加速能搜索）
            CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)           // 集合名称
                    .withFieldName("embedding")                    // 向量字段名
                    .withIndexType(IndexType.IVF_FLAT)             // 索引类型
                    .withMetricType(MetricType.COSINE)             // 相似度度量
                    .withExtraParam("{\"nlist\":1024}")            // 索引参数（JSON 字符串）
                    .build();
            milvusServiceClient.createIndex(createIndexParam);

            // 加载到内存（搜索前必须先load）
            milvusServiceClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            log.info("Milvus collection索引创建+加载完成: {}", collectionName);

        } catch (Exception e) {
            log.error("创建Milvus collection失败: {}", e.getMessage());
            throw new BusinessException("Milvus初始化失败: " + e.getMessage());
        }
    }

    /**
     * 批量插入向量
     *
     * @param chunkIds   文本块ID列表（作为Milvus的主键）
     * @param documentId 所属文档ID
     * @param contents   文本内容列表
     * @param vectors    向量列表（和contents一一对应）
     */
    public void insertVectors(List<Long> chunkIds, Long documentId,
                              List<String> contents, List<float[]> vectors) {
        try {
            // 构建插入数据（Milvus用JSONObject表示一行数据，注意是FastJSON不是Gson）
            List<JSONObject> rows = new ArrayList<>();
            for (int i = 0; i < chunkIds.size(); i++) {
                JSONObject row = new JSONObject();
                row.put("id", chunkIds.get(i));
                row.put("document_id", documentId);
                row.put("content", contents.get(i));

                // 向量字段需要转成JSONArray
                JSONArray vectorArray = new JSONArray();
                for (float v : vectors.get(i)) {
                    vectorArray.add(v);
                }
                row.put("embedding", vectorArray);

                rows.add(row);
            }

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withRows(rows)
                    .build();

            milvusServiceClient.insert(insertParam);
            log.info("Milvus插入成功: {}条向量, documentId={}", chunkIds.size(), documentId);

        } catch (Exception e) {
            log.error("Milvus插入失败: {}", e.getMessage());
            throw new BusinessException("向量入库失败: " + e.getMessage());
        }
    }

    /**
     * 向量搜索（下周问答功能会用到）
     *
     * @param queryVector 查询向量（2048维）
     * @param topK        返回最相似的K条结果
     * @return 搜索结果列表
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        try {
            // ============================================================
            // TODO 5（⭐⭐ 难度）：构建搜索参数
            //
            // 需要用 SearchParam.newBuilder() 构建，需要设置：
            //   .withCollectionName(collectionName)    // 搜哪个表
            //   .withVectorFieldName("embedding")     // 搜哪个字段
            //   .withVectors(List.of(queryVector))    // 查询向量
            //   .withVectorValues(queryVector)        // 或者用这个
            //   .withTopK(topK)                       // 返回前K条
            //   .withOutFields(List.of("id", "content", "document_id"))  // 返回哪些字段
            //   .withMetricType(MetricType.COSINE)    // 余弦相似度
            //   .withParams("{\"nprobe\":10}")        // 搜索参数
            //
            // 提示：查询向量要用 List.of(new float[][]{queryVector}) 或
            //       .withVectors(List.of(queryVector))

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(queryVector))
                    .withTopK(topK)
                    .withOutFields(List.of("id", "content", "document_id"))
                    .withMetricType(MetricType.COSINE)
                    .withParams("{\"nprobe\":10}")
                    .build();


            R<SearchResults> response = milvusServiceClient.search(searchParam);
            SearchResultsWrapper wrapper = new SearchResultsWrapper(
                    response.getData().getResults());

            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResult sr = new SearchResult();
                sr.setChunkId(wrapper.getIDScore(0).get(i).getLongID());
                sr.setScore(wrapper.getIDScore(0).get(i).getScore());
                sr.setContent(wrapper.getFieldData("content", 0).get(i).toString());
                results.add(sr);
            }

            log.info("Milvus搜索完成: topK={}, 返回{}条结果", topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("Milvus搜索失败: {}", e.getMessage());
            throw new BusinessException("向量搜索失败: " + e.getMessage());
        }
    }

    /**
     * 搜索结果内部类
     */
    @lombok.Data
    public static class SearchResult {
        private Long chunkId;
        private float score;
        private String content;
    }
}