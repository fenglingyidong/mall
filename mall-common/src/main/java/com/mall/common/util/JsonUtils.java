package com.mall.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;

public final class JsonUtils {

    private JsonUtils() {
    }

    public static String toJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "JSON serialization failed");
        }
    }

    public static <T> T fromJson(ObjectMapper objectMapper, String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "JSON deserialization failed");
        }
    }
}
