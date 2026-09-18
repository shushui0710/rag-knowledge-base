package com.liushuwen.rag.chat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liushuwen.rag.agent.AgentMetrics;
import com.liushuwen.rag.agent.LlmCircuitBreaker;
import com.liushuwen.rag.agent.Tool;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.LlmUnavailableException;
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
 * LLM 调用服务（封装 DeepSeek，OpenAI 兼容 /chat/completions 协议）。
 * 在 Agentic RAG 链路中提供三种能力：纯问答 chat、带 Function Calling 的 chatWithTools、可定制 system 的 chatWithSystem。
 * 【设计要点】第三方 AI API 标准调用范式（Bearer 认证 → 构造 messages → RestTemplate 发请求 → POJO/JsonNode 解析 → 异常降级），以及思考模型 messages 协议的字段兼容
 * 【设计要点】本类是**全站 LLM 调用的唯一出口**（3 个方法三条形态：纯问答 / Function Calling / 自定义 system），
 * 因此两件"跨界关注点"都收口在这里：①指标埋点（llmCalls）②熔断（LlmCircuitBreaker）。
 * 出口唯一 ⇒ 主 RAG 链、单 Agent、多 Agent 编排（含意图路由/查询改写/反思评审）一律被覆盖，既不漏记也不重记。
 * 【缺陷修复·熔断挂在执行器层】修复前熔断只被 AgentExecutor 使用 ⇒ 只有 /api/agent/ask 有保护，
 * 而用户真正在用的主问答链路 /api/chat/ask 完全没有熔断。根因是"挂错了层"：熔断的目的是保护下游 LLM API，
 * 挂在某个执行器上，天然只能覆盖"路过该执行器"的链路；挂到唯一出口才与调用方无关地覆盖全站。
 * 这与指标埋点从"散落各调用点"收口到"唯一出口"是同一个思路（G-08/G-09 的延续）。
 * 【常见问题】为什么用 LinkedHashMap 构造请求体？——保证 JSON 字段顺序与协议一致（模型参数顺序敏感场景）；为什么 Function Calling 的 assistant 消息必须原样回填？——思维链/工具调用原始字段（reasoning_content/tool_calls）缺失会被 API 拒绝
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

    // 功能：由 Spring 容器注入 RestTemplate 与 ObjectMapper｜要点：@Bean 装配 + Spring Boot 自动配置统一 Jackson（日期/命名策略一致）
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    /**
     * LLM 调用计数：本类的 3 个方法是全站唯一打 /v1/chat/completions 的出口，
     * 因此计数放在这里能一次性覆盖主 RAG 链、单 Agent ReAct、多 Agent 编排（含意图路由/改写/评审）所有链路，
     * 既不漏记也不重记。详见 AgentMetrics 的「唯一写者口径」。
     */
    private final AgentMetrics metrics;

    /**
     * LLM 熔断器：与指标埋点同挂"唯一出口"。
     * 三个方法在发起真实 HTTP 前统一 tryAcquire，成功后 onSuccess、失败后 onFailure。
     */
    private final LlmCircuitBreaker circuitBreaker;

    /**
     * 唯一出口的底层实现：熔断判断 → 发 POST /v1/chat/completions → 成功清零计数并记一次调用 → 返回原始响应体。
     * 【设计要点】把"熔断 + 认证 + 发请求 + 计数"压成一个出口，上层三个方法只负责"拼请求体 / 解响应体"，
     * 跨界关注点全部落在这里 ⇒ 新增一条调用链时天然被熔断与指标覆盖，无需在调用处补埋点。
     * 【常见问题】熔断打开时为什么抛异常而不是返回空串？——返回空串会被上层当成"模型给了空回答"静默吞掉，
     * 用户拿到空内容；抛专属异常才能让每条链路按自己的语义降级
     * （主问答给兜底文案、路由回落 DOCUMENT、改写退回原句、评审放行、ReAct 返回降级提示）。
     *
     * @param body 已拼好的 OpenAI 兼容请求体
     * @return 原始响应体 JSON 字符串
     */
    private String postChatCompletions(Map<String, Object> body) throws JsonProcessingException {
        // 功能：熔断前置判断——打开期间直接抛专属异常，不发请求｜要点：熔断点必须在"唯一出口"，才能覆盖全站链路
        if (!circuitBreaker.tryAcquire()) {
            throw new LlmUnavailableException();
        }
        // 功能：构造请求头（JSON + Bearer Token 认证）｜要点：HttpHeaders.setBearerAuth 注入 API Key，Content-Type 声明 application/json
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 功能：向 /v1/chat/completions 发 POST 请求｜要点：RestTemplate.exchange 同步调用，HttpEntity 封装请求体+头，String.class 收原始响应
        String apiUrl = baseUrl + "/v1/chat/completions";
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, String.class);

        // 功能：调用成功 → 清零熔断失败计数 + 记一次 LLM 调用｜要点：两个"全站唯一写者"都收口在出口处，
        // 只统计"真的发出并拿到响应"的调用；失败时由上层 catch 统一 onFailure 并转 BusinessException
        circuitBreaker.onSuccess();
        metrics.recordLlmCall();
        return response.getBody();
    }

    /**
     * 纯问答调用：构造 system+user 双消息的 OpenAI 兼容请求，调 DeepSeek 取回答文本。
     * 【设计要点】OpenAI 兼容协议形态（model + messages + max_tokens/temperature），RestTemplate 同步调用与 POJO 绑定解析
     * 【常见问题】为什么 messages 用 List<Map> 而非强类型？——协议字段少且固定，Map 构造最轻；异常如何降级？——catch 后转 BusinessException，不把底层错误暴露给前端
     */
    public String chat(String prompt) {
        try {
            // 功能：构造 OpenAI 兼容请求体（model + messages[system,user] + max_tokens/temperature），用 LinkedHashMap 保序后写 JSON｜要点：LinkedHashMap 保证字段顺序与协议一致；messages 用 Map 列表贴合协议、不引入多余 POJO
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

            // 功能：交唯一出口发请求（熔断前置 + Bearer 认证 + 响应体返回 + 计数）｜要点：三条形态共用同一出口，熔断与指标才能"一处收口、全站覆盖"
            String raw = postChatCompletions(body);

            // 功能：把响应体反序列化为 DeepSeekResponse（@JsonIgnoreProperties 忽略未知字段），取 choices[0].message.content｜要点：POJO 绑定比手动遍历 JsonNode 更稳健，字段缺失由 Jackson 容错；choices 是数组须先 get(0)
            DeepSeekResponse resp = objectMapper.readValue(raw, DeepSeekResponse.class);
            String answer = resp.getChoices().get(0).getMessage().getContent();


            log.info("DeepSeek生成完成, 回答长度: {}", answer.length());
            return answer;

        } catch (LlmUnavailableException e) {
            // 熔断打开：本次压根没发出请求，不属于"调用失败"，不能污染失败计数
            throw e;
        } catch (Exception e) {
            circuitBreaker.onFailure();
            log.error("调用DeepSeek API失败: {}", e.getMessage());
            throw new BusinessException("大模型生成失败: " + e.getMessage());
        }
    }

    // 响应体 POJO：DeepSeekResponse → Choice → Message，统一加 @Data + @JsonIgnoreProperties(ignoreUnknown=true) 容错未知字段
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

    // Function Calling（Agentic RAG 工具调用）支持：模型可在回答中返回要执行的工具调用

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
     * 带 Function Calling 的对话调用：把工具定义注入 tools 参数，驱动 ReAct 循环逐轮决策（ANSWER 或 TOOL_CALL）。
     * 【设计要点】OpenAI Function Calling 消息协议：tools 描述工具签名、tool_choice=auto 让模型自选、返回带 tool_calls 的 assistant 消息
     * 【常见问题】assistant 消息为何必须原样回填？——思考模型的 reasoning_content / tool_calls 原始字段缺失会被 API 拒绝；为什么用 readTree 透传而不用 Java 对象映射？——协议字段非固定，readTree 保序保字段最稳
     */
    public LlmResponse chatWithTools(List<Map<String, Object>> messages, List<Tool> tools) {
        try {
            // 功能：构造请求体——messages + tools（type=function + name/description/parameters 的 JSON Schema）｜要点：用 LinkedHashMap 逐字段拼装工具定义，parameters 由 readTree 解析 schema 保结构
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);                        // deepseek-v4-flash
            body.put("messages", messages);
            body.put("tools", tools.stream().map(t -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", t.name());
                fn.put("description", t.description());
                // 功能：解析工具参数 JSON Schema 放入请求体｜要点：readTree 抛受检异常，lambda 内无法被外层 try 捕获，须就地转 RuntimeException 交统一 catch 处理
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

            // 功能：交唯一出口发请求（熔断前置 + 认证 + 计数），返回原始响应体｜要点：ReAct 每轮决策都算一次真实调用，
            // 计数只在出口处发生，AgentExecutor 不再自行计数——修复前两处都记，导致整体翻倍
            String rawBody = postChatCompletions(body);
            DeepSeekResponse resp = objectMapper.readValue(rawBody, DeepSeekResponse.class);

            // 功能：取 choices[0].message 判断是否有 tool_calls｜要点：choices 是数组须先 get(0)；有 tool_calls 进入工具调用分支，否则直接返回回答
            Message msg = resp.getChoices().get(0).getMessage();
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // 功能：把模型返回的 assistant 消息完整原样保存（含 role/content/reasoning_content/tool_calls），供 ReAct 循环回填｜要点：思考模型缺 reasoning_content 必报错、tool_calls 缺 index/type 报 "missing field type"，须从原始 JSON 取
                // 常见问题：为什么用 readTree→Map 而非 convertValue(POJO→Map)？→ convertValue 会把字段名退化成 Java 的 reasoningContent/toolCalls，API 不认，故必须用 JsonNode 保留原始 JSON 字段名
                com.fasterxml.jackson.databind.JsonNode rawNode = objectMapper.readTree(rawBody)
                        .path("choices").get(0).path("message");
                Map<String, Object> raw = objectMapper.convertValue(rawNode, Map.class);
                raw.put("type", "message");   // 思考模型要求每条消息带 type 字段
                return LlmResponse.toolCalls(msg.getToolCalls(), raw);
            }
            return LlmResponse.answer(msg.getContent() == null ? "" : msg.getContent());
        } catch (LlmUnavailableException e) {
            // 熔断打开：本模块未发出请求，不计失败
            throw e;
        } catch (Exception e) {
            circuitBreaker.onFailure();
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
     * 可定制 system + temperature 的对话生成，供查询改写/意图路由/反思评审等子任务复用。
     * 与 chat(prompt) 的区别：chat() 的 system 固定为知识库问答助手、temperature 走配置；本方法二者均可控。
     * 【设计要点】同一 LLM 封装按"系统提示 + 温度"参数化复用，避免每类子任务重复造 HTTP 调用
     * 【常见问题】为什么改写/路由用低温度（0.1~0.2）而生成用 0.7？——确定性任务要稳定输出、创意生成才要高随机
     */
    public String chatWithSystem(String system, String user, double temperature) {
        try {
            // 功能：构造请求体 messages=[system,user]，temperature 参数化（改写/路由用低值）｜要点：用 Map.of 快速拼装 system/user 双消息
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);                       // 当前模型 deepseek-v4-flash
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", system));
            msgs.add(Map.of("role", "user", "content", user));
            body.put("messages", msgs);
            body.put("max_tokens", maxTokens);
            body.put("temperature", temperature);

            // 功能：交唯一出口发请求并复用 POJO 绑定取 choices[0].message.content｜要点：查询改写/意图路由/反思评审这些"子任务"
            // 同样消耗 LLM 额度、同样需要熔断保护，走同一出口后三者自动被覆盖
            String raw = postChatCompletions(body);
            DeepSeekResponse resp = objectMapper.readValue(raw, DeepSeekResponse.class);
            return resp.getChoices().get(0).getMessage().getContent();
        } catch (LlmUnavailableException e) {
            // 熔断打开：本模块未发出请求，不计失败；由调用方按各自语义降级
            throw e;
        } catch (Exception e) {
            circuitBreaker.onFailure();
            log.error("chatWithSystem 调用失败: {}", e.getMessage(), e);
            throw new BusinessException("大模型生成失败: " + e.getMessage());
        }
    }

}
