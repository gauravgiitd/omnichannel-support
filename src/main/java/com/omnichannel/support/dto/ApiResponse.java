package com.omnichannel.support.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ApiResponse<T>(String result, String message, T data, ApiMeta meta) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", null, data, null);
    }

    public static <T> ApiResponse<T> error(String message, String errorCode, Integer httpStatus) {
        return new ApiResponse<>("ERROR", message, null, new ApiMeta(httpStatus, errorCode));
    }
}
