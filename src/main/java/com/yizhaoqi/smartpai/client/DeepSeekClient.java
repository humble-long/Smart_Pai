package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.ToolDefinition;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.model}") String model,
            AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }

    /**
     * 流式响应（不带工具）
     */
    public void streamResponse(String userMessage,
            String context,
            List<Map<String, String>> history,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {
        streamResponseWithTools(userMessage, context, history, null, onChunk, null, onError);
    }

    /**
     * 流式响应（带工具支持）
     * 
     * @param userMessage 用户消息
     * @param context     上下文
     * @param history     历史消息
     * @param tools       工具定义列表
     * @param onChunk     文本块回调
     * @param onToolCall  工具调用回调
     * @param onError     错误回调
     */
    public void streamResponseWithTools(String userMessage,
            String context,
            List<Map<String, String>> history,
            List<ToolDefinition> tools,
            Consumer<String> onChunk,
            Consumer<Map<String, Object>> onToolCall,
            Consumer<Throwable> onError) {

        Map<String, Object> request = buildRequest(userMessage, context, history, tools);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, onChunk, onToolCall),
                        onError);
    }

    private Map<String, Object> buildRequest(String userMessage,
            String context,
            List<Map<String, String>> history) {
        return buildRequest(userMessage, context, history, null);
    }

    private Map<String, Object> buildRequest(String userMessage,
            String context,
            List<Map<String, String>> history,
            List<ToolDefinition> tools) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}，工具数：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0,
                tools != null ? tools.size() : 0);

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);

        // 添加工具定义
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            logger.info("添加了 {} 个工具定义", tools.size());
        }

        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessages(String userMessage,
            String context,
            List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
                "role", "system",
                "content", systemContent));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
                "role", "user",
                "content", userMessage));

        return messages;
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        processChunk(chunk, onChunk, null);
    }

    private void processChunk(String chunk, Consumer<String> onChunk, Consumer<Map<String, Object>> onToolCall) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            JsonNode deltaNode = node.path("choices").path(0).path("delta");

            // 检查是否有工具调用
            if (onToolCall != null && deltaNode.has("tool_calls")) {
                JsonNode toolCallsNode = deltaNode.get("tool_calls");
                if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                    JsonNode toolCallNode = toolCallsNode.get(0);

                    // 提取工具调用信息
                    String toolName = toolCallNode.path("function").path("name").asText("");
                    String argumentsStr = toolCallNode.path("function").path("arguments").asText("");

                    if (!toolName.isEmpty()) {
                        Map<String, Object> toolCall = new java.util.HashMap<>();
                        toolCall.put("name", toolName);

                        // 解析参数 JSON
                        if (!argumentsStr.isEmpty()) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> arguments = mapper.readValue(argumentsStr, Map.class);
                                toolCall.put("arguments", arguments);
                            } catch (Exception e) {
                                logger.warn("解析工具参数失败: {}", e.getMessage());
                                toolCall.put("arguments", new java.util.HashMap<>());
                            }
                        } else {
                            toolCall.put("arguments", new java.util.HashMap<>());
                        }

                        logger.info("检测到工具调用: {}", toolName);
                        onToolCall.accept(toolCall);
                        return;
                    }
                }
            }

            // 处理普通文本内容
            String content = deltaNode.path("content").asText("");
            if (!content.isEmpty() && onChunk != null) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
}