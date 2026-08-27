package com.motcs.commons.converters;

import com.motcs.commons.ContextUtil;
import lombok.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.util.ObjectUtils;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-08-25 星期二
 */
public class CustomTypesConverters implements TypesConverters {

    @Override
    public Collection<Converter<?, ?>> getConverters() {
        List<Converter<?, ?>> converters = new ArrayList<>();
        converters.add(JsonToNodeWriteConverter.INSTANCE);
        converters.add(JsonToNodeReadConverter.INSTANCE);
        return converters;
    }

    @WritingConverter
    private enum JsonToNodeWriteConverter implements Converter<JsonNode, String> {
        /**
         * default INSTANCE
         */
        INSTANCE;

        @Override
        public String convert(@NonNull JsonNode source) {
            if (ObjectUtils.isEmpty(source)) {
                return "{}";
            }
            String string = source.toString();
            if (Objects.equals(string, "null")) {
                return "{}";
            }
            return string;
        }
    }

    @ReadingConverter
    private enum JsonToNodeReadConverter implements Converter<String, JsonNode> {
        /**
         * default INSTANCE
         */
        INSTANCE;

        @Override
        public JsonNode convert(@NonNull String source) {
            return ContextUtil.OBJECT_MAPPER.readTree(source);
        }
    }

}