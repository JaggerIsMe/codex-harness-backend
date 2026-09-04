package com.myharness.codex.websocket;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.security.*;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ClientEventAuthorizationTest {
    @Test void managementBroadcastIsNotSentToNormalUserAndMessagesRespectDeviceGrant() throws Exception {
        UserAuthenticationService auth=mock(UserAuthenticationService.class);AuthorizationService access=mock(AuthorizationService.class);
        ClientEventWebSocketHandler handler=new ClientEventWebSocketHandler(new ObjectMapper(),auth,access);
        WebSocketSession admin=session(1L,"admin-token"),normal=session(2L,"normal-token");
        SysUserPO enabled=new SysUserPO();enabled.setStatus("ENABLED");
        when(auth.authenticate(any())).thenReturn(enabled);when(access.hasPermission(1L,"device:manage")).thenReturn(true);
        handler.afterConnectionEstablished(admin);handler.afterConnectionEstablished(normal);
        handler.broadcast(Map.of("type","REGISTER","deviceId",9L));
        verify(admin).sendMessage(any());verify(normal,never()).sendMessage(any());
        handler.sendToUser(2L,Map.of("type","MESSAGE_UPDATED","deviceId",9L));
        verify(normal,never()).sendMessage(any());
        when(access.canUseDevice(2L,9L)).thenReturn(true);
        handler.sendToUser(2L,Map.of("type","MESSAGE_UPDATED","deviceId",9L));
        verify(normal).sendMessage(any());
        clearInvocations(normal);
        when(auth.authenticate("normal-token")).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        handler.sendToUser(2L,Map.of("type","MESSAGE_UPDATED","deviceId",9L));
        verify(normal,never()).sendMessage(any());verify(normal).close(CloseStatus.POLICY_VIOLATION);
    }
    @Test void socketTicketsAreSingleUse(){
        ClientSocketTickets tickets=new ClientSocketTickets();
        String id=tickets.issue("token");assertEquals("token",tickets.consume(id));
        assertThrows(BusinessException.class,()->tickets.consume(id));
        assertThrows(BusinessException.class,()->tickets.consume("unknown"));
    }
    private WebSocketSession session(Long id,String token){
        WebSocketSession session=mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);when(session.getAttributes()).thenReturn(Map.of("userId",id,"token",token));return session;
    }
}

