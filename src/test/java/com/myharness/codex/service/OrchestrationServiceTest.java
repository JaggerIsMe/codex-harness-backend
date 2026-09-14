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
    ClientEventWebSocketHandler clientEvents;
    OrchestrationService service;OrchestrationExecutionPO execution;OrchestrationStepPO step;ConversationTurnPO turn;
    @BeforeEach void setup() throws Exception {
        mapper=mock(OrchestrationMapper.class);projects=mock(ProjectMapper.class);turns=mock(ConversationMapper.class);
        conversations=mock(ConversationService.class);experts=mock(ExpertService.class);access=mock(AuthorizationService.class);gateway=mock(AgentCommandGateway.class);
        settings=new OrchestrationProperties();settings.setEnabled(true);
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        doAnswer(i->{((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)i.getArgument(0)).accept(mock(org.springframework.transaction.TransactionStatus.class));return null;})
            .when(tx).executeWithoutResult(any());
        clientEvents=mock(ClientEventWebSocketHandler.class);
        service=new OrchestrationService(mapper,projects,turns,conversations,experts,access,gateway,settings,tx,new ObjectMapper(),clientEvents);
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
        when(mapper.observation(5L)).thenAnswer(i->new OrchestrationObservationPO(step.getTurnId(),step.getStatus(),step.getTerminalStatus(),
            step.getCheckpointJson(),turn.getStatus(),turn.getExpertVersionId(),turn.getFailureCode(),turn.getFailureMessage()));
        when(mapper.checkpoint(eq(7L),anyString())).thenAnswer(i->{step.setCheckpointJson(i.getArgument(1));return 1;});
        var c=new ConversationPO();c.setId(6L);c.setDeviceCode("device");c.setStatus("ACTIVE");c.setCodexThreadId("thread");
        when(turns.selectConversation(6L)).thenReturn(c);
    }
    void running(){step.setConversationId(6L);step.setTurnId(7L);step.setStatus("RUNNING");}
    void completeReceipt(String summary) {
        var receipt=new ObjectMapper().createObjectNode().put("protocol",1).put("state","COMPLETE")
            .put("summary",summary).put("unresolvedApproval",false);
        receipt.putArray("files");step.setCheckpointJson(receipt.toString());
    }
    @Test void ordinaryClarificationWithoutCompletionReceiptNeverAdvances() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"已查到 32 个店铺。请指定店铺名称和站点。")));
        service.tick();service.tick();
        assertEquals("NEEDS_ATTENTION",step.getStatus());
        verify(mapper,never()).result(anyLong(),anyString());verifyNoInteractions(conversations);
    }
    @Test void pendingApprovalOrUnansweredReportWinsOverCompletion() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");completeReceipt("已完成");
        when(mapper.pendingApprovals(7L)).thenReturn(1);service.tick();
        assertEquals("NEEDS_ATTENTION",step.getStatus());verify(mapper,never()).result(anyLong(),anyString());
    }
    @Test void onlyAnExplicitUserQuestionRequestsAnAnswer() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        step.setCheckpointJson("{\"protocol\":1,\"state\":\"WAITING_USER\",\"summary\":\"请提供目标代码仓库\",\"files\":[],\"unresolvedApproval\":false}");
        service.tick();service.tick();
        assertEquals("WAITING_USER",step.getStatus());assertEquals("请提供目标代码仓库",step.getFailureMessage());
        assertEquals("RUNNING",execution.getStatus());verifyNoInteractions(conversations);
        verify(clientEvents,times(1)).sendToUser(eq(3L),any());
    }
    @Test void committedObservationWinsOverAnOlderSchedulerStepSnapshot() {
        running();turn.setStatus("COMPLETED");completeReceipt("done");
        when(mapper.observation(5L)).thenReturn(new OrchestrationObservationPO(7L,"RUNNING","COMPLETED",step.getCheckpointJson(),"COMPLETED",9L,null,null));
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"done")));
        service.tick();assertEquals("SUCCEEDED",step.getStatus());assertEquals("RUNNING",execution.getStatus());
        verify(mapper,never()).status(eq(1L),eq("NEEDS_ATTENTION"),anyString());
    }
    @Test void newCurrentTurnCannotBeCompletedByAnOlderSchedulerSnapshot() {
        running();
        when(mapper.observation(5L)).thenReturn(new OrchestrationObservationPO(8L,"RUNNING",null,null,"RUNNING",9L,null,null));
        service.tick();verify(mapper,never()).result(anyLong(),anyString());verify(mapper,never()).stepStatus(anyLong(),anyString(),nullable(String.class));
    }
    @Test void rechecksDroppedOutcomeWithoutSendingAnotherMessageOrReplayingCompletedWork() {
        running();step.setStatus("WAITING_USER");step.setTerminalStatus("COMPLETED");turn.setStatus("COMPLETED");
        execution.setStatus("NEEDS_ATTENTION");
        step.setCheckpointJson("{\"protocol\":1,\"state\":\"WAITING_USER\",\"files\":[],\"unresolvedApproval\":false}");
        var activity=message(11L,2L,"{\"type\":\"dynamicToolCall\",\"tool\":\"harness_node_outcome\",\"status\":\"completed\",\"success\":true,\"arguments\":{\"state\":\"COMPLETE\",\"summary\":\"done\"}}");
        activity.setTurnId(7L);activity.setRole("ASSISTANT");activity.setMessageType("ACTIVITY");
        when(mapper.outcomeActivities(7L)).thenReturn(List.of(activity));
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"done")));
        service.recheckStep(2L,1L,5L,new RecheckOrchestrationStepDTO(7L),3L);
        service.recheckStep(2L,1L,5L,new RecheckOrchestrationStepDTO(7L),3L);
        assertEquals("SUCCEEDED",step.getStatus());assertEquals("RUNNING",execution.getStatus());
        verify(mapper,times(1)).result(eq(5L),anyString());verifyNoInteractions(conversations);verify(gateway,never()).send(anyString(),any());
        service.tick();assertEquals("SUCCEEDED",execution.getStatus());
    }
    @Test void recheckCannotBypassPendingApprovalsCancellationOrStaleTurns() {
        running();step.setStatus("NEEDS_ATTENTION");step.setTerminalStatus("COMPLETED");turn.setStatus("COMPLETED");completeReceipt("done");
        assertThrows(BusinessException.class,()->service.recheckStep(2L,1L,5L,new RecheckOrchestrationStepDTO(6L),3L));
        when(mapper.pendingApprovals(7L)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.recheckStep(2L,1L,5L,new RecheckOrchestrationStepDTO(7L),3L));
        when(mapper.pendingApprovals(7L)).thenReturn(0);execution.setCancelRequested(true);
        assertThrows(BusinessException.class,()->service.recheckStep(2L,1L,5L,new RecheckOrchestrationStepDTO(7L),3L));
        verify(mapper,never()).result(anyLong(),anyString());verifyNoInteractions(conversations);
    }
    @Test void completedExpertsAutomaticallyHandOffAndReachTheEndWithoutUserMessages() throws Exception {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");completeReceipt("source data");
        var nodes=List.of(new WorkflowDTO.Node("a","EXPERT","查询",8L,"query","b",null,0,0),
            new WorkflowDTO.Node("b","EXPERT","分析",10L,"analyse","end",null,300,0),
            new WorkflowDTO.Node("end","END","完成",null,"",null,null,600,0));
        execution.setPlanJson(new ObjectMapper().writeValueAsString(new WorkflowDTO(2,"a",nodes)));
        var next=new OrchestrationStepPO();next.setId(6L);next.setExecutionId(1L);next.setPosition(1);next.setName("分析");next.setExpertId(10L);next.setStatus("PENDING");
        var end=new OrchestrationStepPO();end.setId(8L);end.setExecutionId(1L);end.setPosition(2);end.setName("完成");end.setStatus("PENDING");
        when(mapper.steps(1L)).thenAnswer(i->List.of(step,next,end));when(mapper.step(6L)).thenReturn(next);
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"source data")));
        when(mapper.claim(eq(6L),anyString(),anyString(),nullable(String.class))).thenAnswer(i->{
            if(!next.getStatus().equals(i.getArgument(1)))return 0;next.setStatus(i.getArgument(2));return 1;});
        when(conversations.createOrchestrationConversation(eq(2L),any(),eq(3L),eq(6L))).thenAnswer(i->{next.setStatus("WAITING_THREAD");next.setConversationId(8L);return null;});
        var conversation=new ConversationPO();conversation.setStatus("ACTIVE");conversation.setCodexThreadId("second-thread");
        when(turns.selectConversation(8L)).thenReturn(conversation);
        when(conversations.startOrchestrationTurn(eq(2L),eq(8L),any(),eq(3L),eq(6L))).thenAnswer(i->{next.setStatus("RUNNING");next.setTurnId(9L);return null;});
        String receipt="{\"protocol\":1,\"state\":\"COMPLETE\",\"summary\":\"analysis done\",\"files\":[],\"unresolvedApproval\":false}";
        when(mapper.observation(6L)).thenReturn(new OrchestrationObservationPO(9L,"RUNNING","COMPLETED",receipt,"COMPLETED",11L,null,null));
        when(mapper.messages(9L)).thenReturn(List.of(message(12L,1L,"analysis done")));
        when(mapper.result(eq(6L),anyString())).thenAnswer(i->{next.setStatus("SUCCEEDED");next.setResultJson(i.getArgument(1));return 1;});
        when(mapper.result(eq(8L),anyString())).thenAnswer(i->{end.setStatus("SUCCEEDED");return 1;});
        for(int tick=0;tick<5;tick++)service.tick();
        assertEquals("SUCCEEDED",execution.getStatus());assertEquals("SUCCEEDED",next.getStatus());assertEquals("SUCCEEDED",end.getStatus());
        verify(conversations,times(1)).createOrchestrationConversation(eq(2L),any(),eq(3L),eq(6L));
        verify(conversations,times(1)).startOrchestrationTurn(eq(2L),eq(8L),argThat(input->"analyse".equals(input.getMessage())),eq(3L),eq(6L));
    }
    @Test void pausedNodeContinuesSameConversationWithExactUserMessageAndStableRequestId() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");step.setStatus("WAITING_USER");
        var input=new ContinueOrchestrationStepDTO(7L,"retry-key","只查询美国 Vantrue 主店");
        when(conversations.startOrchestrationTurn(eq(2L),eq(6L),any(),eq(3L),eq(5L))).thenAnswer(i->{
            StartTurnDTO sent=i.getArgument(2);assertEquals(input.message(),sent.getMessage());assertEquals("DISPATCHING",step.getStatus());
            var next=new ConversationTurnPO();next.setId(8L);next.setRequestHash(com.myharness.codex.security.SecureDigests.sha256(new ObjectMapper().writeValueAsString(sent)));
            when(turns.byClientRequest(6L,sent.getClientRequestId())).thenReturn(next);when(mapper.hasAttempt(5L,8L)).thenReturn(1);
            step.setTurnId(8L);step.setTerminalStatus(null);step.setStatus("RUNNING");return null;
        });
        service.continueStep(2L,1L,5L,input,3L);service.continueStep(2L,1L,5L,input,3L);
        verify(conversations,times(1)).startOrchestrationTurn(eq(2L),eq(6L),any(),eq(3L),eq(5L));
        assertThrows(BusinessException.class,()->service.continueStep(2L,1L,5L,new ContinueOrchestrationStepDTO(7L,"retry-key","其他店铺"),3L));
    }
    @Test void completionAndStaleRepliesCannotReopenOrRaceTheCurrentNode() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        for(String status:List.of("SUCCEEDED","RUNNING","WAITING_APPROVAL")) {
            step.setStatus(status);
            assertThrows(BusinessException.class,()->service.continueStep(2L,1L,5L,new ContinueOrchestrationStepDTO(7L,"key","继续"),3L));
        }
        step.setStatus("WAITING_USER");
        assertThrows(BusinessException.class,()->service.continueStep(2L,1L,5L,new ContinueOrchestrationStepDTO(99L,"key","继续"),3L));
        when(mapper.pendingApprovals(7L)).thenReturn(1);
        assertThrows(BusinessException.class,()->service.continueStep(2L,1L,5L,new ContinueOrchestrationStepDTO(7L,"key","继续"),3L));
        verifyNoInteractions(conversations);
    }
    @Test void humanWaitingDoesNotExpireAndCancellationDoesNotStartAnotherTurn() {
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");step.setStatus("WAITING_USER");
        execution.setCreatedAt(LocalDateTime.now().minusDays(2));service.tick();
        assertEquals("WAITING_USER",step.getStatus());service.cancel(2L,1L,3L);
        assertEquals("CANCELLED",execution.getStatus());verifyNoInteractions(conversations);
    }
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
    @Test void commandFailureWithoutTerminalKeepsUnderlyingReasonAndNeverAdvances() {
        running();turn.setStatus("FAILED");turn.setFailureCode("COMMAND_FAILED");
        turn.setFailureMessage("Codex method failed: thread/start: Invalid request: dynamic tools must use either canonical or legacy format consistently");
        service.tick();service.tick();
        assertEquals("NEEDS_ATTENTION",execution.getStatus());assertEquals("NEEDS_ATTENTION",step.getStatus());
        assertTrue(step.getFailureMessage().contains(turn.getFailureMessage()));
        assertTrue(execution.getFailureMessage().contains("COMMAND_FAILED"));
        assertNull(step.getTerminalStatus());verify(mapper,never()).result(anyLong(),anyString());verifyNoInteractions(conversations);
    }
    @Test void longCommandFailureFitsPersistedAttentionReason() {
        running();turn.setStatus("FAILED");turn.setFailureCode("COMMAND_FAILED");turn.setFailureMessage("x".repeat(2000));
        service.tick();
        assertEquals("NEEDS_ATTENTION",execution.getStatus());assertEquals(1000,step.getFailureMessage().length());
        verifyNoInteractions(conversations);
    }
    @Test void receiptAndCompleteMessageProduceTraceableResult() {
        completeReceipt("交付完成，尚有待办");
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        var message=message(10L,1L,"交付完成，尚有待办");
        when(mapper.messages(7L)).thenReturn(List.of(message));service.tick();service.tick();
        assertEquals("SUCCEEDED",execution.getStatus());assertTrue(step.getResultJson().contains("sourceTurnId"));assertTrue(step.getResultJson().contains("尚有待办"));
    }
    @Test void incompleteOutputFailsHandoffEvenWhenTurnCompleted() {
        completeReceipt("partial");
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        var message=message(10L,1L,"partial");message.setStatus("INCOMPLETE");when(mapper.messages(7L)).thenReturn(List.of(message));
        service.tick();assertEquals("VALIDATION_FAILED",step.getStatus());
    }
    @Test void completedTurnWithInvalidSchemaOutputNeverCommitsResultOrDispatchesNext() throws Exception {
        completeReceipt("{\"approved\":\"true\"}");
        var json=new ObjectMapper();
        var node=new WorkflowDTO.Node("a","EXPERT","检查",8L,"用户职责",null,null,0,0,null,
            json.readTree("{\"type\":\"object\",\"properties\":{\"approved\":{\"type\":\"boolean\"}},\"required\":[\"approved\"]}"),null,null);
        execution.setPlanJson(json.writeValueAsString(new WorkflowDTO(3,"a",List.of(node))));
        running();turn.setStatus("COMPLETED");step.setTerminalStatus("COMPLETED");
        when(mapper.messages(7L)).thenReturn(List.of(message(10L,1L,"{\"approved\":\"true\"}")));
        service.tick();service.tick();assertEquals("VALIDATION_FAILED",step.getStatus());
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
