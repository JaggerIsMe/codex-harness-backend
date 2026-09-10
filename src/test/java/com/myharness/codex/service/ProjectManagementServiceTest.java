package com.myharness.codex.service;

import com.myharness.codex.entity.dto.UpdateConversationDTO;
import com.myharness.codex.entity.dto.UpdateProjectDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectManagementServiceTest {
    private ProjectManagementMapper mutations;
    private ProjectMapper projects;
    private ConversationMapper conversations;
    private AuthorizationService access;
    private PlatformTransactionManager manager;
    private ProjectManagementService service;
    private ProjectPO project;
    private ConversationPO conversation;

    @BeforeEach void setup() {
        mutations=mock(ProjectManagementMapper.class);projects=mock(ProjectMapper.class);
        conversations=mock(ConversationMapper.class);access=mock(AuthorizationService.class);
        manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new ProjectManagementService(mutations,projects,conversations,access,new TransactionTemplate(manager));
        project=new ProjectPO();project.setId(7L);project.setUserId(3L);project.setDeviceId(2L);
        project.setWorkspaceId(4L);project.setProjectName("Old project");project.setStatus("ACTIVE");
        conversation=new ConversationPO();conversation.setId(8L);conversation.setProjectId(7L);conversation.setUserId(3L);
        conversation.setDeviceId(2L);conversation.setWorkspaceId(4L);conversation.setTitle("Old conversation");
        conversation.setStatus("ACTIVE");conversation.setCodexThreadId("stable-thread");
        when(mutations.lockProject(7L,3L)).thenReturn(project);
        when(mutations.lockConversation(7L,8L,3L)).thenReturn(conversation);
        when(mutations.lockProjectConversations(7L)).thenReturn(List.of(8L,9L));
        when(mutations.lockActiveTurn(anyLong())).thenReturn(null);
        when(projects.selectOwned(7L,3L)).thenReturn(project);
        when(conversations.selectOwnedConversation(7L,8L,3L)).thenReturn(conversation);
    }

    @Test void renamesOnlyDisplayNamesWithoutRequiringAnOnlineDeviceOrChangingRuntimeBindings() {
        doAnswer(call -> {project.setProjectName(call.getArgument(2));return 1;})
                .when(mutations).renameProject(eq(7L),eq(3L),anyString());
        doAnswer(call -> {conversation.setTitle(call.getArgument(3));return 1;})
                .when(mutations).renameConversation(eq(7L),eq(8L),eq(3L),anyString());

        assertEquals("项目名称",service.updateProject(7L,new UpdateProjectDTO("  项目名称  "),3L).getProjectName());
        var renamed=service.updateConversation(7L,8L,new UpdateConversationDTO("  会话名称  "),3L);
        assertEquals("会话名称",renamed.getTitle());
        assertEquals("stable-thread",renamed.getCodexThreadId());
        verify(access).requirePermission(3L,"project:update");
        verify(access).requirePermission(3L,"conversation:update");
        verify(mutations,never()).disableWorkspace(anyLong(),anyLong());
        verify(mutations,never()).lockActiveTurn(anyLong());
    }

    @Test void rejectsBlankAndOverlongNamesBeforeAnyDatabaseMutation() {
        for(String name:List.of(" ","x".repeat(129)))
            assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(BusinessException.class,
                    () -> service.updateProject(7L,new UpdateProjectDTO(name),3L)).getErrorCode());
        for(String name:List.of("\n\t","x".repeat(256)))
            assertEquals(ErrorCode.INVALID_REQUEST,assertThrows(BusinessException.class,
                    () -> service.updateConversation(7L,8L,new UpdateConversationDTO(name),3L)).getErrorCode());
        verifyNoInteractions(mutations);
    }

    @Test void duplicateProjectNameIsAConflictAndRollsBack() {
        doThrow(new DuplicateKeyException("duplicate")).when(mutations).renameProject(7L,3L,"Existing");
        assertEquals(ErrorCode.CONFLICT,assertThrows(BusinessException.class,
                () -> service.updateProject(7L,new UpdateProjectDTO("Existing"),3L)).getErrorCode());
        verify(manager).rollback(any());
    }

    @Test void anotherUsersProjectIsInvisibleForEveryMutation() {
        List<Runnable> requests=List.of(
                () -> service.updateProject(7L,new UpdateProjectDTO("Name"),99L),
                () -> service.updateConversation(7L,8L,new UpdateConversationDTO("Name"),99L),
                () -> service.deleteProject(7L,99L),
                () -> service.deleteConversation(7L,8L,99L));
        for(Runnable request:requests)
            assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,request::run).getErrorCode());
        verify(mutations,never()).deleteProject(anyLong(),anyLong());
        verify(mutations,never()).deleteConversation(anyLong());
    }

    @Test void rejectsAConversationFromAnotherProjectBeforeRenamingOrDeletingIt() {
        when(mutations.lockConversation(7L,8L,3L)).thenReturn(null);
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,
                () -> service.updateConversation(7L,8L,new UpdateConversationDTO("Name"),3L)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,
                () -> service.deleteConversation(7L,8L,3L)).getErrorCode());
        verify(mutations,never()).deleteConversation(anyLong());
        verify(mutations,never()).renameConversation(anyLong(),anyLong(),anyLong(),anyString());
    }

    @Test void revokedDeviceAssignmentBlocksDeletion() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(3L,2L);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,
                () -> service.deleteProject(7L,3L)).getErrorCode());
        verify(mutations,never()).deleteProject(anyLong(),anyLong());
        verify(mutations,never()).deleteConversation(anyLong());
    }

    @Test void missingDeletePermissionBlocksEvenTheOwnerBeforeResourceLookup() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requirePermission(3L,"conversation:delete");
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,
                () -> service.deleteConversation(7L,8L,3L)).getErrorCode());
        verifyNoInteractions(mutations);
    }

    @Test void deletionRejectsAnActiveTurnAndLeavesHistoryUntouched() {
        when(mutations.lockActiveTurn(8L)).thenReturn(18L);
        assertEquals(ErrorCode.CONFLICT,assertThrows(BusinessException.class,
                () -> service.deleteConversation(7L,8L,3L)).getErrorCode());
        verify(mutations,never()).deleteMessageAttachments(anyLong());
        verify(mutations,never()).deleteConversation(anyLong());
        verify(manager).rollback(any());
    }

    @Test void projectDeletionChecksEveryConversationBeforeDeletingAnyHistory() {
        when(mutations.lockActiveTurn(9L)).thenReturn(19L);
        assertEquals(ErrorCode.CONFLICT,assertThrows(BusinessException.class,
                () -> service.deleteProject(7L,3L)).getErrorCode());
        verify(mutations,never()).deleteConversation(anyLong());
        verify(mutations,never()).disableWorkspace(anyLong(),anyLong());
    }

    @Test void deletingAnInitializingConversationRemovesAttachmentLinksBeforeCascadingItsHistory() {
        conversation.setCodexThreadId(null);
        service.deleteConversation(7L,8L,3L);
        var order=inOrder(mutations,manager);
        order.verify(mutations).lockProject(7L,3L);
        order.verify(mutations).lockConversation(7L,8L,3L);
        order.verify(mutations).lockActiveTurn(8L);
        order.verify(mutations).detachConversationTransfers(8L);
        order.verify(mutations).deleteMessageAttachments(8L);
        order.verify(mutations).deleteAttachments(8L);
        order.verify(mutations).deleteConversation(8L);
        order.verify(manager).commit(any());
        verify(mutations,never()).disableWorkspace(anyLong(),anyLong());
        verify(mutations,never()).deleteProject(anyLong(),anyLong());
    }

    @Test void deletingAPreparingProjectDisablesTheWorkspaceAndRemovesEveryConversationInOneTransaction() {
        project.setWorkspaceStatus("CREATING");
        service.deleteProject(7L,3L);
        verify(mutations).deleteConversation(8L);verify(mutations).deleteConversation(9L);
        var order=inOrder(mutations,manager);
        order.verify(mutations).deleteExpertBindings(7L);
        order.verify(mutations).cancelProjectTransfers(7L);
        order.verify(mutations).disableWorkspace(4L,2L);
        order.verify(mutations).deleteProject(7L,3L);
        order.verify(manager).commit(any());
        verify(access).requirePermission(3L,"project:delete");
    }

    @Test void cleanupFailureRollsBackDeletion() {
        doThrow(new IllegalStateException("database failure")).when(mutations).deleteAttachments(8L);
        assertThrows(IllegalStateException.class,() -> service.deleteProject(7L,3L));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
        verify(mutations,never()).deleteProject(anyLong(),anyLong());
    }
}
