package com.myharness.codex.entity.enums;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(400, HttpStatus.BAD_REQUEST, "请求参数不正确"),
    UNAUTHORIZED(401, HttpStatus.UNAUTHORIZED, "未登录或登录已失效"),
    INVALID_CREDENTIALS(401, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    USER_DISABLED(403, HttpStatus.FORBIDDEN, "用户已被禁用"),
    NOT_FOUND(404, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(409, HttpStatus.CONFLICT, "当前状态不允许此操作"),
    AGENT_ENROLLMENT_INVALID(40111, HttpStatus.UNAUTHORIZED, "注册码不存在、已过期或已使用"),
    AGENT_INVALID_REQUEST(40001, HttpStatus.BAD_REQUEST, "参数无效"),
    AGENT_ENROLLMENT_CONFLICT(40911, HttpStatus.CONFLICT, "注册码已被使用"),
    AGENT_UNAUTHORIZED(40112, HttpStatus.UNAUTHORIZED, "设备身份无效"),
    AGENT_DISABLED(40311, HttpStatus.FORBIDDEN, "设备已被禁用"),
    AGENT_OFFLINE(40912, HttpStatus.CONFLICT, "设备当前不在线"),
    AGENT_PROTOCOL_UNSUPPORTED(42601, HttpStatus.UPGRADE_REQUIRED, "Agent 协议版本不受支持"),
    INTERNAL_ERROR(500, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
