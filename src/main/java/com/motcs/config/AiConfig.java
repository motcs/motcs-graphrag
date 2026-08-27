package com.motcs.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${app.zhipu.api-key}")
    private String zhiPuApiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${app.zhipu.embedding.model}")
    private String model;

    /**
     * ChatClient：聊天模型由 OpenAiChatAutoConfiguration 自动配置
     * （spring.ai.openai.base-url 指向智谱，model=glm-4-flash）
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * EmbeddingModel：使用智谱 embedding-3 模型（2048维），兼容OpenAI接口格式
     * 手动配置是因为排除了 OpenAiEmbeddingAutoConfiguration，避免与聊天模型共用配置冲突
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl).apiKey(zhiPuApiKey).model(model).build();
        return OpenAiEmbeddingModel.builder().options(embeddingOptions).build();
    }

}
