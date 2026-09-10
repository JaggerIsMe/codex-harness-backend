package com.myharness.codex.service.mail;

import com.myharness.codex.config.AccountMailProperties;
import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.MailDeliveryTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
    private final AccountMailProperties properties = AccountMailCryptoTest.properties();
    private final AccountMailCrypto crypto = new AccountMailCrypto(properties);
    private final MailDeliveryTaskMapper tasks = mock(MailDeliveryTaskMapper.class);
    private final AccountMailSettings settings = mock(AccountMailSettings.class);
    private final AccountMailService service = new AccountMailService(tasks, crypto, settings, properties, clock);

    private SysUserPO user() {
        SysUserPO user = new SysUserPO();
        user.setId(7L);
        user.setEmail("person@example.com");
        user.setDisplayName("示例");
        return user;
    }

    @Test void invitationStoresOnlyEncryptedCredentialAndDoesNotPerformDelivery() {
        String token = crypto.newActivationToken();
        LocalDateTime expires = LocalDateTime.now(clock).plusHours(24);
        service.enqueueActivation(user(), 9L, token, expires);
        ArgumentCaptor<MailDeliveryTaskPO> captor = ArgumentCaptor.forClass(MailDeliveryTaskPO.class);
        verify(tasks).insert(captor.capture());
        MailDeliveryTaskPO task = captor.getValue();
        assertNotEquals(token, task.getEncryptedPayload());
        assertEquals(token, crypto.decrypt(task.getEncryptedPayload(), task.payloadBinding()));
        assertEquals(expires, task.getExpiresAt());
        assertEquals("challenge:9", task.getIdempotencyKey());
        verify(settings).requireSendingConfigured();
    }

    @Test void passwordChangeNotificationCanBeRecordedWhileMailIsDisabled() {
        properties.setEnabled(false);
        properties.setEncryptionKey("");
        service.enqueuePasswordChanged(user(), "event-id");
        ArgumentCaptor<MailDeliveryTaskPO> captor = ArgumentCaptor.forClass(MailDeliveryTaskPO.class);
        verify(tasks).insert(captor.capture());
        assertNull(captor.getValue().getEncryptedPayload());
        assertNull(captor.getValue().getChallengeId());
        assertEquals("password-changed:7:event-id", captor.getValue().getIdempotencyKey());
        verifyNoInteractions(settings);
    }
}
