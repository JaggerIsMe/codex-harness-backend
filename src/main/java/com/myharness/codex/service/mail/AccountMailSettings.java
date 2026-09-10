package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** Static validation never connects to SMTP and never includes configured values in exceptions. */
@Component
public class AccountMailSettings {
    private final AccountMailProperties properties;
    private final MailProperties smtp;
    private final AccountMailCrypto crypto;
    private final ObjectProvider<JavaMailSender> sender;

    public AccountMailSettings(AccountMailProperties properties, MailProperties smtp, AccountMailCrypto crypto,
                               ObjectProvider<JavaMailSender> sender) {
        this.properties = properties;
        this.smtp = smtp;
        this.crypto = crypto;
        this.sender = sender;
    }

    @PostConstruct
    public void validateEnabledConfiguration() { if (properties.isEnabled()) validateStatic(); }

    public void requireSendingConfigured() {
        if (!properties.isEnabled()) throw new BusinessException(ErrorCode.EMAIL_UNAVAILABLE);
        try { validateStatic(); }
        catch (IllegalStateException ex) { throw new BusinessException(ErrorCode.EMAIL_UNAVAILABLE); }
    }

    private void validateStatic() {
        require(smtp.getHost() != null && !smtp.getHost().isBlank(), "spring.mail.host 不能为空");
        require("smtp".equals(smtp.getProtocol()), "spring.mail.protocol 必须为 smtp");
        require(smtp.getPort() != null && smtp.getPort() > 0 && smtp.getPort() <= 65535, "spring.mail.port 无效");
        require(sender.getIfAvailable() != null, "邮件发送适配器未配置");
        require(smtp.getUsername() != null && !smtp.getUsername().isBlank(), "spring.mail.username 不能为空");
        require(smtp.getPassword() != null && !smtp.getPassword().isBlank(), "spring.mail.password 不能为空");
        require(Boolean.parseBoolean(smtp.getProperties().get("mail.smtp.auth")), "mail.smtp.auth 必须开启");
        require(Boolean.parseBoolean(smtp.getProperties().get("mail.smtp.ssl.checkserveridentity")), "mail.smtp.ssl.checkserveridentity 必须开启");
        boolean ssl = Boolean.parseBoolean(smtp.getProperties().get("mail.smtp.ssl.enable"));
        boolean startTls = Boolean.parseBoolean(smtp.getProperties().get("mail.smtp.starttls.enable"));
        boolean requiredTls = Boolean.parseBoolean(smtp.getProperties().get("mail.smtp.starttls.required"));
        require(ssl ? !startTls && !requiredTls : startTls && requiredTls, "SMTP 必须选择 SSL 或强制 STARTTLS");
        long totalTimeout = 0;
        for (String name : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            try {
                long value = Long.parseLong(smtp.getProperties().getOrDefault("mail.smtp." + name, "0"));
                require(value > 0 && value <= 60000, "SMTP 超时必须在 1 到 60000 毫秒之间");
                totalTimeout += value;
            } catch (NumberFormatException ex) { throw new IllegalStateException("SMTP 超时必须为正整数毫秒"); }
        }
        require(properties.getDeliveryLeaseSeconds() * 1000L > totalTimeout + 10000,
                "harness.mail.delivery-lease-seconds 必须大于 SMTP 三类超时总和加 10 秒");
        require(isMailbox(properties.getFrom()), "harness.mail.from 必须是有效邮箱地址");
        require(properties.getFromName() != null && !properties.getFromName().contains("\r") && !properties.getFromName().contains("\n"), "harness.mail.from-name 无效");
        validateBaseUrl(properties.getPublicBaseUrl());
        crypto.validateKeys();
    }

    public static boolean isMailbox(String value) {
        if (value == null || value.length() > 254 || value.contains("\r") || value.contains("\n")) return false;
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            return value.equals(address.getAddress()) && value.contains("@");
        } catch (Exception ex) { return false; }
    }

    static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            String host = uri.getHost();
            boolean local = host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            require(host != null && ("https".equals(scheme) || (local && "http".equals(scheme)))
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                    "harness.mail.public-base-url 必须是 HTTPS 前端地址；本机开发允许 HTTP");
        } catch (IllegalArgumentException ex) { throw new IllegalStateException("harness.mail.public-base-url 无效"); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
