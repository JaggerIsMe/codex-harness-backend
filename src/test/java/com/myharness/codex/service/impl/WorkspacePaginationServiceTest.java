package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.WorkspacePageQuery;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.service.ConversationAttachmentService;
import com.myharness.codex.service.ExpertService;
import com.myharness.codex.service.stream.ConversationMessageStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspacePaginationServiceTest {
    private ProjectMapper projects;
    private ConversationMapper conversations;
    private AuthorizationService access;
    private ProjectServiceImpl projectService;
    private ConversationServiceImpl conversationService;

    @BeforeEach
    void setup() {
        projects=mock(ProjectMapper.class);
        conversations=mock(ConversationMapper.class);
        access=mock(AuthorizationService.class);
        var devices=mock(AgentDeviceMapper.class);
        var gateway=mock(AgentCommandGateway.class);
        var transactions=mock(TransactionTemplate.class);
        projectService=new ProjectServiceImpl(projects,devices,access,gateway,transactions);
        conversationService=new ConversationServiceImpl(conversations,devices,gateway,transactions,
                mock(ApprovalMapper.class),new ObjectMapper(),projects,mock(ConversationMessageStream.class),access,
                mock(ConversationAttachmentService.class),mock(ExpertService.class));
        var project=new ProjectPO();
        project.setId(9L);project.setUserId(7L);project.setDeviceId(10L);
        project.setStatus("ACTIVE");project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/allowed");
        when(projects.selectOwned(9L,7L)).thenReturn(project);
    }

    @Test
    void returnsRequestedProjectPageAndCountUsingTheSameOwnerAndNormalizedKeyword() {
        var project=new ProjectPO();project.setId(3L);project.setProjectName("季度报告");
        when(projects.countOwnedProjects(7L,"季度")).thenReturn(43L);
        when(projects.selectOwnedProjects(7L,"季度",20,40L)).thenReturn(List.of(project));

        var page=projectService.getProjects(7L,3,20,"  季度  ");

        assertThat(page.items()).extracting(value -> value.getId()).containsExactly(3L);
        assertThat(page.total()).isEqualTo(43);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(20);
        var order=inOrder(access,projects);
        order.verify(access).requirePermission(7L,"project:read");
        order.verify(projects).countOwnedProjects(7L,"季度");
        order.verify(projects).selectOwnedProjects(7L,"季度",20,40L);
    }

    @Test
    void returnsLaterConversationPagesWithStatusProjectionWithoutReadingEveryConversation() {
        var conversation=conversation(1L);conversation.setLatestTurnId(90L);conversation.setLatestTurnStatus("RUNNING");
        when(conversations.countProjectConversations(9L,7L,"old")).thenReturn(203L);
        when(conversations.selectProjectConversations(9L,7L,"old",20,200L)).thenReturn(List.of(conversation));

        var page=conversationService.getProjectConversations(9L,7L,11,20," old ");

        assertThat(page.total()).isEqualTo(203);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().getLatestTurnStatus()).isEqualTo("RUNNING");
        verify(access).requirePermission(7L,"conversation:read");
        verify(access).requireDevice(7L,10L);
        verify(conversations,never()).selectLatestTurn(any());
        verify(conversations,never()).selectConversationStatuses(any(),any(),any());
    }

    @Test
    void rejectsInvalidBoundsAndLongKeywordsBeforeIssuingAnyListQuery() {
        for(int[] bounds:List.of(new int[]{0,20},new int[]{1,0},new int[]{1,101},new int[]{100001,20},new int[]{Integer.MAX_VALUE,100})) {
            assertInvalid(() -> projectService.getProjects(7L,bounds[0],bounds[1],null));
            assertInvalid(() -> conversationService.getProjectConversations(9L,7L,bounds[0],bounds[1],null));
        }
        assertInvalid(() -> projectService.getProjects(7L,1,20,"x".repeat(201)));
        assertInvalid(() -> conversationService.getProjectConversations(9L,7L,1,20,"x".repeat(201)));
        assertThat(new WorkspacePageQuery(100000,100,null).offset()).isEqualTo(9999900L);
        verify(projects,never()).countOwnedProjects(any(),any());
        verify(projects,never()).selectOwnedProjects(any(),any(),anyInt(),anyLong());
        verifyNoInteractions(conversations);
    }

    @Test
    void permissionAndProjectOwnershipChecksProtectBothCountsAndRows() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requirePermission(7L,"project:read");
        assertThrows(BusinessException.class,() -> projectService.getProjects(7L,1,20,null));
        var missing=assertThrows(BusinessException.class,() -> conversationService.getProjectConversations(9L,8L,1,20,null));
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(7L,10L);
        assertThrows(BusinessException.class,() -> conversationService.getProjectConversations(9L,7L,1,20,null));
        assertThrows(BusinessException.class,() -> conversationService.getConversationStatuses(9L,7L,List.of(1L)));
        verify(projects,never()).countOwnedProjects(any(),any());
        verifyNoInteractions(conversations);
    }

    @Test
    void statusRefreshOnlyQueriesTheRequestedIdsAndRetainsLatestTurnFields() {
        var first=conversation(101L);first.setLatestTurnId(900L);first.setLatestTurnStatus("FAILED");first.setLatestTurnFailureMessage("Disconnected");
        var second=conversation(2L);second.setLatestTurnHasIncompleteMessage(true);
        var ids=List.of(101L,2L);
        when(conversations.selectConversationStatuses(9L,7L,ids)).thenReturn(List.of(first,second));

        var result=conversationService.getConversationStatuses(9L,7L,ids);

        assertThat(result).extracting(value -> value.getId()).containsExactly(101L,2L);
        assertThat(result.getFirst().getLatestTurnFailureMessage()).isEqualTo("Disconnected");
        assertThat(result.get(1).getLatestTurnHasIncompleteMessage()).isTrue();
        verify(conversations).selectConversationStatuses(9L,7L,ids);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void statusRefreshRejectsEmptyOversizedDuplicateAndNonPositiveIdLists() {
        assertInvalid(() -> conversationService.getConversationStatuses(9L,7L,null));
        for(List<Long> ids:List.of(List.<Long>of(),List.of(0L),List.of(-1L),List.of(1L,1L),Arrays.asList(1L,null),LongStream.rangeClosed(1,101).boxed().toList()))
            assertInvalid(() -> conversationService.getConversationStatuses(9L,7L,ids));
        verifyNoInteractions(conversations);
    }

    @Test
    void statusRefreshRejectsForeignOrMissingIdsWithoutReturningPartialResults() {
        when(conversations.selectConversationStatuses(9L,7L,List.of(1L,2L))).thenReturn(List.of(conversation(1L)));
        var failure=assertThrows(BusinessException.class,() -> conversationService.getConversationStatuses(9L,7L,List.of(1L,2L)));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    private ConversationPO conversation(Long id) {
        var value=new ConversationPO();value.setId(id);value.setUserId(7L);value.setProjectId(9L);value.setDeviceId(10L);
        return value;
    }

    private void assertInvalid(Runnable action) {
        assertThat(assertThrows(BusinessException.class,action::run).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
