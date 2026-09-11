package com.myharness.codex.entity.enums;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(400, HttpStatus.BAD_REQUEST, "请求参数不正确"),
    UNAUTHORIZED(401, HttpStatus.UNAUTHORIZED, "未登录或登录已失效"),
    SESSION_REPLACED(40121, HttpStatus.UNAUTHORIZED, "账号已在其他位置登录，请重新登录"),
    ACCESS_TOKEN_EXPIRED(40122, HttpStatus.UNAUTHORIZED, "访问凭证已过期，请刷新凭证"),
    REFRESH_CONFLICT(40922, HttpStatus.CONFLICT, "刷新凭证已轮换，请读取最新状态后重试"),
    SESSION_UNAVAILABLE(50322, HttpStatus.SERVICE_UNAVAILABLE, "登录服务暂不可用，请稍后重试"),
    FORBIDDEN(403, HttpStatus.FORBIDDEN, "没有执行此操作的权限"),
    PASSWORD_CHANGE_REQUIRED(40301, HttpStatus.FORBIDDEN, "请先修改初始密码"),
    TOO_MANY_REQUESTS(429, HttpStatus.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试"),
    INVALID_CREDENTIALS(401, HttpStatus.UNAUTHORIZED, "邮箱或密码错误"),
    EMAIL_VERIFICATION_INVALID(40021, HttpStatus.BAD_REQUEST, "验证信息错误或已失效"),
    EMAIL_VERIFICATION_EXPIRED(41021, HttpStatus.GONE, "验证信息已过期，请重新发送"),
    EMAIL_VERIFICATION_USED(40921, HttpStatus.CONFLICT, "验证信息已使用或已失效"),
    EMAIL_RATE_LIMITED(42921, HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试"),
    EMAIL_UNAVAILABLE(50321, HttpStatus.SERVICE_UNAVAILABLE, "邮件服务暂不可用，请稍后重试"),
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
