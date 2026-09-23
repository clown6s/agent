package com.zzp.agent.common;

/** 通用 HTTP 接口返回结构，后续所有接口统一使用 */
public record ApiResponse<T>(
        int code,        // 业务码，200 成功，非 200 失败
        String message,  // 提示信息
        T data           // 数据体
) {
    /** 成功 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    /** 失败 */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
