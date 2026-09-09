package com.motcs.core.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AI 平台探测接口（公开）：
 * 前端页面加载时调用，获取当前后端启用的 AI 平台与可选的对话模型列表，
 * 用于动态渲染对话界面的模型下拉选择框，避免选择到当前平台不存在的模型。
 * <p>
 * 响应示例（baidu profile）：
 * <pre>
 * {
 *   "provider": "baidu",
 *   "name": "百度千帆",
 *   "models": ["deepseek-v3.2", "deepseek-v3.2-think", "deepseek-v4-flash-0731"],
 *   "defaultModel": "deepseek-v3.2-think"
 * }
 * </pre>
 * 平台标识由配置决定：application.yaml（默认 zhipu）/ application-baidu.yaml（baidu），
 * 均可用环境变量 AI_PROVIDER 覆盖。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@RestController
@RequestMapping("/ai/v1")
public class AiProviderController {

    @Value("${app.ai.provider:zhipu}")
    private String provider;

    @Value("${app.ai.provider-name:智谱 AI}")
    private String providerName;

    @Value("${app.ai.chat-models:glm-4-flash}")
    private String chatModels;

    @Value("${app.ai.default-chat-model:}")
    private String defaultChatModel;

    @GetMapping("/provider")
    public Mono<Map<String, Object>> provider() {
        List<String> models = Arrays.stream(chatModels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String defaultModel = defaultChatModel.isBlank() ?
                (models.isEmpty() ? "" : models.getFirst()) : defaultChatModel.trim();
        return Mono.just(Map.of("provider", provider, "name", providerName,
                "models", models, "defaultModel", defaultModel));
    }

}
