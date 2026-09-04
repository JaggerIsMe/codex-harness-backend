package com.myharness.codex.entity.vo;

public class ApiResponseVO<T> {

    private final String status;
    private final int code;
    private final String info;
    private final T data;

    private ApiResponseVO(String status, int code, String info, T data) {
        this.status = status;
        this.code = code;
        this.info = info;
        this.data = data;
    }

    public static <T> ApiResponseVO<T> success(T data) {
        return new ApiResponseVO<T>("success", 200, "请求成功", data);
    }

    public static <T> ApiResponseVO<T> success(String info, T data) {
        return new ApiResponseVO<T>("success", 200, info, data);
    }

    public static <T> ApiResponseVO<T> error(int code, String info) {
        return new ApiResponseVO<T>("error", code, info, null);
    }

    public String getStatus() {
        return status;
    }

    public int getCode() {
        return code;
    }

    public String getInfo() {
        return info;
    }

    public T getData() {
        return data;
    }
}
