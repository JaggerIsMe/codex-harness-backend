package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.WorkspaceFileProperties;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import com.myharness.codex.websocket.AgentProtocolCodec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFileActionsServiceTest {
    @TempDir Path storage;
    WorkspaceFileService files;
    WorkspaceFileOperationMapper operations=mock(WorkspaceFileOperationMapper.class);
    ProjectMapper projects=mock(ProjectMapper.class);
    AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
    ExpertMapper experts=mock(ExpertMapper.class);
    AuthorizationService access=mock(AuthorizationService.class);
    AgentCommandGateway gateway=mock(AgentCommandGateway.class);
    DeviceAuthenticationService authentication=mock(DeviceAuthenticationService.class);
    WorkspaceAttachmentLocationService locations=mock(WorkspaceAttachmentLocationService.class);
    ClientEventWebSocketHandler events=mock(ClientEventWebSocketHandler.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    WorkspaceFileProperties properties=new WorkspaceFileProperties();
    ObjectMapper json=new ObjectMapper();
    Map<Long,WorkspaceFileOperationPO> rows=new LinkedHashMap<>();
    Map<Long,List<WorkspaceFileItemsDTO.Item>> details=new HashMap<>();
    AgentDevicePO device=new AgentDevicePO();
    ProjectPO project=new ProjectPO();

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        properties.setStorageDir(storage.toString());
        project.setId(2L);project.setUserId(1L);project.setDeviceId(4L);project.setDeviceCode("device");
        project.setWorkspaceName("demo");project.setWorkspaceStatus("ENABLED");project.setStatus("ACTIVE");
        device.setId(4L);device.setWorkspaceFiles(true);device.setWorkspaceFileMutations(true);device.setWorkspaceArchiveDownload(true);
        when(projects.selectOwned(2L,1L)).thenReturn(project);when(projects.selectForDevice(4L)).thenReturn(List.of(project));
        when(devices.selectById(4L)).thenReturn(device);when(authentication.authenticate("device","Bearer token")).thenReturn(device);
        when(experts.lockProject(2L)).thenReturn(0L);when(gateway.isOnline("device")).thenReturn(true);
        when(gateway.send(anyString(),any())).thenAnswer(i ->
                new AgentProtocolCodec(json,new AgentProperties()).encodeCommand(i.getArgument(0),i.getArgument(1)));
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        files=new WorkspaceFileService(projects,devices,operations,access,authentication,gateway,properties,redis,json,events);
        files.configureActions(experts,tx,locations);
        when(operations.insert(any())).thenAnswer(i -> {var op=(WorkspaceFileOperationPO)i.getArgument(0);op.setId((long)rows.size()+1);rows.put(op.getId(),op);return 1;});
        when(operations.get(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(operations.request(anyLong(),anyString())).thenAnswer(i -> rows.values().stream().filter(o -> o.getProjectId().equals(i.getArgument(0)) && o.getRequestKey().equals(i.getArgument(1))).findFirst().orElse(null));
        when(operations.actionPayload(any())).thenReturn(1);when(operations.actionResult(any())).thenReturn(1);
        when(operations.mutationCount(2L)).thenAnswer(i -> (int)rows.values().stream().filter(o -> Set.of("RELOCATE_WORKSPACE_ENTRY","DELETE_WORKSPACE_ENTRY").contains(o.getKind()) && Set.of("QUEUED","RUNNING","UNKNOWN").contains(o.getStatus())).count());
        when(operations.running(2L)).thenAnswer(i -> (int)rows.values().stream().filter(o -> Set.of("RUNNING","UNKNOWN").contains(o.getStatus())).count());
        when(operations.queued()).thenAnswer(i -> rows.values().stream().filter(o -> "QUEUED".equals(o.getStatus())).toList());
        when(operations.start(anyLong())).thenAnswer(i -> {var op=rows.get(i.getArgument(0));if(!"QUEUED".equals(op.getStatus())) return 0;op.setStatus("RUNNING");return 1;});
        when(operations.deletePlan(eq(2L),anyString())).thenAnswer(i -> rows.values().stream().filter(o -> "PREPARE_WORKSPACE_DELETE".equals(o.getKind()) && Objects.equals(o.getPlanId(),i.getArgument(1))).findFirst().orElse(null));
        when(operations.planConsumer(anyLong())).thenAnswer(i -> rows.values().stream().filter(o -> Objects.equals(o.getDeletePlanOperationId(),i.getArgument(0))).findFirst().orElse(null));
        when(operations.clearItems(anyLong())).thenAnswer(i -> {details.put(i.getArgument(0),new ArrayList<>());return 1;});
        when(operations.insertItem(anyLong(),anyInt(),any())).thenAnswer(i -> {details.get(i.<Long>getArgument(0)).add(i.getArgument(2));return 1;});
        when(operations.itemsDigest(anyLong(),anyString())).thenAnswer(i -> {rows.get(i.<Long>getArgument(0)).setItemsDigest(i.getArgument(1));return 1;});
        when(operations.items(anyLong(),anyLong(),anyInt())).thenAnswer(i -> {
            var values=details.getOrDefault(i.getArgument(0),List.of());var answer=new ArrayList<WorkspaceFileItemPO>();
            for(int n=0;n<values.size();n++) if(n>i.<Long>getArgument(1) && answer.size()<i.<Integer>getArgument(2)) {
                var item=values.get(n);var row=new WorkspaceFileItemPO();row.setItemIndex(n);row.setPath(item.path());row.setEntryType(item.entryType());row.setStatus(item.status());row.setCode(item.code());row.setError(item.error());answer.add(row);
            }
            return answer;
        });
        when(operations.content(anyLong(),anyLong(),anyString())).thenAnswer(i -> {var op=rows.get(i.<Long>getArgument(0));op.setSizeBytes(i.getArgument(1));op.setSha256(i.getArgument(2));return 1;});
    }

    @Test void renameHasCanonicalIdempotencyAndClaimsReferencesBeforeDispatch() throws Exception {
        var request=rename("report.txt","报告.txt");var first=files.rename(2L,1L,request);
        assertEquals(first.id(),files.rename(2L,1L,request).id());assertEquals(1,rows.size());
        verify(locations,times(1)).claim(2L,"report.txt",1L);
        assertThrows(BusinessException.class,() -> files.rename(2L,1L,new WorkspaceFileActionRequestDTO(request.requestKey(),"report.txt","other.txt",null,"revision",null,null,null)));
        files.dispatch();var command=files.agentManifest(1L,"device","Bearer token");
        assertEquals("报告.txt",command.targetPath());assertEquals("revision",command.expectedRevision());assertEquals(64,command.requestDigest().length());
        assertThrows(BusinessException.class,() -> files.assertNoMutation(2L));
        var done=result(rows.get(1L),"SUCCEEDED","COMPLETE","FILE",null,null,null,null);
        files.result(4L,done);assertEquals("SUCCEEDED",files.operation(2L,1L,1L).status());
        verify(locations).apply(2L,"report.txt","报告.txt",1L,"SUCCEEDED",List.of(),List.of());
        files.result(4L,done);verify(locations,times(1)).apply(2L,"report.txt","报告.txt",1L,"SUCCEEDED",List.of(),List.of());
        assertDoesNotThrow(() -> files.assertNoMutation(2L));
    }

    @Test void directoryRenamePassesRealProtocolEncodingAndDispatchesItsTargetPath() {
        files.rename(2L,1L,rename("docs","文档"));
        files.dispatch();
        assertEquals("RUNNING",rows.get(1L).getStatus());
        var sent=org.mockito.ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway).send(eq("device"),sent.capture());
        assertEquals("RELOCATE_WORKSPACE_ENTRY",sent.getValue().getType());
        var payload=(WorkspaceFileCommandDTO)sent.getValue().getPayload();
        assertEquals("docs",payload.path());assertEquals("文档",payload.targetPath());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void recoversLegacyEncodingFailureWithoutSendingOrReplayingOnManualOrReconnect(boolean reconnect) {
        doThrow(new IllegalArgumentException(
                "No enum constant com.myharness.codex.entity.enums.AgentCommandType.RELOCATE_WORKSPACE_ENTRY"))
                .when(gateway).send(anyString(),any());
        files.rename(2L,1L,rename("docs","文档"));files.dispatch();
        var op=rows.get(1L);assertEquals("UNKNOWN",op.getStatus());
        verify(locations).apply(2L,"docs","文档",1L,"UNKNOWN",List.of(),List.of());
        clearInvocations(gateway);
        if(reconnect) {
            when(operations.unknownForDevice(4L)).thenReturn(List.of(op));
            files.reconnected(4L);
        } else {
            when(gateway.isOnline("device")).thenReturn(false);
            files.reconcile(2L,1L,1L,key());
        }
        assertEquals("FAILED",op.getStatus());
        assertDoesNotThrow(() -> files.assertNoMutation(2L));
        verify(locations).apply(2L,"docs","文档",1L,"FAILED",List.of(),List.of());
        verify(gateway,never()).send(anyString(),any());
    }

    @Test void legacyRecoveryRechecksEvidenceInsideResultTransaction() {
        doThrow(new IllegalArgumentException(
                "No enum constant com.myharness.codex.entity.enums.AgentCommandType.RELOCATE_WORKSPACE_ENTRY"))
                .when(gateway).send(anyString(),any());
        files.rename(2L,1L,rename("docs","文档"));files.dispatch();
        var stale=new WorkspaceFileOperationPO();
        org.springframework.beans.BeanUtils.copyProperties(rows.get(1L),stale);
        var current=rows.get(1L);current.setError("Agent 已重连，请核实上次操作结果");
        when(operations.get(1L)).thenReturn(stale,current);
        doReturn("sent").when(gateway).send(anyString(),any());
        clearInvocations(gateway);
        files.reconcile(2L,1L,1L,key());
        assertEquals("UNKNOWN",current.getStatus());
        verify(locations,never()).apply(any(),any(),any(),any(),eq("FAILED"),any(),any());
        verify(gateway).send(eq("device"),argThat(c -> "RECONCILE_WORKSPACE_OPERATION".equals(c.getType())));
    }

    @Test void rejectsCrossProjectBusyTurnsProtectedPathsAndOldCapabilities() {
        assertThrows(BusinessException.class,() -> files.rename(2L,99L,rename("a","b")));
        when(experts.activeTurns(2L)).thenReturn(1);
        assertThrows(BusinessException.class,() -> files.rename(2L,1L,rename("a","b")));assertTrue(rows.isEmpty());
        when(experts.activeTurns(2L)).thenReturn(0);device.setWorkspaceFileMutations(false);
        assertThrows(BusinessException.class,() -> files.rename(2L,1L,rename("a","b")));
        device.setWorkspaceFileMutations(true);
        for(String path:List.of(".AGENT/a","a/.git",".harness-upload-part","../a",""))
            assertThrows(BusinessException.class,() -> files.rename(2L,1L,rename(path,"b")));
        assertThrows(BusinessException.class,() -> files.rename(2L,1L,rename("a",".CODEX")));
        assertThrows(BusinessException.class,() -> files.move(2L,1L,new WorkspaceFileActionRequestDTO(key(),"a",null,"","revision",null,null,null)));
    }

    @Test void timeoutAfterDispatchStaysUnknownUntilAuthoritativeResultAndNeverResendsMutation() {
        files.rename(2L,1L,rename("a.txt","b.txt"));files.dispatch();
        when(operations.timedOut(any())).thenReturn(List.of(rows.get(1L)));files.dispatch();
        assertEquals("UNKNOWN",rows.get(1L).getStatus());assertThrows(BusinessException.class,() -> files.assertNoMutation(2L));
        files.reconcile(2L,1L,1L,key());
        var captor=org.mockito.ArgumentCaptor.forClass(AgentCommand.class);verify(gateway,times(2)).send(eq("device"),captor.capture());
        assertEquals(List.of("RELOCATE_WORKSPACE_ENTRY","RECONCILE_WORKSPACE_OPERATION"),captor.getAllValues().stream().map(AgentCommand::getType).toList());
        files.result(4L,result(rows.get(1L),"SUCCEEDED","COMPLETE","FILE",null,null,null,null));
        assertEquals("SUCCEEDED",rows.get(1L).getStatus());assertDoesNotThrow(() -> files.assertNoMutation(2L));
    }

    @Test void queuedTimeoutHasNoEffectAndReleasesMutationGate() {
        files.rename(2L,1L,rename("a.txt","b.txt"));when(gateway.isOnline("device")).thenReturn(false);
        when(operations.timedOut(any())).thenReturn(List.of(rows.get(1L)));files.dispatch();
        assertEquals("FAILED",rows.get(1L).getStatus());assertDoesNotThrow(() -> files.assertNoMutation(2L));verify(gateway,never()).send(any(),any());
    }

    @Test void deletePlanCannotBeConsumedAgainWithNewRequestKeyAndExpiredPlanIsRejected() {
        var plan=preparePlan("docs","DIRECTORY");
        var request=new WorkspaceFileActionRequestDTO(key(),null,null,null,null,plan.planId(),plan.planDigest(),null);
        var deleted=files.delete(2L,1L,request);assertEquals(deleted.id(),files.delete(2L,1L,request).id());
        rows.get(Long.valueOf(deleted.id())).setStatus("FAILED");
        assertThrows(BusinessException.class,() -> files.delete(2L,1L,new WorkspaceFileActionRequestDTO(key(),null,null,null,null,plan.planId(),plan.planDigest(),null)));
        var expired=new WorkspaceFileResultDTO.Plan(key(),"a".repeat(64),"other.txt","FILE","revision",1,0,12,1);
        files.deletePlan(2L,1L,new WorkspaceFileActionRequestDTO(key(),"other.txt",null,null,"revision",null,null,null));
        var op=rows.get(3L);op.setStatus("RUNNING");files.result(4L,result(op,"SUCCEEDED","COMPLETE","FILE",expired,null,null,null));
        assertThrows(BusinessException.class,() -> files.delete(2L,1L,new WorkspaceFileActionRequestDTO(key(),null,null,null,null,expired.planId(),expired.planDigest(),null)));
    }

    @Test void partialDeleteUsesDigestCheckedScopedDetailsAndPreservesRemainingAttachments() throws Exception {
        var plan=preparePlan("docs","DIRECTORY");files.delete(2L,1L,new WorkspaceFileActionRequestDTO(key(),null,null,null,null,plan.planId(),plan.planDigest(),null));
        var op=rows.get(2L);op.setStatus("RUNNING");
        var items=List.of(new WorkspaceFileItemsDTO.Item("docs/a.txt","FILE","DELETED",null,null),new WorkspaceFileItemsDTO.Item("docs/b.txt","FILE","REMAINING","FILE_BUSY","占用"),new WorkspaceFileItemsDTO.Item("docs","DIRECTORY","REMAINING",null,null));
        byte[] bytes=json.writeValueAsBytes(new WorkspaceFileItemsDTO(items));String sha=sha(bytes);
        assertThrows(BusinessException.class,() -> files.receiveItems(2L,"device","Bearer token","0".repeat(64),new ByteArrayInputStream(bytes)));
        files.receiveItems(2L,"device","Bearer token",sha,new ByteArrayInputStream(bytes));
        files.receiveItems(2L,"device","Bearer token",sha,new ByteArrayInputStream(bytes));
        verify(operations,times(1)).clearItems(2L);
        files.result(4L,result(op,"PARTIAL_FAILED","PARTIAL","DIRECTORY",null,new WorkspaceFileResultDTO.Summary(1,0,2),null,sha));
        assertEquals("PARTIAL_FAILED",op.getStatus());
        verify(locations).apply(2L,"docs",null,2L,"PARTIAL_FAILED",List.of("docs/a.txt"),List.of());
        var first=files.items(2L,1L,2L,null,1);assertEquals("0",first.nextCursor());assertEquals("docs/a.txt",first.items().getFirst().path());
        assertEquals("docs/b.txt",files.items(2L,1L,2L,first.nextCursor(),1).items().getFirst().path());
        assertThrows(BusinessException.class,() -> files.items(99L,1L,2L,null,1));
    }

    @Test void archiveCanonicalizesSelectionsAndUsesIndependentOutputLimitWithoutExpiringBusinessResult() throws Exception {
        properties.setMaxFileBytes(3);properties.setMaxArchiveOutputBytes(16);
        var a=new WorkspaceFileActionRequestDTO.Item("reports/a.txt","r1");var b=new WorkspaceFileActionRequestDTO.Item("archive/a.txt","r2");
        var request=new WorkspaceFileActionRequestDTO(key(),null,null,null,null,null,null,List.of(a,b,a));
        var created=files.archive(2L,1L,request);
        var reordered=new WorkspaceFileActionRequestDTO(request.requestKey(),null,null,null,null,null,null,List.of(b,a));
        assertEquals(created.id(),files.archive(2L,1L,reordered).id());files.dispatch();
        var command=files.agentManifest(1L,"device","Bearer token");assertEquals(List.of(b,a),command.items());
        byte[] bytes="ZIP bytes".getBytes();files.receiveContent(1L,"device","Bearer token",sha(bytes),new ByteArrayInputStream(bytes));
        var op=rows.get(1L);files.result(4L,result(op,"SUCCEEDED","COMPLETE","FILE",null,null,null,null));
        assertArrayEquals(bytes,files.downloadContent(2L,1L,1L).resource().getContentAsByteArray());
        when(operations.expired(any())).thenReturn(List.of(op));files.cleanup();
        verify(operations).releaseContent(1L);verify(operations,never()).expire(1L);assertEquals("SUCCEEDED",op.getStatus());
        assertThrows(BusinessException.class,() -> files.archive(2L,1L,new WorkspaceFileActionRequestDTO(key(),null,null,null,null,null,null,List.of(a,new WorkspaceFileActionRequestDTO.Item("REPORTS/a.txt","r3")))));
    }

    @Test void freezesMinimumAgentLimitsAndForeignResultCannotReleaseGate() throws Exception {
        device.setWorkspaceFileLimits(json.writeValueAsString(new WorkspaceFileCommandDTO.Limits(2,12,24,30,524288,200)));
        files.rename(2L,1L,rename("a","b"));files.dispatch();
        assertEquals(12,files.agentManifest(1L,"device","Bearer token").limits().maxFileBytes());
        var op=rows.get(1L);files.result(99L,result(op,"SUCCEEDED","COMPLETE","FILE",null,null,null,null));
        assertEquals("RUNNING",op.getStatus());verify(locations,never()).apply(any(),any(),any(),any(),any(),any(),any());
    }

    @Test void resultFromControlAfterCommitUsesItsOwnTransactionAndNotifiesOnlyAfterResultCommit() throws Exception {
        var dataSource=mock(javax.sql.DataSource.class);
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        when(dataSource.getConnection()).thenAnswer(i -> {
            var connection=mock(java.sql.Connection.class);when(connection.getAutoCommit()).thenReturn(true);
            doAnswer(call -> {commits.incrementAndGet();return null;}).when(connection).commit();return connection;
        });
        var tx=new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        files.configureActions(experts,tx,locations);
        files.rename(2L,1L,rename("a.txt","b.txt"));files.dispatch();int before=commits.get();
        var outerConnection=new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
        when(operations.actionResult(any())).thenAnswer(i -> {
            var holder=(org.springframework.jdbc.datasource.ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource);
            assertNotNull(holder);assertNotSame(outerConnection.get(),holder.getConnection());return 1;
        });
        doAnswer(i -> {assertEquals(before+2,commits.get());return null;}).when(events).sendToUser(eq(1L),any());
        tx.execute(status -> {
            outerConnection.set(((org.springframework.jdbc.datasource.ConnectionHolder)TransactionSynchronizationManager.getResource(dataSource)).getConnection());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {files.result(4L,result(rows.get(1L),"SUCCEEDED","COMPLETE","FILE",null,null,null,null));}
            });
            return null;
        });
        assertEquals(before+2,commits.get());verify(events,times(1)).sendToUser(eq(1L),any());
    }

    @Test void missingDetailsCannotTurnPartialDeletionIntoSuccessOrReleaseUncertainGate() {
        var plan=preparePlan("docs","DIRECTORY");files.delete(2L,1L,new WorkspaceFileActionRequestDTO(key(),null,null,null,null,plan.planId(),plan.planDigest(),null));
        var op=rows.get(2L);op.setStatus("RUNNING");
        files.result(4L,result(op,"PARTIAL_FAILED","PARTIAL","DIRECTORY",null,new WorkspaceFileResultDTO.Summary(1,0,1),null,"b".repeat(64)));
        assertEquals("UNKNOWN",op.getStatus());assertThrows(BusinessException.class,() -> files.assertNoMutation(2L));
        verify(locations).apply(2L,"docs",null,2L,"UNKNOWN",List.of(),List.of());
    }

    @Test void executorRejectionIsNoChangeButRejectedReconciliationCannotReleaseOriginalMutation() {
        files.rename(2L,1L,rename("a","b"));files.dispatch();
        files.commandError(4L,"1","RELOCATE_WORKSPACE_ENTRY","AGENT_BUSY","队列已满");
        assertEquals("FAILED",rows.get(1L).getStatus());assertDoesNotThrow(() -> files.assertNoMutation(2L));
        files.rename(2L,1L,rename("a","b"));files.dispatch();
        files.commandError(4L,"2","RELOCATE_WORKSPACE_ENTRY","COMMAND_FAILED","结果丢失");
        assertEquals("UNKNOWN",rows.get(2L).getStatus());
        files.commandError(4L,"2","RECONCILE_WORKSPACE_OPERATION","AGENT_BUSY","队列已满");
        assertEquals("UNKNOWN",rows.get(2L).getStatus());assertThrows(BusinessException.class,() -> files.assertNoMutation(2L));
    }

    @Test void legacyCommandsAndAuthorizationRemainReadableByStrictOldAgents() throws Exception {
        var command=new WorkspaceFileCommandDTO("1","2","demo","report.txt","",0,null);
        var original=json.readValue(json.writeValueAsString(command),LegacyCommand.class);
        assertEquals("report.txt",original.path());
        assertEquals(original,json.readValue(json.writeValueAsString(new com.myharness.codex.entity.vo.WorkspaceFileManifestVO(command)),LegacyCommand.class));
    }

    private record LegacyCommand(String operationId,String projectId,String workspaceName,String path,String cursor,long sizeBytes,String sha256) {}

    private WorkspaceFileResultDTO.Plan preparePlan(String path,String type) {
        files.deletePlan(2L,1L,new WorkspaceFileActionRequestDTO(key(),path,null,null,"revision",null,null,null));
        var op=rows.values().stream().reduce((a,b)->b).orElseThrow();op.setStatus("RUNNING");
        var plan=new WorkspaceFileResultDTO.Plan(key(),"a".repeat(64),path,type,"revision",2,"DIRECTORY".equals(type) ? 1 : 0,12,System.currentTimeMillis()+120000);
        files.result(4L,result(op,"SUCCEEDED","COMPLETE",type,plan,null,null,null));return plan;
    }
    private WorkspaceFileResultDTO result(WorkspaceFileOperationPO op,String status,String outcome,String type,WorkspaceFileResultDTO.Plan plan,
            WorkspaceFileResultDTO.Summary summary,List<WorkspaceFileItemsDTO.Item> items,String digest) {
        return new WorkspaceFileResultDTO(op.getId().toString(),"SUCCEEDED".equals(status),null,List.of(),null,0,op.getSizeBytes(),op.getSha256(),1,status,outcome,null,
                op.getPath(),op.getTargetPath(),type,"new-revision",plan,summary,items,digest);
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static WorkspaceFileActionRequestDTO rename(String path,String name){return new WorkspaceFileActionRequestDTO(key(),path,name,null,"revision",null,null,null);}
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
