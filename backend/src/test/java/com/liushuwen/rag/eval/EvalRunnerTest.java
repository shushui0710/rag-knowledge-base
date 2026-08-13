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
 * 检索评估测试（阶段5 ✅ 已实现）
 *
 * 指标：Top5 命中率 = 期望关键词出现在 Top5 片段中的用例数 / 总用例数。
 * 每次改动检索策略（混合检索/改写/重排）后跑一遍，对比命中率。
 *
 * 运行：mvn.cmd test -Dtest=EvalRunnerTest  （或 IDE 里直接跑）
 * 用例文件：docs/eval/questions.json（按需增删，建议 20 题）
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
            // ⚠️ 单条用例失败不中断评估：catch 记 miss 继续
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

    /** 读取 docs/eval/questions.json（相对 backend 模块根目录） */
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
