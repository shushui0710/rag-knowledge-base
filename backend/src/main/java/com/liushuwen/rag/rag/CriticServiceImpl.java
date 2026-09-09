package com.liushuwen.rag.rag;

import com.alibaba.fastjson.JSONObject;
import com.liushuwen.rag.chat.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回答质量评审实现：调 LLM 输出 pass/reason 的 JSON 评判，解析失败或异常则默认放行。
 * 【设计要点】LLM-as-Judge 容错：评委只是增强信号，解析失败/调用异常都放行，绝不阻塞主流程
 * 【常见问题】为什么评审失败要放行而非拦截？——评委本身不可靠，错误拦截会让正确答案被丢弃，宁可放行；常见问题：重写次数在哪控制？→ 由编排层按上限(本设计 1 次)循环调用本 judge
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

            // 功能：从模型可能夹带的冗余文字中提取纯 JSON（首个 { 到最后一个 }）｜要点：LLM 输出鲁棒解析
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return Critique.pass();                    // 解析失败默认放行
            }
            JSONObject obj = JSONObject.parseObject(raw.substring(start, end + 1));
            boolean pass = obj.getBooleanValue("pass");
            return pass ? Critique.pass() : Critique.fail(obj.getString("reason"));
        } catch (Exception e) {
            // 功能：评审异常时默认放行｜要点：旁路增强 fail-open（评委不可靠，宁错放不误拦）
            log.warn("评审调用失败，默认放行: {}", e.getMessage());
            return Critique.pass();
        }
    }
}
