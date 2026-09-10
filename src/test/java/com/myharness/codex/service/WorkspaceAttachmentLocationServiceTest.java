package com.myharness.codex.service;

import com.myharness.codex.entity.po.ConversationAttachmentPO;
import com.myharness.codex.mapper.ConversationAttachmentMapper;
import com.myharness.codex.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceAttachmentLocationServiceTest {
    ConversationAttachmentMapper attachments;
    ConversationMapper conversations;
    WorkspaceAttachmentLocationService service;
    final List<ConversationAttachmentPO> rows=new ArrayList<>();

    @BeforeEach void setup() {
        attachments=mock(ConversationAttachmentMapper.class);
        conversations=mock(ConversationMapper.class);
        service=new WorkspaceAttachmentLocationService(attachments,conversations);
        when(attachments.atLocation(anyLong(),anyString())).thenAnswer(call -> rows.stream()
                .filter(row -> row.getProjectId().equals(call.getArgument(0)))
                .filter(row -> row.getWorkspacePath().equals(call.getArgument(1)) || row.getWorkspacePath().startsWith(call.getArgument(1)+"/"))
                .toList());
        when(attachments.lock(anyLong())).thenAnswer(call -> rows.stream().filter(row -> row.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(attachments.claimLocation(anyLong(),anyLong(),anyLong())).thenAnswer(call -> {
            var row=find(call.getArgument(0));
            if(row.getLocationRevision()!=(long)call.getArgument(1)) return 0;
            row.setLastFileOperationId(call.getArgument(2));return 1;
        });
        when(attachments.updateLocation(anyLong(),anyLong(),anyLong(),anyString(),anyString())).thenAnswer(call -> {
            var row=find(call.getArgument(0));
            if(row.getLocationRevision()!=(long)call.getArgument(1) || !Objects.equals(row.getLastFileOperationId(),call.getArgument(2))) return 0;
            row.setWorkspacePath(call.getArgument(3));row.setWorkspaceLocationState(call.getArgument(4));
            row.setLocationRevision(row.getLocationRevision()+1);return 1;
        });
    }

    @Test void directoryRenameUpdatesPendingAndSentAssociationsOnlyWithinPathBoundary() {
        var pending=add(1L,3L,"reports/a.txt","AVAILABLE");
        var sent=add(2L,4L,"reports/nested/b.png","AVAILABLE");sent.setStatus("ATTACHED");
        var sibling=add(3L,3L,"reports-old/a.txt","AVAILABLE");
        service.claim(2L,"reports",10L);
        service.apply(2L,"reports","archive",10L,"SUCCEEDED",List.of(),List.of());
        assertEquals("archive/a.txt",pending.getWorkspacePath());assertEquals("archive/nested/b.png",sent.getWorkspacePath());
        assertEquals("reports-old/a.txt",sibling.getWorkspacePath());assertEquals("original.txt",sent.getFileName());
        assertEquals("original-hash",sent.getSha256());assertEquals(7L,sent.getWorkspaceOperationId());
        service.apply(2L,"reports","archive",10L,"SUCCEEDED",List.of(),List.of());
        assertEquals(1,pending.getLocationRevision());
        var order=inOrder(conversations,attachments);
        order.verify(conversations).lockConversation(3L);order.verify(conversations).lockConversation(4L);
        order.verify(attachments).lock(1L);order.verify(attachments).lock(2L);
    }

    @Test void deletedHistoryDoesNotFollowNewFileAtTheSamePath() {
        var old=add(1L,3L,"a.txt","MISSING");old.setLastFileOperationId(9L);
        var current=add(2L,3L,"a.txt","AVAILABLE");
        service.claim(2L,"a.txt",10L);
        service.apply(2L,"a.txt","b.txt",10L,"SUCCEEDED",List.of(),List.of());
        assertEquals("a.txt",old.getWorkspacePath());assertEquals("MISSING",old.getWorkspaceLocationState());
        assertEquals(9L,old.getLastFileOperationId());assertEquals("b.txt",current.getWorkspacePath());
    }

    @Test void unknownIsResolvedByItsOriginalOperationOnly() {
        var value=add(1L,3L,"a.txt","AVAILABLE");
        service.claim(2L,"a.txt",10L);
        service.apply(2L,"a.txt","b.txt",10L,"UNKNOWN",List.of(),List.of());
        assertEquals("UNKNOWN",value.getWorkspaceLocationState());assertEquals(1,value.getLocationRevision());
        service.claim(2L,"a.txt",11L);
        service.apply(2L,"a.txt","c.txt",11L,"SUCCEEDED",List.of(),List.of());
        assertEquals("a.txt",value.getWorkspacePath());
        service.apply(2L,"a.txt","b.txt",10L,"SUCCEEDED",List.of(),List.of());
        assertEquals("b.txt",value.getWorkspacePath());assertEquals("AVAILABLE",value.getWorkspaceLocationState());
        assertEquals(2,value.getLocationRevision());
    }

    @Test void partialDeletionMarksOnlyConfirmedAndUncertainFiles() {
        var removed=add(1L,3L,"dir/a.txt","AVAILABLE");
        var remaining=add(2L,3L,"dir/b.txt","AVAILABLE");
        var uncertain=add(3L,4L,"dir/c.txt","AVAILABLE");
        service.claim(2L,"dir",10L);
        service.apply(2L,"dir",null,10L,"PARTIAL_FAILED",List.of("dir/a.txt"),List.of("dir/c.txt"));
        assertEquals("MISSING",removed.getWorkspaceLocationState());assertEquals("AVAILABLE",remaining.getWorkspaceLocationState());
        assertEquals("UNKNOWN",uncertain.getWorkspaceLocationState());
    }

    @Test void definiteNoEffectRestoresUnknownWithoutChangingPathAndReplayDoesNotBumpRevision() {
        var value=add(1L,3L,"a.txt","AVAILABLE");service.claim(2L,"a.txt",10L);
        service.apply(2L,"a.txt",null,10L,"UNKNOWN",List.of(),List.of());
        service.apply(2L,"a.txt",null,10L,"FAILED",List.of(),List.of());
        service.apply(2L,"a.txt",null,10L,"FAILED",List.of(),List.of());
        assertEquals("AVAILABLE",value.getWorkspaceLocationState());assertEquals("a.txt",value.getWorkspacePath());
        assertEquals(2,value.getLocationRevision());
    }

    private ConversationAttachmentPO add(Long id,Long cid,String path,String state) {
        var row=new ConversationAttachmentPO();row.setId(id);row.setConversationId(cid);row.setProjectId(2L);
        row.setStatus("PENDING");row.setWorkspacePath(path);row.setWorkspaceLocationState(state);
        row.setFileName("original.txt");row.setSha256("original-hash");row.setWorkspaceOperationId(7L);rows.add(row);return row;
    }
    private ConversationAttachmentPO find(Long id) {return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();}
}
