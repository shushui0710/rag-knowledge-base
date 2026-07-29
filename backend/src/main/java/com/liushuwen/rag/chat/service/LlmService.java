package com.liushuwen.rag.chat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liushuwen.rag.common.BusinessException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM大模型服务 - 调用DeepSeek API生成回答
 *
 * DeepSeek API是OpenAI兼容格式，调用流程和EmbeddingService调用智谱API几乎一样：
 * 1. 构建HTTP请求（Bearer Token认证 + JSON请求体）
 * 2. 发送POST请求
 * 3. 解析JSON响应，提取回答文本
 *
 * 面试考点：调用第三方AI API的标准流程（认证→请求→解析→异常处理）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    @Value("${llm.deepseek.api-key}")
    private String apiKey;

    @Value("${llm.deepseek.base-url}")
    private String baseUrl;

    @Value("${llm.deepseek.model}")
    private String model;

    @Value("${llm.deepseek.max-tokens}")
    private int maxTokens;

    @Value("${llm.deepseek.temperature}")
    private double temperature;

    // 由Spring容器注入（RestTemplateConfig中定义的@Bean + Spring Boot自动配置的ObjectMapper）
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 调用DeepSeek生成回答
     *
     * @param prompt 拼接好的完整提示词（包含上下文+用户问题）
     * @return 大模型生成的回答文本
     */
    public String chat(String prompt) {
        try {
            // 1. 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // ============================================================
            // TODO 1（⭐⭐ 难度）：构建DeepSeek API请求体
            //
            // DeepSeek API是OpenAI兼容格式，请求体结构：
            // {
            //   "model": "deepseek-chat",
            //   "messages": [
            //     {"role": "system", "content": "你是一个专业的知识库问答助手..."},
            //     {"role": "user", "content": "拼接好的prompt"}
            //   ],
            //   "max_tokens": 2048,
            //   "temperature": 0.7
            // }
            //
            // 提示：
            // - 用 LinkedHashMap 保证JSON字段顺序（你在EmbeddingService用过的技巧）
            // - messages 是一个 List<Map<String,String>>，包含2个元素：system和user
            // - system消息内容可以是简单的"你是一个专业的知识库问答助手"
            // - user消息内容就是传入的prompt参数
            // - 最后用 objectMapper.writeValueAsString(body) 转成JSON字符串
            //
            // 面试考点：OpenAI兼容API的请求体格式（model + messages + 参数）
            // ============================================================
            Map<String,Object> body=new LinkedHashMap<>();
            List<Map<String,String>> messages=new ArrayList<>();
            Map<String,String>systemMsg=new LinkedHashMap<>();
            systemMsg.put("role","system");
            systemMsg.put("content","你是一个专业的知识库问答助手。请根据参考资料回答用户问题。");
            messages.add(systemMsg);

            Map<String,String>userMsg=new LinkedHashMap<>();
            userMsg.put("role","user");
            userMsg.put("content",prompt);
            messages.add(userMsg);

            body.put("model",model);
            body.put("messages",messages);
            body.put("max_tokens",maxTokens);
            body.put("temperature",temperature);
            String requestBody=objectMapper.writeValueAsString(body);



            // 2. 发送HTTP请求
            String apiUrl = baseUrl + "/v1/chat/completions";
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);

            // ============================================================
            // TODO 2（⭐⭐ 难度）：解析响应，提取回答文本
            //
            // DeepSeek API响应结构：
            // {
            //   "id": "chatcmpl-xxx",
            //   "choices": [
            //     {
            //       "index": 0,
            //       "message": { "role": "assistant", "content": "大模型生成的回答" },
            //       "finish_reason": "stop"
            //     }
            //   ],
            //   "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
            // }
            //
            // 提示：用POJO绑定（你在EmbeddingService用过的优化方案）
            // - 定义 DeepSeekResponse 类（@JsonIgnoreProperties(ignoreUnknown = true)）
            // - 内部有 List<Choice> choices
            // - Choice 内部有 Message message
            // - Message 内部有 String content
            // - 最终取 choices.get(0).getMessage().getContent()
            //
            // 面试亮点：和EmbeddingService一样的POJO绑定，不手动遍历JsonNode
            // ============================================================
            DeepSeekResponse resp = objectMapper.readValue(response.getBody(), DeepSeekResponse.class);
            String answer = resp.getChoices().get(0).getMessage().getContent();



            log.info("DeepSeek生成完成, 回答长度: {}", answer.length());
            return answer;

        } catch (Exception e) {
            log.error("调用DeepSeek API失败: {}", e.getMessage());
            throw new BusinessException("大模型生成失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 在这里定义你的DTO类（参考EmbeddingService的EmbeddingResponse）
    // 记得加 @Data + @JsonIgnoreProperties(ignoreUnknown = true)
    // 需要三个类：DeepSeekResponse → Choice → Message
    // ============================================================
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeepSeekResponse {
        private List<Choice> choices;
        
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message message;
        
    }
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
    }



}
