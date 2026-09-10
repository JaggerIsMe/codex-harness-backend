package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailConfiguration;
import com.myharness.codex.config.AccountMailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMailContextTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(AccountMailConfiguration.class, AccountMailProperties.class,
                    AccountMailCrypto.class, AccountMailSettings.class, SmtpMailDeliveryAdapter.class);

    @Test void disabledMailConstructsWithoutSmtpHostKeysOrJavaMailSender() {
        context.withPropertyValues("harness.mail.enabled=false").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(MailProperties.class);
            assertThat(application).hasSingleBean(AccountMailSettings.class);
            assertThat(application).hasSingleBean(MailDeliveryAdapter.class);
            assertThat(application).doesNotHaveBean(JavaMailSender.class);
        });
    }

    @Test void enabledMailBindsAndConstructsWithoutAnySmtpConnection() {
        AccountMailProperties keys = AccountMailCryptoTest.properties();
        context.withPropertyValues("harness.mail.enabled=true", "harness.mail.from=no-reply@example.com",
                "harness.mail.public-base-url=https://harness.example.com",
                "harness.mail.hmac-key=" + keys.getHmacKey(), "harness.mail.encryption-key=" + keys.getEncryptionKey(),
                "spring.mail.host=smtp.invalid", "spring.mail.port=465", "spring.mail.username=test@example.com",
                "spring.mail.password=test-only-password", "spring.mail.properties.mail.smtp.auth=true",
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
                "spring.mail.properties.mail.smtp.ssl.enable=true", "spring.mail.properties.mail.smtp.connectiontimeout=5000",
                "spring.mail.properties.mail.smtp.timeout=5000", "spring.mail.properties.mail.smtp.writetimeout=5000")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(MailProperties.class);
                    assertThat(application).hasSingleBean(JavaMailSender.class);
                });
    }
}
