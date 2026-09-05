package com.myharness.codex.service;

import com.myharness.codex.config.AttachmentProperties;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationAttachmentServiceTest {
    @TempDir Path directory;
    ConversationAttachmentMapper mapper;
    ConversationMapper conversations;
    AuthorizationService access;
    DeviceAuthenticationService authentication;
    ConversationAttachmentService service;
    ConversationPO conversation;
    AttachmentProperties properties;
    @BeforeEach void setup() {
        mapper=mock(ConversationAttachmentMapper.class); conversations=mock(ConversationMapper.class);
        var projects=mock(ProjectMapper.class); var devices=mock(AgentDeviceMapper.class);
        access=mock(AuthorizationService.class); authentication=mock(DeviceAuthenticationService.class);
        properties=new AttachmentProperties(); properties.setStorageDir(directory.toString());
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        service=new ConversationAttachmentService(mapper,conversations,projects,devices,access,authentication,properties,tx);
        conversation=new ConversationPO(); conversation.setId(3L);conversation.setProjectId(2L);conversation.setUserId(1L);conversation.setDeviceId(4L);conversation.setStatus("ACTIVE");
        when(conversations.selectOwnedConversation(2L,3L,1L)).thenReturn(conversation);
        when(conversations.selectConversation(3L)).thenReturn(conversation);
        var project=new ProjectPO();project.setStatus("ACTIVE");when(projects.selectOwned(2L,1L)).thenReturn(project);
    }
    @Test void storesBytesWithGeneratedKeyAndChecksum() throws Exception {
        doAnswer(i -> {((ConversationAttachmentPO)i.getArgument(0)).setId(9L);return 1;}).when(mapper).insert(any());
        var result=service.upload(2L,3L,1L,new MockMultipartFile("file","../../hello.txt","text/plain","hello".getBytes()));
        var saved=org.mockito.ArgumentCaptor.forClass(ConversationAttachmentPO.class);verify(mapper).insert(saved.capture());
        assertEquals("hello.txt",result.fileName());
        assertEquals(SecureDigests.sha256("hello"),result.sha256());
        assertEquals("hello",Files.readString(directory.resolve(saved.getValue().getStorageKey())));
        try(var files=Files.list(directory)){assertEquals(1,files.count());}
    }
    @Test void failedDatabaseInsertRemovesUnreferencedFile() {
        when(mapper.insert(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,() -> service.upload(2L,3L,1L,new MockMultipartFile("file","a.txt","text/plain",new byte[]{1})));
        assertDoesNotThrow(() -> {try(var files=Files.list(directory)){assertEquals(0,files.count());}});
    }
    @Test void refusesForeignConversationAndDuplicateAttachments() {
        var a=attachment();a.setConversationId(999L);when(mapper.lock(9L)).thenReturn(a);
        assertThrows(BusinessException.class,() -> service.bind(conversation,7L,List.of(9L)));
        assertThrows(BusinessException.class,() -> service.bind(conversation,7L,List.of(9L,9L)));
        verify(mapper,never()).link(any(),any(),anyInt());
    }
    @Test void enforcesTotalLimitBeforeBindingAnyAttachment() {
        var a=attachment();a.setSizeBytes(properties.getMaxTotalBytes()+1);when(mapper.lock(9L)).thenReturn(a);
        assertThrows(BusinessException.class,() -> service.bind(conversation,7L,List.of(9L)));
        verify(mapper,never()).attach(any());
    }
    @Test void deviceCannotDownloadAnotherTurnAttachmentOrCanceledTurn() {
        var device=new AgentDevicePO();device.setId(4L);when(authentication.authenticate("device","Bearer test")).thenReturn(device);
        var turn=new ConversationTurnPO();turn.setConversationId(3L);turn.setStatus("CREATED");when(conversations.selectTurn(7L)).thenReturn(turn);
        when(mapper.forTurn(7L)).thenReturn(List.of(attachment()));
        assertThrows(BusinessException.class,() -> service.agentDownload(7L,99L,"device","Bearer test"));
        turn.setStatus("INTERRUPTED");
        assertThrows(BusinessException.class,() -> service.agentManifest(7L,"device","Bearer test"));
    }
    @Test void revokedAssignmentBlocksCachedFileManifest() {
        var device=new AgentDevicePO();device.setId(4L);when(authentication.authenticate("device","Bearer test")).thenReturn(device);
        var turn=new ConversationTurnPO();turn.setConversationId(3L);turn.setStatus("CREATED");when(conversations.selectTurn(7L)).thenReturn(turn);
        doThrow(new BusinessException(com.myharness.codex.entity.enums.ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,4L);
        assertThrows(BusinessException.class,() -> service.agentManifest(7L,"device","Bearer test"));
    }
    @Test void cleanupDoesNotDeleteAttachmentBoundAfterExpirationScan() throws Exception {
        var a=attachment();a.setStatus("ATTACHED");a.setStorageKey(UUID.randomUUID().toString());
        Files.writeString(directory.resolve(a.getStorageKey()),"keep");
        when(mapper.expired(any())).thenReturn(List.of(a));when(mapper.lock(9L)).thenReturn(a);
        service.cleanup();
        assertTrue(Files.exists(directory.resolve(a.getStorageKey())));verify(mapper,never()).purge(any());
    }
    ConversationAttachmentPO attachment(){var a=new ConversationAttachmentPO();a.setId(9L);a.setUserId(1L);a.setProjectId(2L);a.setConversationId(3L);a.setStatus("PENDING");a.setSizeBytes(5);return a;}
}
