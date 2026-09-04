package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentEventServiceImplTest {
    private final com.myharness.codex.service.stream.ConversationMessageStream streams=mock(com.myharness.codex.service.stream.ConversationMessageStream.class);
    private final org.springframework.transaction.support.TransactionTemplate transactions=new org.springframework.transaction.support.TransactionTemplate() {
        @Override public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        }
    };
    @Test void sendsConversationEventsOnlyToTheirOwner() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        ConversationMapper conversations=mock(ConversationMapper.class);
        ApprovalMapper approvals=mock(ApprovalMapper.class);
        SkillMapper skills=mock(SkillMapper.class);
        ClientEventWebSocketHandler clients=mock(ClientEventWebSocketHandler.class);
        when(devices.insertEvent(3L,"3d0fd4b4-7f1f-4f87-8947-ef23dbe0689d","TURN_COMPLETED",10L)).thenReturn(1);
        ConversationPO conversation=new ConversationPO(); conversation.setId(5L); conversation.setDeviceId(3L); conversation.setUserId(9L);
        when(conversations.selectConversation(5L)).thenReturn(conversation);
        ObjectMapper mapper=new ObjectMapper();
        AgentProtocolEnvelope envelope=new AgentProtocolEnvelope();
        envelope.setMessageId("3d0fd4b4-7f1f-4f87-8947-ef23dbe0689d"); envelope.setType("TURN_COMPLETED"); envelope.setTimestamp(10L);
        envelope.setPayload(mapper.createObjectNode().put("conversationId","5").put("turnId","7"));
        AgentEventServiceImpl service=new AgentEventServiceImpl(devices,conversations,approvals,skills,clients,streams,transactions);

        assertTrue(service.process(3L,envelope));

        verify(clients).sendToUser(eq(9L),any());
        verify(clients,never()).broadcast(any());
    }

    @Test void sendsHighFrequencyDeltasThroughStreamModuleWithoutDatabaseAudit() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        ConversationMapper conversations=mock(ConversationMapper.class);
        ApprovalMapper approvals=mock(ApprovalMapper.class);
        SkillMapper skills=mock(SkillMapper.class);
        ClientEventWebSocketHandler clients=mock(ClientEventWebSocketHandler.class);
        AgentProtocolEnvelope envelope=new AgentProtocolEnvelope();
        envelope.setType("TURN_EVENT");
        envelope.setPayload(new ObjectMapper().createObjectNode().put("eventSeq",1));
        when(streams.accept(3L,envelope.getPayload())).thenReturn(true);
        AgentEventServiceImpl service=new AgentEventServiceImpl(devices,conversations,approvals,skills,clients,streams,transactions);
        assertTrue(service.process(3L,envelope));
        verify(streams).accept(3L,envelope.getPayload());
        verifyNoInteractions(devices,conversations,approvals,skills,clients);
    }

    @Test void replacesWorkspaceAvailabilityFromCompleteAgentSnapshot() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        ConversationMapper conversations=mock(ConversationMapper.class);
        ApprovalMapper approvals=mock(ApprovalMapper.class);
        SkillMapper skills=mock(SkillMapper.class);
        ClientEventWebSocketHandler clients=mock(ClientEventWebSocketHandler.class);
        when(devices.insertEvent(3L,"8ad73b79-4ff6-4b56-b0d2-434be6a42112","WORKSPACES_CHANGED",10L)).thenReturn(1);
        ObjectMapper mapper=new ObjectMapper();
        AgentProtocolEnvelope envelope=new AgentProtocolEnvelope();
        envelope.setMessageId("8ad73b79-4ff6-4b56-b0d2-434be6a42112");
        envelope.setType("WORKSPACES_CHANGED"); envelope.setTimestamp(10L);
        envelope.setPayload(mapper.createArrayNode().add(mapper.createObjectNode()
                .put("name","default-harness").put("rootPath","D:/projects/default-harness")));
        AgentEventServiceImpl service=new AgentEventServiceImpl(devices,conversations,approvals,skills,clients,streams,transactions);

        assertTrue(service.process(3L,envelope));
        InOrder order=inOrder(devices);
        order.verify(devices).markReportedWorkspacesMissing(3L);
        order.verify(devices).upsertWorkspace(argThat((AgentWorkspacePO value) -> "default-harness".equals(value.getWorkspaceName())
                && "D:/projects/default-harness".equals(value.getRootPath())));
    }

    @Test void duplicateMessageIsAuditedButNotProcessedAgain() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        ConversationMapper conversations=mock(ConversationMapper.class);
        ApprovalMapper approvals=mock(ApprovalMapper.class);
        SkillMapper skills=mock(SkillMapper.class);
        ClientEventWebSocketHandler clients=mock(ClientEventWebSocketHandler.class);
        when(devices.insertEvent(3L,"8ad73b79-4ff6-4b56-b0d2-434be6a42112","REGISTER",10L)).thenReturn(2);
        AgentProtocolEnvelope envelope=new AgentProtocolEnvelope(); envelope.setMessageId("8ad73b79-4ff6-4b56-b0d2-434be6a42112");
        envelope.setType("REGISTER"); envelope.setTimestamp(10L); envelope.setPayload(new ObjectMapper().createObjectNode());
        AgentEventServiceImpl service=new AgentEventServiceImpl(devices,conversations,approvals,skills,clients,streams,transactions);
        assertFalse(service.process(3L,envelope));
        verify(devices,never()).heartbeat(anyLong(),any());
        verifyNoInteractions(conversations,approvals,skills,clients,streams);
    }

    @Test void completesWorkspaceCreationFromCorrelatedResult() {
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        ConversationMapper conversations=mock(ConversationMapper.class);
        ApprovalMapper approvals=mock(ApprovalMapper.class);
        SkillMapper skills=mock(SkillMapper.class);
        ClientEventWebSocketHandler clients=mock(ClientEventWebSocketHandler.class);
        when(devices.insertEvent(3L,"8ad73b79-4ff6-4b56-b0d2-434be6a42112","WORKSPACE_CREATE_RESULT",10L)).thenReturn(1);
        when(devices.completeWorkspace(any())).thenReturn(1);
        ObjectMapper mapper=new ObjectMapper();
        AgentProtocolEnvelope envelope=new AgentProtocolEnvelope();
        envelope.setMessageId("8ad73b79-4ff6-4b56-b0d2-434be6a42112");
        envelope.setType("WORKSPACE_CREATE_RESULT"); envelope.setTimestamp(10L); envelope.setCorrelationId("11");
        envelope.setPayload(mapper.createObjectNode().put("requestId","11").put("workspaceName","order-service")
                .put("success",true).put("rootPath","D:/projects/order-service"));
        AgentEventServiceImpl service=new AgentEventServiceImpl(devices,conversations,approvals,skills,clients,streams,transactions);

        assertTrue(service.process(3L,envelope));
        verify(devices).completeWorkspace(argThat(value -> value.getId().equals(11L)
                && "D:/projects/order-service".equals(value.getRootPath())));
    }
}
