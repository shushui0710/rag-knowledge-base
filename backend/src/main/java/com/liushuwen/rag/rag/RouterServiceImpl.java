package com.liushuwen.rag.rag;

import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 意图路由实现（阶段3 ✅ 已实现：LLM 分类）
 *
 * 链路：LLM 判断用户问题需要的能力（DOCUMENT/STATS/HYBRID），
 * 失败默认回落 DOCUMENT（宁可多检索，别让问题没人答）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouterServiceImpl implements RouterService {

    private final LlmService llmService;

    @Override
    public Route route(String question) {
        try {
            String out = llmService.chatWithSystem(
                    "你是意图分类器。判断用户问题需要的能力："
                            + "DOCUMENT=检索知识库文档内容（问题问的是文档里的知识点）；"
                            + "STATS=查询文档统计/列表/数量/分类（如'有多少文档''列出文档''有哪些文档''文档统计'）；"
                            + "HYBRID=两者都要（既问数量又问内容）。只输出一个词，不要解释。",
                    question, 0.1);
            if (out.contains("STATS") && out.contains("DOCUMENT")) {
                return Route.HYBRID;
            }
            if (out.contains("STATS")) {
                return Route.STATS;
            }
            return Route.DOCUMENT;
        } catch (Exception e) {
            // 路由失败默认回落 DOCUMENT，保证可用性
            log.warn("路由调用失败，默认回落 DOCUMENT: {}", e.getMessage());
            return Route.DOCUMENT;
        }
    }
}
