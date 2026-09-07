package com.motcs.commons.utils;

import java.util.Map;
import java.util.StringJoiner;

public record ParameterSql(StringJoiner sql, Map<String, Object> params) {

    public String whereSql() {
        if (this.sql.length() > 0) {
            return " where " + this.sql;
        }
        return "";
    }

    public static ParameterSql of(StringJoiner sql, Map<String, Object> params) {
        return new ParameterSql(sql, params);
    }

}
