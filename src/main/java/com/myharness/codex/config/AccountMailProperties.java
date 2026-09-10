package com.myharness.codex.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "harness.mail")
public class AccountMailProperties {
    private boolean enabled;
    private String from = "";
    private String fromName = "My Harness For Codex";
    private String publicBaseUrl = "";
    private String hmacKey = "";
    private String encryptionKey = "";
    @Min(1) @Max(168) private int activationTtlHours = 24;
    @Min(1) @Max(60) private int resetTtlMinutes = 10;
    @Min(1) @Max(20) private int maxVerificationFailures = 5;
    @Min(1) private int resendCooldownSeconds = 60;
    @Min(1) private int emailHourlyLimit = 5;
    @Min(1) private int ipSendHourlyLimit = 30;
    @Min(1) private int emailVerifyHourlyLimit = 20;
    @Min(1) private int ipVerifyHourlyLimit = 100;
    @Min(1) @Max(20) private int maxDeliveryAttempts = 5;
    @Min(30) private int deliveryLeaseSeconds = 120;
    @Min(1) private int retryBaseSeconds = 30;
    @Min(1) @Max(100) private int deliveryBatchSize = 20;
    @Min(1) private int metadataRetentionDays = 30;
    @Min(1) private int notificationTtlHours = 72;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getFrom() { return from; }
    public void setFrom(String value) { from = value; }
    public String getFromName() { return fromName; }
    public void setFromName(String value) { fromName = value; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    public String getHmacKey() { return hmacKey; }
    public void setHmacKey(String value) { hmacKey = value; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String value) { encryptionKey = value; }
    public int getActivationTtlHours() { return activationTtlHours; }
    public void setActivationTtlHours(int value) { activationTtlHours = value; }
    public int getResetTtlMinutes() { return resetTtlMinutes; }
    public void setResetTtlMinutes(int value) { resetTtlMinutes = value; }
    public int getMaxVerificationFailures() { return maxVerificationFailures; }
    public void setMaxVerificationFailures(int value) { maxVerificationFailures = value; }
    public int getResendCooldownSeconds() { return resendCooldownSeconds; }
    public void setResendCooldownSeconds(int value) { resendCooldownSeconds = value; }
    public int getEmailHourlyLimit() { return emailHourlyLimit; }
    public void setEmailHourlyLimit(int value) { emailHourlyLimit = value; }
    public int getIpSendHourlyLimit() { return ipSendHourlyLimit; }
    public void setIpSendHourlyLimit(int value) { ipSendHourlyLimit = value; }
    public int getEmailVerifyHourlyLimit() { return emailVerifyHourlyLimit; }
    public void setEmailVerifyHourlyLimit(int value) { emailVerifyHourlyLimit = value; }
    public int getIpVerifyHourlyLimit() { return ipVerifyHourlyLimit; }
    public void setIpVerifyHourlyLimit(int value) { ipVerifyHourlyLimit = value; }
    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int value) { maxDeliveryAttempts = value; }
    public int getDeliveryLeaseSeconds() { return deliveryLeaseSeconds; }
    public void setDeliveryLeaseSeconds(int value) { deliveryLeaseSeconds = value; }
    public int getRetryBaseSeconds() { return retryBaseSeconds; }
    public void setRetryBaseSeconds(int value) { retryBaseSeconds = value; }
    public int getDeliveryBatchSize() { return deliveryBatchSize; }
    public void setDeliveryBatchSize(int value) { deliveryBatchSize = value; }
    public int getMetadataRetentionDays() { return metadataRetentionDays; }
    public void setMetadataRetentionDays(int value) { metadataRetentionDays = value; }
    public int getNotificationTtlHours() { return notificationTtlHours; }
    public void setNotificationTtlHours(int value) { notificationTtlHours = value; }
}
