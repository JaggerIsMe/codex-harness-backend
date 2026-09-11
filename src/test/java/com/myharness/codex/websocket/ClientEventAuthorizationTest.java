package com.myharness.codex.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.ClientSocketTickets;
import com.myharness.codex.security.UserAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClientEventAuthorizationTest {
    private UserAuthenticationService auth;
    private AuthorizationService access;
    private ClientEventWebSocketHandler handler;

    @BeforeEach
    void setup() {
        auth = mock(UserAuthenticationService.class);
        access = mock(AuthorizationService.class);
        handler = new ClientEventWebSocketHandler(new ObjectMapper(), auth, access);
        SysUserPO enabled = new SysUserPO();
        enabled.setStatus("ENABLED");
        when(auth.authenticate(any())).thenReturn(enabled);
    }

    @Test
    void managementBroadcastIsNotSentToNormalUserAndMessagesRespectDeviceGrant() throws Exception {
        WebSocketSession admin = session(1L, "admin-token", 1);
        WebSocketSession normal = session(2L, "normal-token", 1);
        when(access.hasPermission(1L, "device:manage")).thenReturn(true);
        handler.afterConnectionEstablished(admin);
        handler.afterConnectionEstablished(normal);
        handler.broadcast(Map.of("type", "REGISTER", "deviceId", 9L));
        verify(admin).sendMessage(any());
        verify(normal, never()).sendMessage(any());
        handler.sendToUser(2L, Map.of("type", "MESSAGE_UPDATED", "deviceId", 9L));
        verify(normal, never()).sendMessage(any());
        when(access.canUseDevice(2L, 9L)).thenReturn(true);
        handler.sendToUser(2L, Map.of("type", "MESSAGE_UPDATED", "deviceId", 9L));
        verify(normal).sendMessage(any());
        clearInvocations(normal);
        when(auth.authenticate("normal-token")).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        handler.sendToUser(2L, Map.of("type", "MESSAGE_UPDATED", "deviceId", 9L));
        verify(normal, never()).sendMessage(any());
        verify(normal).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void replacementDetectedBeforeSendingClosesOldLoginWith4001() throws Exception {
        WebSocketSession old = session(3L, "old-token", 1);
        handler.afterConnectionEstablished(old);
        when(auth.authenticate("old-token")).thenThrow(new BusinessException(ErrorCode.SESSION_REPLACED));

        handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED"));

        verify(old).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        verify(old, never()).sendMessage(any());
        verifyNoInteractions(access);
        clearInvocations(auth);
        handler.expireSessions();
        verifyNoInteractions(auth);
    }

    @Test
    void redisOutageDuringRevalidationClosesWithRetryable1013() throws Exception {
        WebSocketSession current = session(3L, "current-token", 2);
        handler.afterConnectionEstablished(current);
        when(auth.authenticate("current-token")).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));

        handler.expireSessions();

        verify(current).close(new CloseStatus(1013, "SESSION_UNAVAILABLE"));
        verify(current, never()).sendMessage(any());
        clearInvocations(auth);
        handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED"));
        verifyNoInteractions(auth);
    }

    @Test
    void expiredAccessClosesSocketWithRefreshable4002WithoutSendingOrTouchingActivity() throws Exception {
        WebSocketSession current=session(3L,"expired-access",2);
        handler.afterConnectionEstablished(current);
        clearInvocations(auth);
        when(auth.authenticate("expired-access")).thenThrow(new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED));
        handler.sendToUser(3L,Map.of("type","MESSAGE_UPDATED"));
        verify(current).close(ClientEventWebSocketHandler.ACCESS_TOKEN_EXPIRED);
        verify(current,never()).sendMessage(any());
        verify(auth,never()).recordActivity(any());
        verifyNoInteractions(access);
        clearInvocations(auth);
        handler.expireSessions();
        verifyNoInteractions(auth);
    }

    @Test
    void connectionEstablishedAfterReplacementIsRevalidatedAndRemoved() throws Exception {
        WebSocketSession old = session(3L, "old-token", 1);
        handler.disconnectBeforeVersion(3L, 2, true);
        when(auth.authenticate("old-token")).thenThrow(new BusinessException(ErrorCode.SESSION_REPLACED));

        handler.afterConnectionEstablished(old);

        verify(auth).authenticate("old-token");
        verify(old).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        clearInvocations(auth);
        handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED"));
        handler.expireSessions();
        verifyNoInteractions(auth);
        verify(old, never()).sendMessage(any());
    }

    @Test
    void redisOutageDuringConnectionRegistrationDoesNotLeaveSocketRegistered() throws Exception {
        WebSocketSession current = session(3L, "current-token", 2);
        when(auth.authenticate("current-token")).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));

        handler.afterConnectionEstablished(current);

        verify(current).close(new CloseStatus(1013, "SESSION_UNAVAILABLE"));
        clearInvocations(auth);
        handler.expireSessions();
        verifyNoInteractions(auth);
    }

    @Test
    void delayedReplacementAndRevocationCannotCloseSameOrNewerLoginVersions() throws Exception {
        WebSocketSession old = session(3L, "old-token", 1);
        WebSocketSession current = session(3L, "current-token", 2);
        WebSocketSession newer = session(3L, "newer-token", 3);
        WebSocketSession otherUser = session(4L, "other-token", 1);
        handler.afterConnectionEstablished(old);
        handler.afterConnectionEstablished(current);
        handler.afterConnectionEstablished(newer);
        handler.afterConnectionEstablished(otherUser);

        handler.disconnectBeforeVersion(3L, 2, true);
        handler.disconnectBeforeVersion(3L, 2, true);

        verify(old).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        verify(current, never()).close(any());
        verify(newer, never()).close(any());
        verify(otherUser, never()).close(any());
        handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED"));
        verify(old, never()).sendMessage(any());
        verify(current).sendMessage(any());
        verify(newer).sendMessage(any());
        verify(otherUser, never()).sendMessage(any());

        handler.disconnectBeforeVersion(3L, 3, false);

        verify(current).close(CloseStatus.POLICY_VIOLATION);
        verify(newer, never()).close(any());
        verify(otherUser, never()).close(any());
    }

    @Test
    void failedTransportCloseDoesNotInterruptOtherRevocationsOrFutureLogins() throws Exception {
        WebSocketSession failing = session(3L, "failing-token", 1);
        WebSocketSession old = session(3L, "old-token", 1);
        WebSocketSession current = session(3L, "current-token", 2);
        handler.afterConnectionEstablished(failing);
        handler.afterConnectionEstablished(old);
        handler.afterConnectionEstablished(current);
        doThrow(new IllegalStateException("transport already closed")).when(failing).close(any());

        assertDoesNotThrow(() -> handler.disconnectBeforeVersion(3L, 2, true));

        verify(failing).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        verify(old).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        verify(current, never()).close(any());
        WebSocketSession future = session(3L, "future-token", 3);
        handler.afterConnectionEstablished(future);
        handler.disconnectBeforeVersion(3L, 3, true);
        handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED"));
        verify(current).close(ClientEventWebSocketHandler.SESSION_REPLACED);
        verify(future, never()).close(any());
        verify(future).sendMessage(any());
        verify(failing, never()).sendMessage(any());
    }

    @Test
    void sendIllegalStateExceptionIsContainedAndHealthySocketStillReceivesEvent() throws Exception {
        WebSocketSession failing = session(3L, "failing-token", 1);
        WebSocketSession healthy = session(3L, "healthy-token", 1);
        handler.afterConnectionEstablished(failing);
        handler.afterConnectionEstablished(healthy);
        doThrow(new IllegalStateException("transport unavailable")).when(failing).sendMessage(any());

        assertDoesNotThrow(() -> handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED")));

        verify(failing).close(CloseStatus.SERVER_ERROR);
        verify(healthy).sendMessage(any());
        clearInvocations(auth);
        handler.expireSessions();
        verify(auth).authenticate("healthy-token");
        verify(auth, never()).authenticate("failing-token");
    }

    @Test
    void transportClosedBeforeSendIsRemovedWithoutSendingOrClosingAgain() throws Exception {
        WebSocketSession socket = session(3L, "current-token", 2);
        handler.afterConnectionEstablished(socket);
        when(socket.isOpen()).thenReturn(true, false);

        assertDoesNotThrow(() -> handler.sendToUser(3L, Map.of("type", "MESSAGE_UPDATED")));

        verify(socket, never()).sendMessage(any());
        verify(socket, never()).close(any());
        clearInvocations(auth);
        handler.expireSessions();
        verifyNoInteractions(auth);
    }

    @Test
    void socketTicketsAreSingleUse() {
        ClientSocketTickets tickets = new ClientSocketTickets();
        String id = tickets.issue("token");
        assertEquals("token", tickets.consume(id));
        assertThrows(BusinessException.class, () -> tickets.consume(id));
        assertThrows(BusinessException.class, () -> tickets.consume("unknown"));
    }

    private WebSocketSession session(Long userId, String token, long version) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of(
                "userId", userId, "token", token, "loginVersion", version, "loginSid", token + "-sid"));
        return socket;
    }
}

