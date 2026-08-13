package com.liushuwen.rag.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估工具（阶段5-生产化：可衡量）
 *
 * 用途：建一个"检索测试集"，每次改动检索策略后跑一遍，对比命中率。
 * 骨架说明：提供数据结构 + 主流程占位；真实运行需接入 Spring 环境
 * （后续可改成 @SpringBootTest 测试类，或由 Controller 触发）。
 *
 * 面试考点：
 * - 没有评估集，检索优化就是玄学——这是工程化的分水岭
 * - 指标：Top5 命中率（检索是否召回期望文档）
 */
public class EvalRunner {

    /** 单条测试用例 */
    @Data
    public static class EvalCase {
        private String question;      // 问题
        private Long expectedDocId;   // 期望命中的文档ID
    }

    /**
     * 主流程（TODO 5-1 ⭐⭐）
     *
     * 伪代码：
     *   List<EvalCase> cases = loadCases("docs/eval/questions.json");
     *   int hit = 0;
     *   for (EvalCase c : cases) {
     *       // 1. 向量化问题
     *       // 2. 检索 Top5（hybridSearch 或 search）
     *       // 3. 判断 Top5 中是否包含期望命中的文档
     *       if (top5.anyMatch(h -> h.getChunkId().equals(c.getExpectedDocId()))) hit++;
     *   }
     *   System.out.println("Top5 命中率: " + hit + "/" + cases.size());
     *
     * 注意：SearchResult 目前只有 chunkId（Milvus 主键），没有 documentId。
     *       若希望按"文档"维度判断命中，二选一：
     *       a) 给 SearchResult 增加 documentId 字段（search 的 outFields 已含 "document_id"，
     *          在 MilvusService 解析处顺手取出即可）；
     *       b) 用 chunkId 反查 document_chunk 表得到所属文档 ID。
     *
     * 说明：接入方式——在测试目录建 @SpringBootTest 类注入各 Service，
     *       或用临时 main + SpringApplication.run 获取上下文。
     */
    public static void main(String[] args) {
        // TODO 5-1: 填充评估主流程
        //
        // 【标准答案】完整实现（推荐用 @SpringBootTest 跑，能注入 Service）
        //
        // 前置：给 MilvusService.SearchResult 增加 documentId 字段：
        //   // 在 search() 解析处取出 document_id：
        //   //   sr.setDocumentId(wrapper.getFieldData("document_id", 0).get(i).getLongId());
        //   private Long documentId;   // 加字段 + Lombok @Data 自动生成 getter
        //
        // 测试方法（新建 src/test/java/.../EvalRunnerTest.java）：
        //   @SpringBootTest
        //   class EvalRunnerTest {
        //       @Autowired EmbeddingService embeddingService;
        //       @Autowired MilvusService milvusService;
        //
        //       @Test
        //       void runEval() {
        //           List<EvalCase> cases = loadCases();      // 从 docs/eval/questions.json 读
        //           int hit = 0;
        //           for (EvalCase c : cases) {
        //               // ⚠️ 单条用例失败不中断整个评估：catch 记 miss 继续跑
        //               try {
        //                   if (c.getQuestion() == null || c.getExpectedDocId() == null) {
        //                       System.out.println("[SKIP] 用例数据不完整: " + c);
        //                       continue;
        //                   }
        //                   List<float[]> vecs = embeddingService.embed(List.of(c.getQuestion()));
        //                   if (vecs == null || vecs.isEmpty()) continue;
        //                   List<MilvusService.SearchResult> top5 = milvusService.search(vecs.get(0), 5);
        //                   boolean ok = top5.stream().anyMatch(h -> c.getExpectedDocId().equals(h.getDocumentId()));
        //                   if (ok) hit++;
        //                   System.out.println((ok ? "[HIT] " : "[MISS] ") + c.getQuestion());
        //               } catch (Exception e) {
        //                   System.out.println("[ERROR] " + c.getQuestion() + " → " + e.getMessage());
        //               }
        //           }
        //           System.out.println("Top5 命中率: " + hit + "/" + cases.size());
        //       }
        //   }
        //
        // loadCases() 参考：用 fastjson 读 JSON 数组映射到 EvalCase
        List<EvalCase> cases = new ArrayList<>();
        System.out.println("评估用例数: " + cases.size() + "（待填充 docs/eval/questions.json）");
        System.out.println("Top5 命中率: 待实现");
    }
}
