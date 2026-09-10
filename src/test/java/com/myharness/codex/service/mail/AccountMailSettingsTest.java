package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailSettingsTest {
    private final AccountMailProperties properties = AccountMailCryptoTest.properties();
    private final MailProperties smtp = new MailProperties();
    @SuppressWarnings("unchecked") private final ObjectProvider<JavaMailSender> sender = mock(ObjectProvider.class);
    private final AccountMailSettings settings = new AccountMailSettings(properties, smtp, new AccountMailCrypto(properties), sender);

    @BeforeEach void configure() {
        properties.setEnabled(true);
        properties.setFrom("no-reply@example.com");
        properties.setPublicBaseUrl("https://harness.example.com");
        smtp.setHost("smtp.example.com");
        smtp.setPort(465);
        smtp.setUsername("test@example.com");
        smtp.setPassword("test-only-credential");
        smtp.getProperties().put("mail.smtp.auth", "true");
        smtp.getProperties().put("mail.smtp.ssl.checkserveridentity", "true");
        smtp.getProperties().put("mail.smtp.ssl.enable", "true");
        for (String key : new String[]{"connectiontimeout", "timeout", "writetimeout"}) smtp.getProperties().put("mail.smtp." + key, "5000");
        when(sender.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
    }

    @Test void validatesConfigurationWithoutConnectingToSmtp() {
        settings.validateEnabledConfiguration();
        verifyNoInteractions(sender.getIfAvailable());
    }

    @Test void rejectsInsecureTlsAndUnlimitedTimeouts() {
        smtp.getProperties().put("mail.smtp.ssl.enable", "false");
        assertThrows(IllegalStateException.class, settings::validateEnabledConfiguration);
        smtp.getProperties().put("mail.smtp.starttls.enable", "true");
        smtp.getProperties().put("mail.smtp.starttls.required", "true");
        assertDoesNotThrow(settings::validateEnabledConfiguration);
        smtp.getProperties().put("mail.smtp.timeout", "0");
        assertThrows(IllegalStateException.class, settings::validateEnabledConfiguration);
    }

    @Test void errorsNameMissingConfigurationWithoutExposingAnyValues() {
        properties.setHmacKey("do-not-log-this-key");
        IllegalStateException error = assertThrows(IllegalStateException.class, settings::validateEnabledConfiguration);
        assertTrue(error.getMessage().contains("hmac-key"));
        assertFalse(error.getMessage().contains("do-not-log-this-key"));
        assertThrows(BusinessException.class, settings::requireSendingConfigured);
    }

    @Test void validatesPublicUrlAndSenderAgainstHeaderAndRedirectInjection() {
        assertFalse(AccountMailSettings.isMailbox("Admin <no-reply@example.com>"));
        assertFalse(AccountMailSettings.isMailbox("no-reply@example.com\r\nBcc:evil@example.com"));
        assertTrue(AccountMailSettings.isMailbox("no-reply@example.com"));
        assertDoesNotThrow(() -> AccountMailSettings.validateBaseUrl("http://localhost:5173"));
        for (String url : new String[]{"http://public.example.com", "https://user@public.example.com", "https://public.example.com?redirect=evil", "https://public.example.com/#fragment"}) {
            assertThrows(IllegalStateException.class, () -> AccountMailSettings.validateBaseUrl(url));
        }
    }

    @Test void disabledConfigurationDoesNotBlockProcessingAlreadyIssuedCredentials() {
        properties.setEnabled(false);
        properties.setHmacKey("");
        assertDoesNotThrow(settings::validateEnabledConfiguration);
        assertThrows(BusinessException.class, settings::requireSendingConfigured);
    }
}
