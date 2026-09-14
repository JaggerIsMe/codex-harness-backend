package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.OrchestrationProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.*;
import com.myharness.codex.service.*;
import com.myharness.codex.service.stream.ConversationMessageStream;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class OrchestrationDispatchTest {
    ConversationMapper mapper=mock(ConversationMapper.class);
    OrchestrationMapper orchestration=mock(OrchestrationMapper.class);
    AgentCommandGateway gateway=mock(AgentCommandGateway.class);
    ConversationServiceImpl service;
    boolean inTransaction;
    @BeforeEach void setup() {
        var node=new OrchestrationStepPO();node.setId(5L);node.setExecutionId(1L);node.setPosition(0);
        when(orchestration.step(5L)).thenReturn(node);
        var execution=new OrchestrationExecutionPO();execution.setPlanJson("{\"schemaVersion\":2,\"startNodeId\":\"a\",\"nodes\":[{\"id\":\"a\",\"kind\":\"EXPERT\"}]}");
        when(orchestration.get(1L)).thenReturn(execution);
        var projects=mock(ProjectMapper.class);var devices=mock(AgentDeviceMapper.class);
        var experts=mock(ExpertService.class);var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i->{inTransaction=true;
            try{return ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));}
            finally{inTransaction=false;}});
        service=new ConversationServiceImpl(mapper,devices,gateway,tx,mock(ApprovalMapper.class),new ObjectMapper(),projects,
            mock(ConversationMessageStream.class),mock(AuthorizationService.class),mock(ConversationAttachmentService.class),experts);
        var settings=new OrchestrationProperties();settings.setEnabled(true);service.setOrchestration(orchestration,settings);
        var project=new ProjectPO();project.setId(2L);project.setUserId(3L);project.setDeviceId(4L);project.setWorkspaceId(9L);
        project.setStatus("ACTIVE");project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/project");when(projects.selectOwned(2L,3L)).thenReturn(project);
        var device=new AgentDevicePO();device.setId(4L);device.setStatus("ONLINE");device.setIsolationMode("WINDOWS_LPAC_V1");device.setDeviceCode("device");
        when(devices.selectById(4L)).thenReturn(device);when(gateway.isOnline("device")).thenReturn(true);
        var workspace=new AgentWorkspacePO();workspace.setId(9L);workspace.setStatus("ENABLED");workspace.setWorkspaceName("workspace");when(devices.selectWorkspace(9L,4L)).thenReturn(workspace);
        var conversation=new ConversationPO();conversation.setId(6L);conversation.setUserId(3L);conversation.setProjectId(2L);
        conversation.setDeviceId(4L);conversation.setDeviceCode("device");conversation.setWorkspaceName("workspace");conversation.setStatus("ACTIVE");conversation.setCodexThreadId("thread");
        when(mapper.selectOwnedConversation(2L,6L,3L)).thenReturn(conversation);when(mapper.lockConversation(6L)).thenReturn(conversation);
        when(mapper.selectConversation(6L)).thenReturn(conversation);
        when(mapper.insertConversation(any())).thenAnswer(i->{((ConversationPO)i.getArgument(0)).setId(6L);return 1;});
        when(experts.freeze(any(),any())).thenReturn(new ExpertRuntimeDTO());when(experts.write(any())).thenReturn("{}");
        when(mapper.insertTurn(any())).thenAnswer(i->{var turn=(ConversationTurnPO)i.getArgument(0);turn.setId(7L);when(mapper.selectTurn(7L)).thenReturn(turn);return 1;});
    }
    StartTurnDTO input(){var d=new StartTurnDTO();d.setMessage("test");d.setClientRequestId("orchestration-5-1");return d;}
    @Test void linksTurnInsideTransactionBeforeSendingAnyCommand() {
        when(orchestration.linkTurn(5L,7L,6L,2L,3L)).thenAnswer(i->{assertTrue(inTransaction);return 1;});
        when(gateway.send(anyString(),any())).thenAnswer(i->{assertFalse(inTransaction);return "command";});
        service.startOrchestrationTurn(2L,6L,input(),3L,5L);
        var ordered=inOrder(mapper,orchestration,gateway);
        ordered.verify(mapper).insertTurn(any());ordered.verify(orchestration).linkTurn(5L,7L,6L,2L,3L);ordered.verify(gateway).send(eq("device"),any());
    }
    @Test void failedStepLinkPreventsTurnCommandDispatch() {
        when(orchestration.linkTurn(5L,7L,6L,2L,3L)).thenReturn(0);
        assertThrows(BusinessException.class,()->service.startOrchestrationTurn(2L,6L,input(),3L,5L));verify(gateway,never()).send(anyString(),any());
    }
    @Test void linksConversationInCreationTransactionBeforeStartThread() {
        when(orchestration.linkConversation(5L,6L,2L,3L)).thenAnswer(i->{assertTrue(inTransaction);return 1;});
        var dto=new CreateConversationDTO();dto.setExpertId(8L);
        service.createOrchestrationConversation(2L,dto,3L,5L);
        var ordered=inOrder(mapper,orchestration,gateway);
        ordered.verify(mapper).insertConversation(any());ordered.verify(orchestration).linkConversation(5L,6L,2L,3L);ordered.verify(gateway).send(eq("device"),any());
    }
    @Test void ordinaryConversationCannotInjectTurnIntoReservedProject() {
        when(orchestration.reservedProject(2L)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.startTurn(2L,6L,input(),3L));verify(mapper,never()).insertTurn(any());
    }
    @Test void existingProjectTurnBlocksAnotherWriter() {
        when(mapper.countActiveProjectTurns(2L)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.startTurn(2L,6L,input(),3L));verify(gateway,never()).send(anyString(),any());
    }
    @Test void managedConversationCannotAcceptManualMessagesAfterProjectReservationEnds() {
        when(orchestration.managedConversation(6L)).thenReturn(1);
        when(orchestration.reservedProject(2L)).thenReturn(0);
        var error=assertThrows(BusinessException.class,()->service.startTurn(2L,6L,input(),3L));
        assertTrue(error.getMessage().contains("仅供查看"));
        verify(mapper,never()).insertTurn(any());verify(gateway,never()).send(anyString(),any());
    }
    @Test void ordinaryListUsesFilteredCountAndPaginationButDirectReadRetainsStepOwnership() {
        var ordinary=new ConversationPO();ordinary.setId(10L);
        when(orchestration.countOrdinaryConversations(2L,3L,"report")).thenReturn(12L);
        when(orchestration.ordinaryConversations(2L,3L,"report",10,10L)).thenReturn(java.util.List.of(ordinary));
        var page=service.getProjectConversations(2L,3L,2,10,"report");
        assertEquals(12,page.total());assertEquals(10L,page.items().get(0).getId());
        verify(mapper,never()).countProjectConversations(any(),any(),any());
        when(orchestration.managedConversation(6L)).thenReturn(1);
        assertTrue(service.getConversation(2L,6L,3L).isOrchestrationManaged());
    }
    @Test void statusBatchKeepsOwnedStepIdsAndMarksThemInsteadOfFailingOrdinarySidebarSync() {
        var step=mapper.selectOwnedConversation(2L,6L,3L);
        var ordinary=new ConversationPO();ordinary.setId(10L);
        when(mapper.selectConversationStatuses(2L,3L,java.util.List.of(6L,10L))).thenReturn(java.util.List.of(step,ordinary));
        when(orchestration.managedConversations(java.util.List.of(6L,10L))).thenReturn(java.util.List.of(6L));
        var values=service.getConversationStatuses(2L,3L,java.util.List.of(6L,10L));
        assertEquals(2,values.size());assertTrue(values.get(0).isOrchestrationManaged());assertFalse(values.get(1).isOrchestrationManaged());
    }
    @Test void featureDisabledKeepsOrdinaryQueriesIndependentOfOrchestrationTables() {
        service.setOrchestration(orchestration,new OrchestrationProperties());
        when(mapper.selectProjectConversations(2L,3L,"",10,0L)).thenReturn(java.util.List.of());
        service.getProjectConversations(2L,3L,1,10,"");
        assertFalse(service.getConversation(2L,6L,3L).isOrchestrationManaged());
        verifyNoInteractions(orchestration);
    }
}
