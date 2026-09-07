package com.motcs.config;

import com.motcs.commons.converters.TypesConverters;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * WebFlux 全局配置。
 *
 * <p>WebFlux 默认不注册 Spring Data 的分页解析器，导致接口方法使用标准
 * {@code Pageable} 参数（page/size/sort）时抛出
 * "No primary or single unique constructor found for interface Pageable"。
 * 这里手动注册 {@link ReactivePageableHandlerMethodArgumentResolver}，
 * 使 {@code Pageable}/{@code Sort} 能按 Spring Data 标准方式解析。
 */
@Configuration
public class WebFluxConfiguration implements WebFluxConfigurer {

    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(new ReactivePageableHandlerMethodArgumentResolver());
    }

    @Bean
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(LocalDateTime.class,
                    new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));
            module.addSerializer(LocalDate.class,
                    new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));
            module.addDeserializer(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));
            module.addDeserializer(LocalDate.class,
                    new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));
            builder.addModule(module);
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableR2dbcAuditing
    @ConditionalOnClass(name = "org.springframework.data.r2dbc.core.R2dbcEntityTemplate")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public static class R2dbcConfiguration extends AbstractR2dbcConfiguration {

        private final List<Converter<?, ?>> converters;

        public R2dbcConfiguration(List<Converter<?, ?>> converters, TypesConverters typesConverters) {
            this.converters = converters;
            this.converters.addAll(typesConverters.getConverters());
        }

        @Override
        @NonNull
        public ConnectionFactory connectionFactory() {
            return ConnectionFactories.get("r2dbc:..");
        }

        @Override
        @NonNull
        public List<Object> getCustomConverters() {
            return new ArrayList<>(this.converters);
        }
    }

}
