package com.motcs.commons.converters;

import org.springframework.core.convert.converter.Converter;

import java.util.Collection;

public interface TypesConverters {

    Collection<Converter<?, ?>> getConverters();

}