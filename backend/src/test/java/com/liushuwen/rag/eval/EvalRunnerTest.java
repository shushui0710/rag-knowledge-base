package com.liushuwen.rag.eval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 检索评估集成测试：在 Spring 容器中注入各 Service，跑外置用例集计算 Top5 命中率。
 * 【设计要点】@SpringBootTest 集成测试：真实上下文里验证"改写/重排/混合检索"对召回率的真实影响，而非单测桩
 * 【常见问题】为什么要先评估再优化？——没有基线指标，任何检索调参都是玄学；本集建议 20 题、用例外置 docs/eval/questions.json，可进 CI 做回归
 */
@SpringBootTest
class EvalRunnerTest {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    @Test
    void runEval() {
        List<EvalCase> cases = loadCases();
        if (cases.isEmpty()) {
            System.out.println("[WARN] 未找到用例文件 docs/eval/questions.json，跳过评估");
            return;
        }
        int hit = 0;
        for (EvalCase c : cases) {
            // 功能：单条用例失败不中断整体评估，catch 记 miss 继续｜要点：评估健壮性（一条坏数据不影响全量统计）
            try {
                if (c.getQuestion() == null || c.getExpectedKeyword() == null) {
                    System.out.println("[SKIP] 用例数据不完整: " + c.getQuestion());
                    continue;
                }
                float[] vec = embeddingService.embedSingle(c.getQuestion());
                List<MilvusService.SearchResult> top5 = milvusService.search(vec, 5);
                boolean ok = top5.stream().anyMatch(r ->
                        r.getContent() != null && r.getContent().contains(c.getExpectedKeyword()));
                if (ok) {
                    hit++;
                }
                System.out.println((ok ? "[HIT]  " : "[MISS] ") + c.getQuestion());
            } catch (Exception e) {
                System.out.println("[ERROR] " + c.getQuestion() + " -> " + e.getMessage());
            }
        }
        System.out.println("======================");
        System.out.println("Top5 命中率: " + hit + "/" + cases.size());
    }

    // 功能：读取 docs/eval/questions.json 并映射为用例列表｜要点：评估数据与代码解耦（外置便于维护与 CI）
    private List<EvalCase> loadCases() {
        try {
            File f = new File("../docs/eval/questions.json");
            if (!f.exists()) {
                return List.of();
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return JSON.parseObject(json, new TypeReference<List<EvalCase>>() {
            });
        } catch (Exception e) {
            System.out.println("[WARN] 读取用例失败: " + e.getMessage());
            return List.of();
        }
    }
}
