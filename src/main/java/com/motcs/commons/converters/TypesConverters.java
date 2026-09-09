package com.motcs.commons.converters;

import org.springframework.core.convert.converter.Converter;

import java.util.Collection;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public interface TypesConverters {

    Collection<Converter<?, ?>> getConverters();

}