package com.myharness.codex.entity.vo;

public class EnrollmentResultVO {
    private final String deviceCode;
    private final String deviceToken;

    public EnrollmentResultVO(String deviceCode, String deviceToken) {
        this.deviceCode = deviceCode;
        this.deviceToken = deviceToken;
    }
    public String getDeviceCode() { return deviceCode; }
    public String getDeviceToken() { return deviceToken; }
}
