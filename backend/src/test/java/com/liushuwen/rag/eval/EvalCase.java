package com.liushuwen.rag.eval;

import lombok.Data;

/**
 * 检索评估用例（阶段5 ✅）
 *
 * expectedKeyword：期望出现在 Top5 检索片段中的关键词——
 * 命中 = Top5 里至少一个片段包含该词（简单可用的召回指标）。
 */
@Data
public class EvalCase {
    /** 测试问题 */
    private String question;
    /** 期望出现在检索结果片段中的关键词 */
    private String expectedKeyword;
}
