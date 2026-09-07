package com.motcs.commons;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.base.CaseFormat;
import com.motcs.commons.utils.ParameterSql;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-08-25 星期二
 */
@Log4j2
@Component
public final class ContextUtil implements InitializingBean {

    private static final List<String> SKIP_CRITERIA_KEYS = List.of("extend", "createdTime", "updatedTime", "creator");

    public static ObjectMapper OBJECT_MAPPER;
    public static Validator VALIDATOR;

    public ContextUtil(Validator validator) {
        ContextUtil.OBJECT_MAPPER = new ObjectMapper();
        ContextUtil.VALIDATOR = validator;
    }

    @Override
    public void afterPropertiesSet() {
        log.debug("ContextUtil 初始化完成!");
    }

    public static ParameterSql applyBindSql(Object object, Collection<String> skipKeys) {
        return applyBindSql(object, null, skipKeys);
    }

    public static ParameterSql applyBindSql(Object object, String prefix, Collection<String> skipKeys) {
        Map<String, Object> objectMap = BeanUtil.beanToMap(object, false, true);

        skipKeys.forEach(objectMap::remove);
        SKIP_CRITERIA_KEYS.forEach(objectMap::remove);
        return applyParamsSql(objectMap.entrySet().stream().filter(entry -> !ObjectUtils.isEmpty(entry.getValue()))
                .collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue)), prefix);
    }

    public static ParameterSql applyParamsSql(Map<String, Object> objectMap, String prefix) {
        StringJoiner stringJoiner = new StringJoiner(" AND ");
        for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
            String key = StrUtil.toUnderlineCase(entry.getKey());
            if (StringUtils.hasLength(prefix)) {
                key = prefix + "." + key;
            }
            Object value = entry.getValue();
            String parameterName = ":" + entry.getKey();
            if (value instanceof String) {
                stringJoiner.add(key + " like " + parameterName);
            } else if (value instanceof Collection<?>) {
                stringJoiner.add(key + " in " + parameterName);
            } else {
                stringJoiner.add(key + " = " + parameterName);
            }
        }

        return ParameterSql.of(stringJoiner, objectMap);
    }

    /**
     * 将条件对象 Criteria 转换为 SQL 查询语句的 WHERE 子句
     *
     * @param criteria 条件对象
     * @return SQL 查询语句的 WHERE 子句，如果条件对象中没有任何条件，则返回空字符串
     */
    public static String applyWhere(Criteria criteria) {
        String whereSql = criteria.toString();
        if (StringUtils.hasLength(whereSql)) {
            return " where " + whereSql;
        }
        return "";
    }

    public static String applyPage(Pageable pageable) {
        return applyPage(pageable, null);
    }

    public static String applyPage(Pageable pageable, String prefix) {
        if (ObjectUtils.isEmpty(pageable)) {
            return "";
        }
        String orderSql = applySort(pageable.getSort(), prefix);
        return String.format("%s limit %d offset %d", orderSql, pageable.getPageSize(), pageable.getOffset());
    }

    /**
     * 根据排序对象生成排序SQL语句
     *
     * @param sort 排序对象
     * @return 排序SQL语句，如果sort为空或未排序则返回""
     */
    public static String applySort(Sort sort, String prefix) {
        if (sort == null || sort.isUnsorted()) {
            return "";
        }
        StringJoiner sortSql = new StringJoiner(",");
        for (Sort.Order order : sort) {
            String sortedPropertyName = convertSortJsonProperty(order);
            String sortedProperty = order.isIgnoreCase() ? "lower(" + sortedPropertyName + ")" : sortedPropertyName;
            if (StringUtils.hasLength(prefix)) {
                sortedProperty = prefix + "." + sortedProperty;
            }
            sortSql.add(sortedProperty + (order.isAscending() ? " ASC" : " DESC"));
        }
        return " ORDER BY " + sortSql;
    }

    private static String convertSortJsonProperty(Sort.Order order) {
        if (order.getProperty().startsWith("extend.") || order.getProperty().startsWith("data.")) {
            String[] keys = StringUtils.delimitedListToStringArray(order.getProperty(), ".");
            String sortedProperty = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, keys[0]); // json 字段
            int lastIndex = keys.length - 1;
            if (lastIndex > 0) {
                sortedProperty = sortedProperty + "->>'" + order.getProperty().replace(keys[0], "$") + "'";
            }
            return sortedProperty;
        }
        return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, order.getProperty());
    }

    public static Criteria build(Object object, Collection<String> skipKeys) {
        return build(object, skipKeys, false);
    }

    /**
     * 构建用于查询的 Criteria 对象
     *
     * @param object   要转换为 Criteria 对象的 JavaBean
     * @param skipKeys 要忽略的属性名列表
     * @return 构建好的 Criteria 对象
     */
    public static Criteria build(Object object, Collection<String> skipKeys, boolean isToUnderlineCase) {
        // 将 JavaBean 转换为 Map
        Map<String, Object> objectMap = BeanUtil.beanToMap(object, isToUnderlineCase, true);
        // 移除要忽略的属性
        skipKeys.forEach(objectMap::remove);
        // 移除默认忽略的属性
        SKIP_CRITERIA_KEYS.forEach(objectMap::remove);
        // 根据 Map 构建 Criteria 对象
        return build(objectMap.entrySet().stream().filter(entry -> !ObjectUtils.isEmpty(entry.getValue()))
                .collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * 根据给定的对象属性Map构建Criteria对象，可选择是否忽略字符串模糊匹配。
     *
     * @param objectMap 对象属性Map
     * @return Criteria对象
     */
    public static Criteria build(Map<String, Object> objectMap) {
        Criteria criteria = Criteria.empty();
        for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String v) {
                criteria = criteria.and(key).like(v + "%").ignoreCase(true);
            } else if (value instanceof Collection<?> v) {
                criteria = criteria.and(key).in(v);
            } else {
                criteria = criteria.and(key).is(value);
            }
        }
        return criteria;
    }
}
