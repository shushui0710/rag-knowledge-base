package com.liushuwen.rag.rag;

import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回答质量评审实现（阶段4 ✅ 已实现：LLM 评判）
 *
 * 链路：生成回答 → LLM 评判（pass/reason JSON）→ 不合格则带意见重写（由编排层调用）。
 * 评审是旁路增强：调用失败/解析失败默认放行，绝不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CriticServiceImpl implements CriticService {

    private final LlmService llmService;

    @Override
    public Critique judge(String question, String answer, List<String> sources) {
        try {
            String srcSummary = sources == null ? "" : String.join("\n", sources);
            if (srcSummary.length() > 500) {
                srcSummary = srcSummary.substring(0, 500);
            }
            String user = "问题：" + question + "\n回答：" + answer
                    + "\n依据片段：" + srcSummary;
            String raw = llmService.chatWithSystem(
                    "你是回答质量评审。判断标准：①是否直接回答了问题 ②是否有知识库依据 ③是否简洁。"
                            + "只输出 JSON：{\"pass\": true/false, \"reason\": \"...\"}",
                    user, 0.2);

            // 模型可能输出多余文字，提取第一个 { 到最后一个 } 之间的内容
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Critique.pass();                    // 解析失败默认放行
            }
            JSONObject obj = JSONObject.parseObject(raw.substring(start, end + 1));
            boolean pass = obj.getBooleanValue("pass");
            return pass ? Critique.pass() : Critique.fail(obj.getString("reason"));
        } catch (Exception e) {
            // 评审挂了不能让主流程挂：默认放行
            log.warn("评审调用失败，默认放行: {}", e.getMessage());
            return Critique.pass();
        }
    }
}
