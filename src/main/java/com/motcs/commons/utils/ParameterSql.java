package com.motcs.commons.utils;

import java.util.Map;
import java.util.StringJoiner;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public record ParameterSql(StringJoiner sql, Map<String, Object> params) {

    public static ParameterSql of(StringJoiner sql, Map<String, Object> params) {
        return new ParameterSql(sql, params);
    }

    public String whereSql() {
        if (this.sql.length() > 0) {
            return " where " + this.sql;
        }
        return "";
    }

}
