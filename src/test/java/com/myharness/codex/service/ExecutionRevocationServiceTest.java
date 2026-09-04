package com.myharness.codex.service;

import com.myharness.codex.entity.po.ApprovalRequestPO;
import com.myharness.codex.entity.vo.RevokedTurnVO;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExecutionRevocationServiceTest {
    @Test void revocationCancelsPendingApprovalBeforeInterruptAndRetriesUntilTerminalEvent() {
        var conversations=mock(ConversationMapper.class);var approvals=mock(ApprovalMapper.class);
        var access=mock(AuthorizationService.class);var gateway=mock(AgentCommandGateway.class);
        when(conversations.selectRunningWork()).thenReturn(List.of(new RevokedTurnVO(7L,8L,3L,2L,"device")));
        when(gateway.isOnline("device")).thenReturn(true);
        var pending=new ApprovalRequestPO();pending.setId(9L);pending.setStatus("PENDING");pending.setRemoteRequestId("remote");
        when(approvals.selectByConversation(8L)).thenReturn(List.of(pending));
        var service=new ExecutionRevocationService(conversations,approvals,access,gateway);
        service.interruptRevokedWork();
        var sent=ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway,times(2)).send(eq("device"),sent.capture());
        assertEquals(List.of("RESOLVE_APPROVAL","INTERRUPT_TURN"),sent.getAllValues().stream().map(AgentCommand::getType).toList());
        verify(approvals).decide(eq(9L),eq("CANCELLED"),isNull(),any());
        clearInvocations(gateway);when(approvals.selectByConversation(8L)).thenReturn(List.of());
        service.interruptRevokedWork();verify(gateway).send(eq("device"),any());
        // Sending interrupt is not evidence that execution stopped; no terminal DB mutation here.
        verify(conversations,never()).finishTurn(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void stillAuthorizedWorkIsUntouched() {
        var conversations=mock(ConversationMapper.class);var approvals=mock(ApprovalMapper.class);
        var access=mock(AuthorizationService.class);var gateway=mock(AgentCommandGateway.class);
        when(conversations.selectRunningWork()).thenReturn(List.of(new RevokedTurnVO(7L,8L,3L,2L,"device")));
        when(access.canUseDevice(3L,2L)).thenReturn(true);
        new ExecutionRevocationService(conversations,approvals,access,gateway).interruptRevokedWork();
        verifyNoInteractions(gateway,approvals);
    }
}
