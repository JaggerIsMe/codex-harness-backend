package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.entity.dto.ApprovalDecisionDTO;
import com.myharness.codex.entity.po.ApprovalRequestPO;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.service.stream.ConversationMessageStream;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalFlowTest {
    @Test void choiceAnswersAreValidatedBeforeSavingAndForwardedWithoutGuessing() throws Exception {
        var mapper=mock(ApprovalMapper.class);var gateway=mock(AgentCommandGateway.class);var access=mock(AuthorizationService.class);
        var service=new ApprovalServiceImpl(mapper,gateway,access);var json=new ObjectMapper();
        var approval=new ApprovalRequestPO();approval.setId(11L);approval.setDeviceId(1L);approval.setDeviceCode("test-device");approval.setRemoteRequestId("question");approval.setApprovalType("MCP_TOOL_CALL");
        approval.setPayload("{\"questions\":[{\"id\":\"method\",\"options\":[{\"label\":\"手绘矢量插画\"},{\"label\":\"只给绘图提示词\"}]}]}");
        when(mapper.selectOwnedById(11L,9L)).thenReturn(approval);when(gateway.isOnline("test-device")).thenReturn(true);
        var dto=new ApprovalDecisionDTO();dto.setDecision("ACCEPT");
        assertThrows(BusinessException.class,()->service.decide(11L,dto,9L));
        verify(mapper,never()).decide(any(),any(),any(),any());verifyNoInteractions(gateway);
        dto.setAnswers(json.readTree("{\"method\":{\"answers\":[\"只给绘图提示词\"]}}"));
        when(mapper.decide(eq(11L),eq("APPROVED"),eq(9L),any())).thenReturn(1);
        when(mapper.recordDispatch(eq(11L),anyString())).thenReturn(1);
        service.decide(11L,dto,9L);
        var sent=ArgumentCaptor.forClass(AgentCommand.class);verify(gateway).send(eq("test-device"),sent.capture());
        assertEquals(dto.getAnswers(),json.valueToTree(sent.getValue().getPayload()).path("answers"));
        verify(mapper).recordDispatch(11L,sent.getValue().getMessageId());
        assertEquals(sent.getValue().getMessageId(),json.valueToTree(sent.getValue().getPayload()).path("decisionMessageId").asText());
    }

    @Test void rejectedAnswerIsReopenedOnlyForItsDeliveryAttemptAndRoutedToConversationOwner() {
        var devices=mock(AgentDeviceMapper.class);var conversations=mock(ConversationMapper.class);var approvals=mock(ApprovalMapper.class);var clients=mock(ClientEventWebSocketHandler.class);
        var transactions=mock(TransactionTemplate.class);when(transactions.execute(any())).thenAnswer(invocation -> ((org.springframework.transaction.support.TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(null));
        var service=new AgentEventServiceImpl(devices,conversations,approvals,clients,mock(ConversationMessageStream.class),transactions);
        var approval=new ApprovalRequestPO();approval.setId(11L);approval.setConversationId(4L);approval.setTurnId(7L);approval.setDeviceId(1L);
        var conversation=new ConversationPO();conversation.setUserId(9L);when(conversations.selectConversation(4L)).thenReturn(conversation);when(approvals.selectById(11L)).thenReturn(approval);
        when(devices.insertEvent(eq(1L),anyString(),anyString(),eq(10L))).thenReturn(1);
        when(approvals.rejectDispatch(eq(11L),eq(1L),anyString())).thenReturn(1);
        var error=new AgentProtocolEnvelope();error.setType("ERROR");error.setMessageId("event-error");error.setTimestamp(10L);error.setCorrelationId("11");
        error.setPayload(new ObjectMapper().createObjectNode().put("commandType","RESOLVE_APPROVAL").put("commandMessageId","6d88aca8-a792-4b55-a08d-abf07aa2e2a6").put("errorCode","APPROVAL_INPUT_INVALID").put("message","请选择具体方案"));
        assertTrue(service.process(1L,error));
        verify(approvals).rejectDispatch(11L,1L,"6d88aca8-a792-4b55-a08d-abf07aa2e2a6");
        assertEquals(4L,error.getPayload().path("conversationId").asLong());verify(clients).sendToUser(eq(9L),any());verify(clients,never()).broadcast(any());verify(conversations,never()).resumeTurn(any(),any());
        ((com.fasterxml.jackson.databind.node.ObjectNode)error.getPayload()).put("errorCode","COMMAND_FAILED");
        service.process(1L,error);verify(approvals,times(1)).rejectDispatch(any(),any(),any());
        clearInvocations(clients);
        when(approvals.rejectDispatch(any(),any(),any())).thenReturn(0);
        ((com.fasterxml.jackson.databind.node.ObjectNode)error.getPayload()).put("errorCode","APPROVAL_INPUT_INVALID");
        service.process(1L,error);verifyNoInteractions(clients);
    }
    @Test void persistsPendingRequestBeforeSendingOnlyToOwnerAndResumesOnAcknowledgement() {
        var devices=mock(AgentDeviceMapper.class);
        var conversations=mock(ConversationMapper.class);
        var approvals=mock(ApprovalMapper.class);
        var clients=mock(ClientEventWebSocketHandler.class);
        // Exercise the control handler through a transaction callback; the database is mocked.
        var transactions=mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(null));
        var service=new AgentEventServiceImpl(devices,conversations,approvals,clients,
                mock(ConversationMessageStream.class),transactions);
        var conversation=new ConversationPO();conversation.setId(4L);conversation.setDeviceId(1L);conversation.setUserId(9L);
        when(conversations.selectConversation(4L)).thenReturn(conversation);
        when(devices.insertEvent(eq(1L),anyString(),anyString(),eq(10L))).thenReturn(1);
        when(approvals.insert(any())).thenReturn(1);
        var payload=new ObjectMapper().createObjectNode().put("conversationId","4").put("turnId","7")
                .put("requestId","mcp-approval").put("approvalType","MCP_TOOL_CALL");
        payload.putObject("details").put("question","Allow this MCP tool call?");
        var required=new AgentProtocolEnvelope();required.setType("APPROVAL_REQUIRED");required.setMessageId("required");
        required.setTimestamp(10L);required.setPayload(payload);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.process(1L,required));
            var saved=ArgumentCaptor.forClass(ApprovalRequestPO.class);
            verify(approvals).insert(saved.capture());
            assertEquals("mcp-approval",saved.getValue().getRemoteRequestId());
            assertEquals(4L,saved.getValue().getConversationId());
            assertEquals("MCP_TOOL_CALL",saved.getValue().getApprovalType());
            verify(conversations).waitApproval(7L,4L);
            verifyNoInteractions(clients);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(clients).sendToUser(eq(9L),argThat(event ->
                    "APPROVAL_REQUIRED".equals(new ObjectMapper().valueToTree(event).path("type").asText())));
            verify(clients,never()).broadcast(any());
            when(approvals.selectRemote(1L,"mcp-approval")).thenReturn(saved.getValue());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        var resolved=new AgentProtocolEnvelope();resolved.setType("APPROVAL_RESOLVED");resolved.setMessageId("resolved");
        resolved.setTimestamp(10L);resolved.setPayload(new ObjectMapper().createObjectNode().put("requestId","mcp-approval").put("decision","ACCEPT"));
        assertTrue(service.process(1L,resolved));
        verify(conversations).resumeTurn(7L,4L);
        assertEquals(4L,resolved.getPayload().path("conversationId").asLong());
        assertEquals(7L,resolved.getPayload().path("turnId").asLong());
        ((com.fasterxml.jackson.databind.node.ObjectNode)resolved.getPayload()).put("decisionMessageId","attempt");
        when(approvals.acknowledgeDispatch(any(),eq("attempt"))).thenReturn(0);
        service.process(1L,resolved);
        verify(conversations,times(1)).resumeTurn(7L,4L);
        ((com.fasterxml.jackson.databind.node.ObjectNode)resolved.getPayload()).remove("decisionMessageId");
        when(approvals.pendingForTurn(7L)).thenReturn(1);
        service.process(1L,resolved);
        verify(conversations,times(1)).resumeTurn(7L,4L);
        var terminal=new AgentProtocolEnvelope();terminal.setType("TURN_INTERRUPTED");terminal.setMessageId("interrupted");
        terminal.setTimestamp(10L);terminal.setPayload(new ObjectMapper().createObjectNode().put("conversationId","4").put("turnId","7"));
        assertTrue(service.process(1L,terminal));
        verify(approvals).cancelTurn(eq(7L),eq(1L),any());
    }

    @ParameterizedTest
    @CsvSource({"ACCEPT,APPROVED","DECLINE,REJECTED","CANCEL,CANCELLED"})
    void decisionIsSavedAndRoutedToTheOwningAgent(String decision,String status) {
        var mapper=mock(ApprovalMapper.class);var gateway=mock(AgentCommandGateway.class);
        var access=mock(AuthorizationService.class);
        var service=new ApprovalServiceImpl(mapper,gateway,access);
        var approval=new ApprovalRequestPO();approval.setId(11L);approval.setDeviceId(1L);
        approval.setDeviceCode("test-device");approval.setRemoteRequestId("mcp-approval");
        when(mapper.selectOwnedById(11L,9L)).thenReturn(approval);
        when(gateway.isOnline("test-device")).thenReturn(true);
        when(mapper.decide(eq(11L),eq(status),eq(9L),any())).thenReturn(1);
        when(mapper.recordDispatch(eq(11L),anyString())).thenReturn(1);
        var dto=new ApprovalDecisionDTO();dto.setDecision(decision);
        service.decide(11L,dto,9L);
        verify(access).requirePermission(9L,"approval:decide");verify(access).requireDevice(9L,1L);
        var command=ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway).send(eq("test-device"),command.capture());
        assertEquals("RESOLVE_APPROVAL",command.getValue().getType());
        var body=new ObjectMapper().valueToTree(command.getValue().getPayload());
        assertEquals("mcp-approval",body.path("requestId").asText());assertEquals(decision,body.path("decision").asText());
        when(mapper.decide(eq(11L),eq(status),eq(9L),any())).thenReturn(0);
        assertThrows(BusinessException.class,()->service.decide(11L,dto,9L));
        verify(gateway,times(1)).send(anyString(),any());
    }

    @Test void evenAdministratorsCannotApproveForSession() {
        var mapper=mock(ApprovalMapper.class);var gateway=mock(AgentCommandGateway.class);var access=mock(AuthorizationService.class);
        var approval=new ApprovalRequestPO();approval.setDeviceId(1L);
        when(mapper.selectOwnedById(11L,9L)).thenReturn(approval);
        when(access.hasPermission(9L,"device:manage")).thenReturn(true);
        var dto=new ApprovalDecisionDTO();dto.setDecision("ACCEPT_FOR_SESSION");
        assertThrows(BusinessException.class,()->new ApprovalServiceImpl(mapper,gateway,access).decide(11L,dto,9L));
        verify(mapper,never()).decide(any(),any(),any(),any());verifyNoInteractions(gateway);
    }
}
