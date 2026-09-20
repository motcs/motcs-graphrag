package com.motcs.commons.base;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 自动封装工具基础类
 * Automatic encapsulation tool base class
 *
 * @author <a href="https://github.com/vnobo">Alex bob</a>
 */
@Log4j2
public abstract class AbstractService implements InitializingBean {

    protected ObjectMapper objectMapper;

    /**
     * 自动装配objectMapper
     * Automatic assembly objectMapper
     *
     * @param objectMapper 待注入的objectMapper对象
     *                     ObjectMapper object to be injected
     */
    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

}