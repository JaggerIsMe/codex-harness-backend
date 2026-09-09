package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.WorkspaceFileProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.WorkspaceFileEntryVO;
import com.myharness.codex.entity.vo.WorkspaceDirectoryVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFileServiceTest {
    @TempDir Path root;
    WorkspaceFileService service;
    WorkspaceFileOperationMapper operations;
    AgentCommandGateway gateway;
    ProjectMapper projects;
    AuthorizationService access;
    DeviceAuthenticationService authentication;
    ClientEventWebSocketHandler events;
    HashOperations<String,Object,Object> hash;
    SetOperations<String,String> sets;
    final Map<Long,WorkspaceFileOperationPO> rows=new LinkedHashMap<>();
    final Map<Object,Object> cache=new HashMap<>();
    ProjectPO project;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        operations=mock(WorkspaceFileOperationMapper.class);gateway=mock(AgentCommandGateway.class);projects=mock(ProjectMapper.class);
        access=mock(AuthorizationService.class);authentication=mock(DeviceAuthenticationService.class);events=mock(ClientEventWebSocketHandler.class);
        var devices=mock(AgentDeviceMapper.class);var redis=mock(StringRedisTemplate.class);hash=mock(HashOperations.class);
        sets=mock(SetOperations.class);when(redis.opsForHash()).thenReturn(hash);when(redis.opsForSet()).thenReturn(sets);
        when(hash.get(anyString(),any())).thenAnswer(i -> cache.get(i.getArgument(1)));
        doAnswer(i -> {cache.put(i.getArgument(1),i.getArgument(2));return null;}).when(hash).put(anyString(),any(),any());
        when(hash.keys(anyString())).thenAnswer(i -> new HashSet<>(cache.keySet()));
        var properties=new WorkspaceFileProperties();properties.setStorageDir(root.toString());
        project=new ProjectPO();project.setId(2L);project.setUserId(1L);project.setDeviceId(4L);project.setDeviceCode("device");
        project.setWorkspaceName("demo");project.setWorkspaceStatus("ENABLED");project.setStatus("ACTIVE");
        when(projects.selectOwned(2L,1L)).thenReturn(project);when(projects.selectForDevice(4L)).thenReturn(List.of(project));
        when(gateway.isOnline("device")).thenReturn(true);
        var device=new AgentDevicePO();device.setId(4L);device.setWorkspaceFiles(true);when(devices.selectById(4L)).thenReturn(device);
        when(authentication.authenticate("device","Bearer token")).thenReturn(device);
        when(operations.insert(any())).thenAnswer(i -> {var p=(WorkspaceFileOperationPO)i.getArgument(0);p.setId((long)rows.size()+1);rows.put(p.getId(),p);return 1;});
        when(operations.get(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(operations.request(anyLong(),anyString())).thenAnswer(i -> rows.values().stream().filter(p -> p.getProjectId().equals(i.getArgument(0)) && p.getRequestKey().equals(i.getArgument(1))).findFirst().orElse(null));
        when(operations.latest(anyLong(),anyString(),anyString())).thenAnswer(i -> rows.values().stream().filter(p -> p.getProjectId().equals(i.getArgument(0)) && p.getPath().equals(i.getArgument(1)) && p.getCursor().equals(i.getArgument(2)) && p.getKind().equals("SYNC_WORKSPACE_TREE")).reduce((a,b) -> b).orElse(null));
        when(operations.queued()).thenAnswer(i -> rows.values().stream().filter(p -> p.getStatus().equals("QUEUED")).toList());
        when(operations.running(anyLong())).thenAnswer(i -> (int)rows.values().stream().filter(p -> p.getProjectId().equals(i.getArgument(0)) && p.getStatus().equals("RUNNING")).count());
        when(operations.start(anyLong())).thenAnswer(i -> {var p=rows.get(i.getArgument(0));if(!p.getStatus().equals("QUEUED")) return 0;p.setStatus("RUNNING");return 1;});
        when(operations.finish(anyLong(),anyString(),nullable(String.class))).thenAnswer(i -> {var p=rows.get(i.getArgument(0));if(!Set.of("QUEUED","RUNNING").contains(p.getStatus())) return 0;p.setStatus(i.getArgument(1));p.setError(i.getArgument(2));return 1;});
        service=new WorkspaceFileService(projects,devices,operations,access,authentication,gateway,properties,redis,new ObjectMapper(),events);
    }
    @Test void synchronizesWithoutBrowserAndCoalescesConcurrentRefreshes() {
        service.refreshProject(2L,1L);service.refreshProject(2L,1L);service.dispatch();
        assertEquals(1,rows.size());assertEquals("RUNNING",rows.get(1L).getStatus());
        service.refreshProject(2L,1L);service.dispatch();assertEquals(1,rows.size());
        var result=new WorkspaceFileResultDTO("1",true,null,List.of(new WorkspaceFileEntryVO("file.txt","file.txt","FILE",4,10)),null,20,0,null);
        service.result(4L,result);
        assertEquals("SUCCEEDED",rows.get(1L).getStatus());
        var tree=service.directory(2L,1L,"","",false);assertTrue(tree.loaded());assertEquals("file.txt",tree.entries().getFirst().name());
        service.dispatch();assertEquals(2,rows.size());
        service.result(4L,result); // Duplicate response cannot finish or overwrite operation 2.
        assertEquals("RUNNING",rows.get(2L).getStatus());
        verify(events,never()).broadcast(any());verify(events).sendToUser(eq(1L),any());
    }
    @Test void keepsCachedTreeOnFailureAndRejectsForeignDeviceResults() {
        service.directory(2L,1L,"","",true);service.dispatch();
        var result=new WorkspaceFileResultDTO("1",true,null,List.of(),null,20,0,null);
        service.result(99L,result);assertEquals("RUNNING",rows.get(1L).getStatus());
        service.result(4L,result);
        service.directory(2L,1L,"","",true);service.dispatch();
        service.result(4L,new WorkspaceFileResultDTO("2",false,"offline",List.of(),null,0,0,null));
        var cached=service.directory(2L,1L,"","",false);
        assertTrue(cached.loaded());assertEquals("1",cached.generation());assertEquals("FAILED",cached.operation().status());
    }
    @Test void uploadIsIdempotentAndDoesNotBecomeReadyBeforeAgentResult() throws Exception {
        String key=UUID.randomUUID().toString();
        var file=new MockMultipartFile("file","报告.txt","text/plain","hello".getBytes());
        var first=service.upload(2L,1L,"docs",key,file);var second=service.upload(2L,1L,"docs",key,file);
        assertEquals(first.id(),second.id());assertEquals(1,rows.size());
        try(var paths=Files.list(root)) {assertEquals(1,paths.count());}
        service.dispatch();var op=rows.get(1L);
        assertEquals("RUNNING",service.operation(2L,1L,1L).status());
        assertEquals("hello",new String(service.agentContent(1L,"device","Bearer token").resource().getContentAsByteArray()));
        service.result(4L,new WorkspaceFileResultDTO("1",true,null,List.of(),null,0,5,SecureDigests.sha256("hello")));
        assertEquals("SUCCEEDED",service.operation(2L,1L,1L).status());
        assertEquals("docs/报告.txt",op.getPath());
    }
    @Test void filtersOldCacheEvenOfflineAndDoesNotResyncInternalDirectories() throws Exception {
        List<WorkspaceFileEntryVO> entries=List.of(".CODEX", ".agent", ".agents", ".git", ".gitignore", ".harness", ".harness-upload-test.part", ".harness-workspace.json")
                .stream().map(name -> new WorkspaceFileEntryVO(name,name,"FILE",1,10)).toList();
        cache.put("\n",new ObjectMapper().writeValueAsString(new WorkspaceDirectoryVO("","1",20,entries,".harness-workspace.json",true,true,true,null,100)));
        when(gateway.isOnline("device")).thenReturn(false);
        var tree=service.directory(2L,1L,"","",false);
        assertTrue(tree.loaded());assertEquals(List.of(".gitignore"),tree.entries().stream().map(WorkspaceFileEntryVO::name).toList());
        assertEquals(".harness-workspace.json",tree.nextCursor()); // Preserve progress through old pages.
        assertThrows(BusinessException.class,() -> service.directory(2L,1L,"docs/.AGENT/nested","",true));
        when(gateway.isOnline("device")).thenReturn(true);
        when(sets.members(anyString())).thenReturn(Set.of("docs", "docs/.AGENT/nested", ".git"));
        service.refreshProject(2L,1L);service.dispatch();
        assertEquals(Set.of("","docs"),new HashSet<>(rows.values().stream().map(WorkspaceFileOperationPO::getPath).toList()));
        verify(sets).remove(anyString(),eq(".git"));
        verify(sets).remove(anyString(),eq("docs/.AGENT/nested"));
    }
    @Test void filtersResultsFromOlderAgentsBeforeCaching() {
        service.directory(2L,1L,"docs","",true);service.dispatch();
        var entries=List.of(new WorkspaceFileEntryVO(".git","docs/.git","FILE",1,10),
                new WorkspaceFileEntryVO("report.txt","docs/report.txt","FILE",1,10));
        service.result(4L,new WorkspaceFileResultDTO("1",true,null,entries,null,20,0,null));
        assertEquals("SUCCEEDED",rows.get(1L).getStatus());
        assertFalse(cache.get("docs\n").toString().contains("docs/.git"));
        assertEquals(List.of("report.txt"),service.directory(2L,1L,"docs","",false).entries().stream().map(WorkspaceFileEntryVO::name).toList());
    }
    @Test void verifiesProjectOwnershipAtEveryFileAccess() throws Exception {
        assertThrows(BusinessException.class,() -> service.directory(2L,99L,"","",false));
        service.upload(2L,1L,"",UUID.randomUUID().toString(),new MockMultipartFile("file","a.txt","text/plain",new byte[]{1}));
        service.dispatch();
        assertThrows(BusinessException.class,() -> service.operation(99L,1L,1L));
        doThrow(new BusinessException(com.myharness.codex.entity.enums.ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,4L);
        assertThrows(BusinessException.class,() -> service.agentManifest(1L,"device","Bearer token"));
        assertThrows(BusinessException.class,() -> service.agentContent(1L,"device","Bearer token"));
    }
    @Test void refusesMalformedPathsAndUnreadyAttachments() {
        for(String value:List.of("../a","a/../b","/absolute","C:/file","x:stream","a\\b","CON","a//b"))
            assertThrows(BusinessException.class,() -> service.directory(2L,1L,value,"",false));
        var p=new ConversationAttachmentPO();p.setProjectId(2L);p.setId(9L);p.setWorkspaceOperationId(100L);
        assertThrows(BusinessException.class,() -> service.requireUploaded(p));
    }
    @Test void legacyAttachmentCannotBypassWorkspaceReadiness() {
        var attachment=new ConversationAttachmentPO();attachment.setId(9L);attachment.setProjectId(2L);
        assertThrows(BusinessException.class,() -> service.requireUploaded(attachment));
        verify(operations,never()).get(any());
    }
    @Test void cleanupReleasesMessageTransferBytesButPreservesReadyAssociation() throws Exception {
        var attachment=new ConversationAttachmentPO();attachment.setId(9L);attachment.setProjectId(2L);
        attachment.setWorkspacePath("hello.txt");attachment.setWorkspaceOperationId(1L);
        var op=new WorkspaceFileOperationPO();op.setId(1L);op.setProjectId(2L);op.setAttachmentId(9L);
        op.setStatus("SUCCEEDED");op.setStorageKey(UUID.randomUUID().toString());rows.put(1L,op);
        Path content=root.resolve(op.getStorageKey());Files.writeString(content,"hello");
        when(operations.expired(any())).thenReturn(List.of(op));
        service.cleanup();
        assertFalse(Files.exists(content));
        verify(operations).releaseContent(1L);verify(operations,never()).expire(1L);
        assertDoesNotThrow(() -> service.requireUploaded(attachment));
    }
}
