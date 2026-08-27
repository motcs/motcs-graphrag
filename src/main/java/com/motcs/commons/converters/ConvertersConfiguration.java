package com.motcs.commons.converters;

import org.springframework.beans.factory.config.CustomEditorConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-08-25 星期二
 */
@Configuration(proxyBeanMethods = false)
public class ConvertersConfiguration extends CustomEditorConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public TypesConverters typesConverters() {
        return new CustomTypesConverters();
    }

}