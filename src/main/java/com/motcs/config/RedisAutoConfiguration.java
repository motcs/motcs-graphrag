package com.motcs.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 自动配置（Reactive）
 * 适配当前 Spring Boot 4.x + WebFlux 环境
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-20 星期六
 */
@Configuration
public class RedisAutoConfiguration {

    /**
     * ReactiveRedisTemplate：key 用 String 序列化，value 用 JSON 序列化
     */
    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        RedisSerializer<Object> valueSerializer = RedisSerializer.json();

        RedisSerializationContext<String, Object> serializationContext = RedisSerializationContext
                .<String, Object>newSerializationContext(keySerializer).value(valueSerializer)
                .hashKey(keySerializer).hashValue(valueSerializer).build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

}
