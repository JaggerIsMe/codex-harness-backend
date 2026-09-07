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
    @Test void everyTerminalOutcomeRequestsProjectSyncAfterPersistence() {
        for(String type:java.util.List.of("TURN_COMPLETED","TURN_FAILED","TURN_INTERRUPTED")) {
            var devices=mock(AgentDeviceMapper.class);var conversations=mock(ConversationMapper.class);
            var files=mock(com.myharness.codex.service.WorkspaceFileService.class);
            var service=new AgentEventServiceImpl(devices,conversations,mock(ApprovalMapper.class),mock(SkillMapper.class),mock(ClientEventWebSocketHandler.class),streams,transactions);
            service.setWorkspaceFiles(files);
            var c=new ConversationPO();c.setId(5L);c.setDeviceId(3L);c.setUserId(9L);c.setProjectId(2L);
            when(conversations.selectConversation(5L)).thenReturn(c);
            var e=new AgentProtocolEnvelope();e.setType(type);e.setMessageId(type);e.setTimestamp(10L);
            e.setPayload(new ObjectMapper().createObjectNode().put("conversationId","5").put("turnId","7"));
            when(devices.insertEvent(3L,type,type,10L)).thenReturn(1);
            assertTrue(service.process(3L,e));
            var order=inOrder(conversations,files);
            order.verify(conversations).finishTurn(eq(7L),eq(5L),eq(3L),anyString(),nullable(String.class),nullable(String.class),any());
            order.verify(files).refreshProject(2L,9L);
        }
    }
    @Test void compatibleRuntimeUpdateMustMatchItsFrozenPendingTurnAndThread() {
        var devices=mock(AgentDeviceMapper.class);var conversations=mock(ConversationMapper.class);var clients=mock(ClientEventWebSocketHandler.class);
        var service=new AgentEventServiceImpl(devices,conversations,mock(ApprovalMapper.class),mock(SkillMapper.class),clients,streams,transactions);
        var envelope=new AgentProtocolEnvelope();envelope.setType("EXPERT_RUNTIME_UPDATED");envelope.setMessageId("expert-update");envelope.setTimestamp(10L);
        envelope.setPayload(new ObjectMapper().createObjectNode().put("conversationId","4").put("turnId","15")
                .put("codexThreadId","thread").put("previousExpertRuntimeKey","a".repeat(64)).put("expertRuntimeKey","b".repeat(64)));
        when(devices.insertEvent(1L,"expert-update","EXPERT_RUNTIME_UPDATED",10L)).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->service.process(1L,envelope));
        when(conversations.updateCompatibleExpertRuntime(eq(4L),eq(1L),eq(15L),eq("thread"),eq("a".repeat(64)),eq("b".repeat(64)),any())).thenReturn(1);
        assertTrue(service.process(1L,envelope));
    }
    @Test void expertThreadReplacementMustMatchFrozenPendingTurnAndPreviousThread() {
        var devices=mock(AgentDeviceMapper.class);var conversations=mock(ConversationMapper.class);var clients=mock(ClientEventWebSocketHandler.class);
        var service=new AgentEventServiceImpl(devices,conversations,mock(ApprovalMapper.class),mock(SkillMapper.class),clients,streams,transactions);
        var envelope=new AgentProtocolEnvelope();envelope.setType("THREAD_STARTED");envelope.setMessageId("expert-reset");envelope.setTimestamp(10L);
        envelope.setPayload(new ObjectMapper().createObjectNode().put("conversationId","4").put("turnId","15")
                .put("previousCodexThreadId","old").put("codexThreadId","new").put("expertRuntimeKey","a".repeat(64)));
        when(devices.insertEvent(1L,"expert-reset","THREAD_STARTED",10L)).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->service.process(1L,envelope));
        verify(conversations,never()).replaceUnstartedThread(any(),any(),any(),any(),any(),any());
        verify(clients,never()).sendToUser(any(),any());
        when(conversations.replaceExpertThread(eq(4L),eq(1L),eq(15L),eq("old"),eq("new"),eq("a".repeat(64)),any())).thenReturn(1);
        assertTrue(service.process(1L,envelope));
    }
    @Test void replacementRequiresAtomicHistoryAndPreviousBindingCheck() {
        var devices=mock(AgentDeviceMapper.class);var conversations=mock(ConversationMapper.class);
        var clients=mock(ClientEventWebSocketHandler.class);
        var service=new AgentEventServiceImpl(devices,conversations,mock(ApprovalMapper.class),mock(SkillMapper.class),clients,streams,transactions);
        var envelope=new AgentProtocolEnvelope();envelope.setType("THREAD_STARTED");envelope.setMessageId("replace-1");envelope.setTimestamp(10L);
        envelope.setPayload(new ObjectMapper().createObjectNode().put("conversationId","4").put("turnId","15")
                .put("previousCodexThreadId","old").put("codexThreadId","replacement"));
        when(devices.insertEvent(1L,"replace-1","THREAD_STARTED",10L)).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->service.process(1L,envelope));
        verify(clients,never()).sendToUser(any(),any());
        verify(conversations,never()).setThreadStarted(any(),any(),any(),any());
        when(conversations.replaceUnstartedThread(eq(4L),eq(1L),eq(15L),eq("old"),eq("replacement"),any())).thenReturn(1);
        assertTrue(service.process(1L,envelope));
        verify(conversations,times(2)).replaceUnstartedThread(eq(4L),eq(1L),eq(15L),eq("old"),eq("replacement"),any());
    }
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
