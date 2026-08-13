package com.liushuwen.rag.chat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liushuwen.rag.agent.Tool;
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
import java.util.stream.Collectors;

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
        /** DeepSeek 返回的 JSON 字段名是 tool_calls，必须显式映射（否则 Jackson 匹配不到，一直为 null） */
        @com.fasterxml.jackson.annotation.JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
        /** ⚠️ deepseek-v4-flash 思考模式的思考内容：回填 assistant 消息时必须原样带上，
         *    否则报 "The `reasoning_content` in the thinking mode must be passed back to the API" */
        @com.fasterxml.jackson.annotation.JsonProperty("reasoning_content")
        private String reasoningContent;
    }

    // ============================================================
    // 阶段3新增：Function Calling 支持（Agentic RAG）
    // ============================================================

    /**
     * 工具调用（Function Calling）结果 DTO
     * DeepSeek 返回的 message.tool_calls 结构：
     * [{"id": "call_xxx", "function": {"name": "query_document_stats", "arguments": "{...}"}}]
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        private String id;
        /** DeepSeek 返回的 tool_calls 带 index，回填时需保留（原样序列化依赖此字段） */
        private Integer index;
        private Function function;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Function {
            private String name;
            /** arguments 是 JSON 字符串，使用时需 parseObject */
            private String arguments;
        }
    }

    /**
     * 带工具定义的对话调用（阶段3 ✅ 已实现：Function Calling）
     *
     * @param messages 对话历史（List of Map：{"role":..,"content":..}，ReAct 循环逐轮追加）
     * @param tools    可用工具列表（转成 OpenAI tools 参数）
     * @return LlmResponse（ANSWER=最终回答 / TOOL_CALL=需要调用工具）
     */
    public LlmResponse chatWithTools(List<Map<String, Object>> messages, List<Tool> tools) {
        try {
            // 1. 请求头（与 chat() 一致）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 2. 请求体：messages + tools 定义
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);                        // deepseek-v4-flash
            body.put("messages", messages);
            body.put("tools", tools.stream().map(t -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.name());
                fn.put("description", t.description());
                // ⚠️ readTree 抛受检异常 JsonProcessingException——lambda 内无法被外层 try-catch
                //    捕获，必须就地转成 RuntimeException（外层 catch(Exception) 统一处理）
                try {
                    fn.put("parameters", objectMapper.readTree(t.parametersJsonSchema()));
                } catch (Exception ex) {
                    throw new RuntimeException("工具参数 schema 解析失败: " + t.name(), ex);
                }
                f.put("function", fn);
                return f;
            }).collect(Collectors.toList()));
            body.put("tool_choice", "auto");
            body.put("temperature", 0.3);

            // 3. 发送请求
            String apiUrl = baseUrl + "/v1/chat/completions";
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);
            DeepSeekResponse resp = objectMapper.readValue(response.getBody(), DeepSeekResponse.class);

            // 4. 解析（⚠️ choices 是数组，先 get(0) 再取 message）
            Message msg = resp.getChoices().get(0).getMessage();
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // 把模型返回的 assistant 消息【完整原样】保存（含 role/content/reasoning_content/tool_calls），
                // ReAct 循环后续要原样回填：⚠️ 思考型模型缺 reasoning_content 必报错；
                // tool_calls 缺 index/type 也会报 "missing field type"（2026-08 验收实测）
                // ⚠️ 必须从原始 JSON 取（JsonNode→Map 保持 JSON 字段名 reasoning_content/tool_calls；
                //    convertValue(POJO→Map) 会退化成 Java 字段名 reasoningContent/toolCalls，API 不认）
                com.fasterxml.jackson.databind.JsonNode rawNode = objectMapper.readTree(response.getBody())
                        .path("choices").get(0).path("message");
                Map<String, Object> raw = objectMapper.convertValue(rawNode, Map.class);
                raw.put("type", "message");   // ⚠️ deepseek-v4-flash 要求每条消息带 type 字段
                return LlmResponse.toolCalls(msg.getToolCalls(), raw);
            }
            return LlmResponse.answer(msg.getContent() == null ? "" : msg.getContent());
        } catch (Exception e) {
            log.error("Function Calling 调用失败: {}", e.getMessage(), e);
            throw new BusinessException("大模型生成失败: " + e.getMessage());
        }
    }

    /**
     * Function Calling 响应（ANSWER / TOOL_CALL 两态）
     *
     * - ANSWER 态：content 有值，toolCalls 为空 → AgentExecutor 直接返回
     * - TOOL_CALL 态：toolCalls 有值，content 可为空 → AgentExecutor 执行工具
     * - rawAssistantMsg：模型返回的 assistant 消息原样（ReAct 回填对话历史用）
     */
    @Data
    public static class LlmResponse {
        private String content;                       // ANSWER 态：最终回答
        private List<ToolCall> toolCalls;             // TOOL_CALL 态：要调用的工具
        private Map<String, Object> rawAssistantMsg;  // assistant 消息原样（回填用）

        public boolean isAnswer() {
            return toolCalls == null || toolCalls.isEmpty();
        }

        public static LlmResponse answer(String content) {
            LlmResponse r = new LlmResponse();
            r.setContent(content);
            return r;
        }

        public static LlmResponse toolCalls(List<ToolCall> calls, Map<String, Object> raw) {
            LlmResponse r = new LlmResponse();
            r.setToolCalls(calls);
            r.setRawAssistantMsg(raw);
            return r;
        }
    }

    /**
     * 带 system 指令的对话生成（阶段2/3/4 共用前置方法）
     *
     * 用途：TODO 2-3 查询改写 / TODO 3-4 意图路由 / TODO 4-3 反思评审——
     * 都需要"自定义 system + 可控 temperature"的 LLM 调用。
     * 与 chat(prompt) 的区别：chat() 的 system 固定为知识库问答助手，且 temperature 走配置。
     *
     * @param system      system 指令（角色设定/输出格式约束）
     * @param user        用户内容
     * @param temperature 温度（改写/路由用 0.1~0.2，生成用 0.7）
     * @return 大模型生成的文本
     */
    public String chatWithSystem(String system, String user, double temperature) {
        try {
            // 1. 请求头（与 chat() 一致）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 2. 请求体：messages = [system, user]，temperature 参数化
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);                       // yml 已改 deepseek-v4-flash
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", system));
            msgs.add(Map.of("role", "user", "content", user));
            body.put("messages", msgs);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            // 3. 发送 + 解析（复用 chat() 的 POJO 绑定）
            String apiUrl = baseUrl + "/v1/chat/completions";
            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);
            DeepSeekResponse resp = objectMapper.readValue(response.getBody(), DeepSeekResponse.class);
            return resp.getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("chatWithSystem 调用失败: {}", e.getMessage(), e);
            throw new BusinessException("大模型生成失败: " + e.getMessage());
        }
    }

}
