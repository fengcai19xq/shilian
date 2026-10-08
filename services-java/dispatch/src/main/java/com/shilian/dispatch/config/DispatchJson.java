package com.shilian.dispatch.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/** 全模块统一的 JSON 约定：字段 snake_case，未知字段忽略。 */
public final class DispatchJson {

    private DispatchJson() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
