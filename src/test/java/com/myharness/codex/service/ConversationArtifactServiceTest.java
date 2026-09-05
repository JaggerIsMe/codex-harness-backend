package com.myharness.codex.service;

import com.myharness.codex.config.ArtifactProperties;
import com.myharness.codex.entity.dto.PublishArtifactDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationArtifactServiceTest {
    @TempDir Path directory;
    ConversationArtifactMapper mapper;
    ConversationMapper conversations;
    AuthorizationService access;
    DeviceAuthenticationService authentication;
    ConversationArtifactService service;
    ArtifactProperties limits;
    ConversationTurnPO turn;
    Map<Long,ConversationArtifactPO> records;
    ClientEventWebSocketHandler events;

    @BeforeEach void setup() {
        mapper=mock(ConversationArtifactMapper.class);conversations=mock(ConversationMapper.class);
        var projects=mock(ProjectMapper.class); access=mock(AuthorizationService.class);
        authentication=mock(DeviceAuthenticationService.class);events=mock(ClientEventWebSocketHandler.class);
        limits=new ArtifactProperties(); limits.setStorageDir(directory.toString());
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        service=new ConversationArtifactService(mapper,conversations,projects,access,authentication,limits,tx,events);
        var c=new ConversationPO();c.setId(3L);c.setUserId(1L);c.setProjectId(2L);c.setDeviceId(4L);c.setStatus("ACTIVE");
        when(conversations.selectOwnedConversation(2L,3L,1L)).thenReturn(c);
        when(conversations.selectConversation(3L)).thenReturn(c);
        var project=new ProjectPO();project.setStatus("ACTIVE");when(projects.selectOwned(2L,1L)).thenReturn(project);
        var device=new AgentDevicePO();device.setId(4L);when(authentication.authenticate("device","Bearer test")).thenReturn(device);
        turn=new ConversationTurnPO();turn.setId(7L);turn.setConversationId(3L);turn.setStatus("COMPLETED");
        when(conversations.selectTurn(7L)).thenReturn(turn);
        records=new LinkedHashMap<>();
        when(mapper.insert(any())).thenAnswer(i -> {
            ConversationArtifactPO p=i.getArgument(0);p.setId((long)records.size()+9);records.put(p.getId(),p);return 1;
        });
        when(mapper.find(any())).thenAnswer(i -> records.get(i.getArgument(0)));
        when(mapper.lock(any())).thenAnswer(i -> records.get(i.getArgument(0)));
        when(mapper.findKey(any(),any())).thenAnswer(i -> records.values().stream()
                .filter(p -> p.getTurnId().equals(i.getArgument(0)) && p.getArtifactKey().equals(i.getArgument(1))).findFirst().orElse(null));
        when(mapper.forTurn(any())).thenAnswer(i -> records.values().stream().filter(p -> p.getTurnId().equals(i.getArgument(0))).toList());
        when(mapper.forConversation(any())).thenAnswer(i -> records.values().stream().filter(p -> p.getConversationId().equals(i.getArgument(0))).toList());
        when(mapper.fail(any(),any())).thenAnswer(i -> {
            var p=records.get((Long)i.getArgument(0));
            if(p==null || !"UPLOADING".equals(p.getStatus()))return 0;
            p.setStatus("FAILED");p.setErrorMessage(i.getArgument(1));return 1;
        });
        when(mapper.retry(any())).thenAnswer(i -> {
            var p=records.get((Long)i.getArgument(0));return p!=null && "FAILED".equals(p.getStatus()) ? 1 : 0;
        });
    }
    PublishArtifactDTO metadata(String key,String text) {
        return new PublishArtifactDTO(key,"报告.txt",text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,SecureDigests.sha256(text));
    }
    long publish(String key,String text) {
        return Long.parseLong(service.register(7L,"device","Bearer test",metadata(key,text)).id());
    }
    void upload(long id,String text) throws Exception {
        service.upload(7L,id,"device","Bearer test",new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    String download(long id) throws Exception {
        try(var in=service.download(2L,3L,id,1L).resource().getInputStream()){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    @Test void publishedBytesSurviveDeviceOfflineAndSameNameLaterDelivery() throws Exception {
        long first=publish("one","original");upload(first,"original");
        long second=publish("two","new version");upload(second,"new version");
        // Downloads do not call Device authentication or connect to the remote filesystem.
        reset(authentication);
        assertEquals("original",download(first));assertEquals("new version",download(second));
        assertEquals(2,service.list(2L,3L,1L).size());
        assertEquals("报告.txt",service.download(2L,3L,first,1L).fileName());
        verifyNoInteractions(authentication);
    }
    @Test void registerAndLostUploadResponseAreIdempotentAndNeverReplaceReady() throws Exception {
        long id=publish("stable","original");upload(id,"original");
        assertEquals(id,publish("stable","original"));
        service.upload(7L,id,"device","Bearer test",new InputStream(){public int read(){throw new AssertionError("READY must not read replacement bytes");}});
        service.agentFailed(7L,id,"device","Bearer test");
        assertEquals("READY",records.get(id).getStatus());assertEquals("original",download(id));
        assertThrows(BusinessException.class,() -> publish("stable","replacement"));
        assertEquals(1,records.size());
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void corruptOrInterruptedUploadCannotBeDownloadedAndCanRetry() throws Exception {
        long id=publish("retry","right");
        assertThrows(BusinessException.class,() -> upload(id,"wrong"));
        assertEquals("FAILED",records.get(id).getStatus());
        assertThrows(BusinessException.class,() -> download(id));
        assertEquals("UPLOADING",service.retry(2L,3L,id,1L).status());
        upload(id,"right");assertEquals("right",download(id));
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
        long other=publish("broken","next");
        assertThrows(IOException.class,() -> service.upload(7L,other,"device","Bearer test",new InputStream(){public int read() throws IOException{throw new IOException("disconnect");}}));
        assertEquals("FAILED",records.get(other).getStatus());
    }
    @Test void foreignUserConversationTurnAndDeviceCannotAccessArtifact() throws Exception {
        long id=publish("private","secret");upload(id,"secret");
        assertThrows(BusinessException.class,() -> service.download(2L,3L,id,999L));
        assertThrows(BusinessException.class,() -> service.retry(2L,99L,id,1L));
        var otherTurn=new ConversationTurnPO();otherTurn.setId(8L);otherTurn.setConversationId(3L);otherTurn.setStatus("COMPLETED");
        when(conversations.selectTurn(8L)).thenReturn(otherTurn);
        assertThrows(BusinessException.class,() -> service.agentStatus(8L,id,"device","Bearer test"));
        var otherDevice=new AgentDevicePO();otherDevice.setId(99L);
        when(authentication.authenticate("device","Bearer test")).thenReturn(otherDevice);
        assertThrows(BusinessException.class,() -> service.agentStatus(7L,id,"device","Bearer test"));
    }
    @Test void revokedDeviceAssignmentBlocksHistoryAndPublication() {
        long id=publish("private","secret");
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,4L);
        assertThrows(BusinessException.class,() -> service.list(2L,3L,1L));
        assertThrows(BusinessException.class,() -> download(id));
        assertThrows(BusinessException.class,() -> service.retry(2L,3L,id,1L));
        assertThrows(BusinessException.class,() -> service.agentStatus(7L,id,"device","Bearer test"));
    }
    @Test void uploadRechecksAuthorizationAfterReadingBytes() {
        long id=publish("private","secret");
        var input=new ByteArrayInputStream("secret".getBytes()) {
            @Override public synchronized int read(byte[] b,int off,int len) {
                int n=super.read(b,off,len);
                if(n<0) doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,4L);
                return n;
            }
        };
        assertThrows(BusinessException.class,() -> service.upload(7L,id,"device","Bearer test",input));
        assertNotEquals("READY",records.get(id).getStatus());
        assertDoesNotThrow(() -> {try(var files=Files.list(directory)){assertEquals(0,files.count());}});
    }
    @Test void refusesCanceledTurnInvalidNamesAndQuotaOverflow() {
        turn.setStatus("INTERRUPTED");assertThrows(BusinessException.class,() -> publish("a","one"));turn.setStatus("COMPLETED");
        for(String name:List.of("../secret","C:\\secret","..","bad\nname"))
            assertThrows(BusinessException.class,() -> service.register(7L,"device","Bearer test",new PublishArtifactDTO("a",name,0,SecureDigests.sha256(""))));
        limits.setMaxFiles(1);publish("a","one");assertThrows(BusinessException.class,() -> publish("b","two"));
        limits.setMaxFiles(5);limits.setMaxTotalBytes(4);assertThrows(BusinessException.class,() -> publish("c","two"));
    }
    @Test void emptyFilesAreValidDeliveries() throws Exception {
        long id=publish("empty","");upload(id,"");assertEquals("",download(id));
    }
}
