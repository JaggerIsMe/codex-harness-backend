package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.OrchestrationProperties;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import com.myharness.codex.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class OrchestrationServiceTest {
    OrchestrationMapper mapper;ProjectMapper projects;ConversationMapper turns;ConversationService conversations;
    ExpertService experts;AuthorizationService access;AgentCommandGateway gateway;OrchestrationProperties settings;
    OrchestrationService service;OrchestrationExecutionPO execution;OrchestrationStepPO step;ConversationTurnPO turn;
    @BeforeEach void setup() throws Exception {
        mapper=mock(OrchestrationMapper.class);projects=mock(ProjectMapper.class);turns=mock(ConversationMapper.class);
        conversations=mock(ConversationService.class);experts=mock(ExpertService.class);access=mock(AuthorizationService.class);gateway=mock(AgentCommandGateway.class);
        settings=new OrchestrationProperties();settings.setEnabled(true);
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        doAnswer(i->{((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)i.getArgument(0)).accept(mock(org.springframework.transaction.TransactionStatus.class));return null;})
            .when(tx).executeWithoutResult(any());
        service=new OrchestrationService(mapper,projects,turns,conversations,experts,access,gateway,settings,tx,new ObjectMapper(),mock(ClientEventWebSocketHandler.class));
        execution=new OrchestrationExecutionPO();execution.setId(1L);execution.setProjectId(2L);execution.setUserId(3L);execution.setDeviceId(4L);
        execution.setTitle("演示");execution.setGoal("实现并验证");execution.setStatus("RUNNING");execution.setCreatedAt(LocalDateTime.now());
        step=new OrchestrationStepPO();step.setId(5L);step.setExecutionId(1L);step.setPosition(0);step.setName("分析");step.setObjective("分析目标");step.setExpertId(8L);step.setStatus("PENDING");
        execution.setPlanJson(new ObjectMapper().writeValueAsString(new WorkflowDTO(2,"a",List.of(new WorkflowDTO.Node("a","EXPERT","用户节点",8L,"用户职责\n{{goal}}",null,null,0,0)))));
        when(mapper.get(1L)).thenReturn(execution);when(mapper.scheduled()).thenAnswer(i->List.of(execution));
        when(mapper.steps(1L)).thenAnswer(i->List.of(step));when(mapper.step(5L)).thenReturn(step);
        when(mapper.status(anyLong(),anyString(),nullable(String.class))).thenAnswer(i->{
            execution.setStatus(i.getArgument(1));execution.setFailureMessage(i.getArgument(2));
            if("CANCELING".equals(execution.getStatus()))execution.setCancelRequested(true);return 1;});
        when(mapper.stepStatus(anyLong(),anyString(),nullable(String.class))).thenAnswer(i->{step.setStatus(i.getArgument(1));step.setFailureMessage(i.getArgument(2));return 1;});
        when(mapper.claim(anyLong(),anyString(),anyString(),nullable(String.class))).thenAnswer(i->{
            if(!step.getStatus().equals(i.getArgument(1)))return 0;step.setStatus(i.getArgument(2));step.setInputSnapshot(i.getArgument(3));return 1;});
        when(mapper.result(anyLong(),anyString())).thenAnswer(i->{step.setStatus("SUCCEEDED");step.setResultJson(i.getArgument(1));return 1;});
        var p=new ProjectPO();p.setId(2L);p.setDeviceId(4L);p.setStatus("ACTIVE");p.setWorkspaceStatus("ENABLED");p.setDeviceCode("device");
        when(projects.selectOwned(2L,3L)).thenReturn(p);when(gateway.isOnline("device")).thenReturn(true);
        turn=new ConversationTurnPO();turn.setId(7L);turn.setConversationId(6L);turn.setExpertVersionId(9L);turn.setStatus("RUNNING");
        when(turns.selectTurn(7L)).thenReturn(turn);
        var c=new ConversationPO();c.setId(6L);c.setDeviceCode("device");c.setStatus("ACTIVE");c.setCodexThreadId("thread");
        when(turns.selectConversation(6L)).thenReturn(c);
    }
    void running(){step.setConversationId(6L);step.setTurnId(7L);step.setStatus("RUNNING");}
    @Test void entryCompletesWithoutConversationThenDispatchesTheLinkedExpert() throws Exception {
        var start=new WorkflowDTO.Node("start","START","开始",null,"","a",null,0,0);
        var expert=new WorkflowDTO.Node("a","EXPERT","任务",8L,"用户职责",null,null,300,0);
        execution.setPlanJson(new ObjectMapper().writeValueAsString(new WorkflowDTO(4,"start",List.of(start,expert))));
        step.setExpertId(null);
        var next=new OrchestrationStepPO();next.setId(8L);next.setPosition(1);next.setStatus("PENDING");next.setExpertId(8L);next.setName("任务");
        when(mapper.steps(1L)).thenReturn(List.of(step,next));
        service.tick();assertEquals("SUCCEEDED",step.getStatus());assertTrue(step.getResultJson().contains("流程开始"));
        verifyNoInteractions(conversations);
        when(mapper.claim(eq(8L),eq("PENDING"),eq("CREATING"),isNull())).thenReturn(1);
        service.tick();
        verify(conversations).createOrchestrationConversation(eq(2L),any(),eq(3L),eq(8L));
        verify(mapper,times(1)).result(eq(step.getId()),anyString());
    }
    @Test void queuesWithoutCallingDeviceWhenCapacityIsFull() {
        when(mapper.activeDeviceTurns(4L)).thenReturn(4);service.tick();verifyNoInteractions(conversations);assertEquals("PENDING",step.getStatus());
    }
    @Test void projectReservationPreventsAnotherExecutionStarting() {
        execution.setStatus("QUEUED");when(mapper.otherActive(2L,1L)).thenReturn(1);service.tick();
        verifyNoInteractions(conversations);assertEquals("QUEUED",execution.getStatus());
    }
    @Test void pendingClaimIsPersistedBeforeCreatingConversation() {
        when(conversations.createOrchestrationConversation(eq(2L),any(),eq(3L),eq(5L))).thenAnswer(i->{
            assertEquals("CREATING",step.getStatus());return null;});
        service.tick();verify(conversations).createOrchestrationConversation(eq(2L),any(),eq(3L),eq(5L));
    }
    @Test void crashAfterClaimDoesNotReplayCreationOrTurn() {
        step.setStatus("CREATING");service.tick();assertEquals("NEEDS_ATTENTION",execution.getStatus());verifyNoInteractions(conversations);
    }
    @Test void waitingApprovalRetainsExecutionWithoutStartingDependents() {
        running();turn.setStatus("WAITING_APPROVAL");service.tick();
        assertEquals("WAITING_APPROVAL",step.getStatus());assertEquals("RUNNING",execution.getStatus());verifyNoInteractions(conversations);
    }
    @Test void persistedCompletionWithoutDeviceReceiptDoesNotAdvance() {
        running();turn.setStatus("COMPLETED");service.tick();assertEquals("NEEDS_ATTENTION",execution.getStatus());verifyNoInteractions(conversations);
    }
    @Test void receiptAndCompleteMessageProduceTraceableResult() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        var message=message(10L,1L,"交付完成，尚有待办");
        when(mapper.messages(7L)).thenReturn(List.of(message));service.tick();service.tick();
        assertEquals("SUCCEEDED",execution.getStatus());assertTrue(step.getResultJson().contains("sourceTurnId"));assertTrue(step.getResultJson().contains("尚有待办"));
    }
    @Test void incompleteOutputFailsHandoffEvenWhenTurnCompleted() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        var message=message(10L,1L,"partial");message.setStatus("INCOMPLETE");when(mapper.messages(7L)).thenReturn(List.of(message));
        service.tick();assertEquals("FAILED",execution.getStatus());
    }
    @Test void completedTurnWithInvalidSchemaOutputNeverCommitsResultOrDispatchesNext() throws Exception {
        var json=new ObjectMapper();
        var node=new WorkflowDTO.Node("a","EXPERT","检查",8L,"用户职责",null,null,0,0,null,
            json.readTree("{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"}},\"required\":[\"approved\"]}"),null,null);
        execution.setPlanJson(json.writeValueAsString(new WorkflowDTO(3,"a",List.of(node))));
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"{\"approved\":\"true\"}")));
        service.tick();service.tick();assertEquals("FAILED",execution.getStatus());
        assertTrue(step.getFailureMessage().contains("Schema"));verify(mapper,never()).result(anyLong(),anyString());verifyNoInteractions(conversations);
    }
    @Test void cancelledIntentSurvivesUnknownStateAndLateSuccess() {
        running();execution.setStatus("NEEDS_ATTENTION");execution.setCancelRequested(true);turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        service.tick();assertEquals("CANCELLED",execution.getStatus());verify(mapper,never()).result(anyLong(),anyString());
    }
    @Test void cancelOnlyRequestsInterruptUntilReceiptArrives() {
        running();service.cancel(2L,1L,3L);
        assertEquals("CANCELING",execution.getStatus());verify(gateway).send(eq("device"),any(AgentCommand.class));
        assertNotEquals("CANCELLED",step.getStatus());
        step.setTerminalStatus("INTERRUPTED");turn.setStatus("INTERRUPTED");service.tick();assertEquals("CANCELLED",execution.getStatus());
    }
    @Test void queuedExecutionCanCancelWithoutDispatch() {
        execution.setStatus("QUEUED");service.cancel(2L,1L,3L);assertEquals("CANCELLED",execution.getStatus());verifyNoInteractions(conversations);
    }
    @Test void failedStepCannotBeSkippedAfterServerRestart() {
        step.setStatus("FAILED");service.tick();assertEquals("FAILED",execution.getStatus());verifyNoInteractions(conversations);
    }
    @Test void dispatchInputIsFrozenAndUsesStableRequestKey() {
        step.setStatus("WAITING_THREAD");step.setConversationId(6L);
        service.tick();
        verify(conversations).startOrchestrationTurn(eq(2L),eq(6L),argThat(d->"orchestration-5-1".equals(d.getClientRequestId()) && d.getMessage().equals("用户职责\n实现并验证")),eq(3L),eq(5L));
        assertNotNull(step.getInputSnapshot());
    }
    @Test void otherUserCannotReadOrCancelExecution() {
        var p=new ProjectPO();p.setStatus("ACTIVE");p.setWorkspaceStatus("ENABLED");p.setDeviceId(4L);when(projects.selectOwned(2L,99L)).thenReturn(p);
        assertThrows(BusinessException.class,()->service.get(2L,1L,99L));assertThrows(BusinessException.class,()->service.cancel(2L,1L,99L));
        verify(gateway,never()).send(anyString(),any());
    }
    @Test void disabledFeatureDoesNotQueryUnmigratedTables() {
        settings.setEnabled(false);service.tick();verify(mapper,never()).scheduled();
    }
    @Test void oldPlanNeverDispatchesNewWorkButRemainsCancelable() {
        execution.setPlanJson("{\"schemaVersion\":1}");service.tick();
        assertEquals("NEEDS_ATTENTION",execution.getStatus());verifyNoInteractions(conversations);
        service.cancel(2L,1L,3L);assertEquals("CANCELLED",execution.getStatus());
    }
    @Test void savesBranchChoiceAndSkipsOnlyTheUnselectedPath() throws Exception {
        var nodes=List.of(new WorkflowDTO.Node("a","EXPERT","检查",8L,"用户原文","b",null,0,0),
            new WorkflowDTO.Node("b","BRANCH","分支",null,"",null,new WorkflowDTO.Condition("a","EQUALS","","yes","yes","no"),300,0),
            new WorkflowDTO.Node("yes","EXPERT","满足",8L,"已选",null,null,600,0),new WorkflowDTO.Node("no","EXPERT","不满足",8L,"不执行",null,null,600,250));
        execution.setPlanJson(new ObjectMapper().writeValueAsString(new WorkflowDTO(2,"a",nodes)));
        step.setStatus("SUCCEEDED");step.setResultJson(new ObjectMapper().writeValueAsString(new OrchestrationStepResultVO(1,"yes",List.of(1L),7L,8L,false)));
        var all=new ArrayList<OrchestrationStepPO>();all.add(step);
        for(int i=1;i<4;i++){var s=new OrchestrationStepPO();s.setId(5L+i);s.setPosition(i);s.setExecutionId(1L);s.setName(nodes.get(i).name());s.setExpertId(nodes.get(i).expertId());s.setStatus("PENDING");all.add(s);}
        when(mapper.steps(1L)).thenReturn(all);
        when(mapper.step(anyLong())).thenAnswer(i->all.stream().filter(s->s.getId().equals(i.getArgument(0))).findFirst().orElseThrow());
        doAnswer(i->{var s=all.get(((Long)i.getArgument(0)).intValue()-5);s.setStatus("SUCCEEDED");s.setResultJson(i.getArgument(1));return 1;}).when(mapper).result(anyLong(),anyString());
        doAnswer(i->{all.get(((Long)i.getArgument(0)).intValue()-5).setStatus(i.getArgument(1));return 1;}).when(mapper).stepStatus(anyLong(),anyString(),nullable(String.class));
        doAnswer(i->{all.get(((Long)i.getArgument(0)).intValue()-5).setStatus(i.getArgument(2));return 1;}).when(mapper).claim(anyLong(),anyString(),anyString(),nullable(String.class));
        service.tick();assertEquals("SUCCEEDED",all.get(1).getStatus());assertEquals("SKIPPED",all.get(3).getStatus());verifyNoInteractions(conversations);
        // Simulate a crash before skipped-state persistence; replay must repair it, not execute that path.
        all.get(3).setStatus("PENDING");service.tick();assertEquals("SKIPPED",all.get(3).getStatus());
        verify(conversations).createOrchestrationConversation(eq(2L),argThat(d->d.getTitle().endsWith("满足")),eq(3L),eq(7L));
        verify(mapper,times(1)).result(eq(6L),anyString());
    }
    @Test void createPersistsUserGraphAndIdempotencyBeforeAnyDeviceCall() {
        var definition=new WorkflowDTO(2,"custom",List.of(new WorkflowDTO.Node("custom","EXPERT","自定义职责",8L,"  精确用户文字  ",null,null,35,90)));
        when(experts.projectExperts(2L,3L)).thenReturn(new ProjectExpertsVO(1L,List.of(new ProjectExpertVO(8L,1L,1L,1L,1L,false,"专家","",true,null))));
        when(mapper.insert(any())).thenAnswer(i->{execution=i.getArgument(0);execution.setId(1L);execution.setStatus("QUEUED");return 1;});
        when(mapper.get(1L)).thenAnswer(i->execution);
        var dto=new CreateOrchestrationDTO("流程","  用户目标  ","stable-key",definition);
        service.create(2L,dto,3L);assertEquals("  用户目标  ",execution.getGoal());assertTrue(execution.getPlanJson().contains("精确用户文字"));
        verify(mapper).insertStep(argThat(s->s.getExpertId().equals(8L) && s.getObjective().equals("  精确用户文字  ")));
        when(mapper.byRequest(3L,"stable-key")).thenAnswer(i->execution);service.create(2L,dto,3L);verify(mapper,times(1)).insert(any());
        assertThrows(BusinessException.class,()->service.create(2L,new CreateOrchestrationDTO("不同内容","目标","stable-key",definition),3L));
        verifyNoInteractions(conversations);verify(gateway,never()).send(anyString(),any());
    }
    @Test void rejectsUnavailableExpertAndInvalidGraphBeforePersisting() {
        var definition=new WorkflowDTO(2,"a",List.of(new WorkflowDTO.Node("a","EXPERT","自定义",99L,"原文",null,null,0,0)));
        when(experts.projectExperts(2L,3L)).thenReturn(new ProjectExpertsVO(1L,List.of()));
        assertThrows(BusinessException.class,()->service.create(2L,new CreateOrchestrationDTO("流程","目标","key",definition),3L));
        assertThrows(BusinessException.class,()->service.create(2L,new CreateOrchestrationDTO("流程","目标","key",new WorkflowDTO(2,"a",List.of())),3L));
        verify(mapper,never()).insert(any());verifyNoInteractions(conversations);
    }
    @Test void manualResolutionRequiresExplicitConfirmationAndNoActiveTurn() {
        running();execution.setStatus("NEEDS_ATTENTION");step.setStatus("NEEDS_ATTENTION");
        assertThrows(BusinessException.class,()->service.acknowledgeStopped(2L,1L,3L,false));
        when(mapper.activeProjectTurns(2L)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.acknowledgeStopped(2L,1L,3L,true));
        when(mapper.activeProjectTurns(2L)).thenReturn(0);
        service.acknowledgeStopped(2L,1L,3L,true);
        assertEquals("FAILED",execution.getStatus());assertTrue(execution.getFailureMessage().contains("人工核实"));
        verifyNoInteractions(conversations);verify(gateway,never()).send(anyString(),any());
    }
    @Test void resultSelectsLatestTextRegardlessOfQueryOrder() {
        var result=OrchestrationResults.result(turn,List.of(message(12L,3L,"最终结果"),message(11L,1L,"较早文本")));
        assertEquals("最终结果",result.summary());assertEquals(List.of(12L),result.sourceMessageIds());
    }
    @Test void earlierCompletedTextCannotHideIncompleteFinalMessage() {
        var latest=message(12L,3L,"incomplete");latest.setStatus("INCOMPLETE");
        assertThrows(IllegalArgumentException.class,()->OrchestrationResults.result(turn,List.of(latest,message(11L,1L,"earlier"))));
    }
    private ConversationMessagePO message(Long id,Long seq,String content) {
        var m=new ConversationMessagePO();m.setId(id);m.setSequenceNo(seq);m.setContent(content);m.setMessageType("TEXT");m.setStatus("COMPLETED");return m;
    }
}
