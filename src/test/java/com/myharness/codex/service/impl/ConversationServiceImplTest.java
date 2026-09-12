package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ConversationTurnPO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.TurnVO;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {
    private final com.myharness.codex.service.ExpertService experts=org.mockito.Mockito.mock(com.myharness.codex.service.ExpertService.class);
    @Mock private ConversationMapper conversationMapper;
    @Mock private AgentDeviceMapper deviceMapper;
    @Mock private AgentCommandGateway gateway;
    @Mock private TransactionTemplate transactions;
    @Mock private ApprovalMapper approvalMapper;
    private ObjectMapper objectMapper=new ObjectMapper();
    @Mock private ProjectMapper projectMapper;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(conversationMapper, deviceMapper, gateway, transactions,
                approvalMapper, objectMapper, projectMapper,org.mockito.Mockito.mock(com.myharness.codex.service.stream.ConversationMessageStream.class),
                org.mockito.Mockito.mock(com.myharness.codex.security.AuthorizationService.class),
                org.mockito.Mockito.mock(com.myharness.codex.service.ConversationAttachmentService.class),experts);
        org.mockito.Mockito.lenient().when(experts.freeze(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(new com.myharness.codex.entity.dto.ExpertRuntimeDTO());
    }

    @Test
    void returnsRecentConversationsForCurrentUser() {
        ConversationPO first = conversation(8L, 3L, "最近会话");
        ConversationPO second = conversation(6L, 3L, "较早会话");
        first.setLatestTurnId(25L); first.setLatestTurnStatus("RUNNING");
        second.setLatestTurnId(18L); second.setLatestTurnStatus("COMPLETED");
        second.setLatestTurnHasIncompleteMessage(true);
        com.myharness.codex.entity.po.ProjectPO project = new com.myharness.codex.entity.po.ProjectPO();
        project.setId(5L); project.setStatus("ACTIVE");
        project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/allowed");
        when(projectMapper.selectOwned(5L,3L)).thenReturn(project);
        when(conversationMapper.selectProjectConversations(5L,3L,"",20,0L)).thenReturn(Arrays.asList(first, second));
        when(conversationMapper.countProjectConversations(5L,3L,"")).thenReturn(2L);

        var page = service.getProjectConversations(5L,3L,1,20,null);
        List<ConversationVO> result = page.items();

        assertEquals(2, result.size());
        assertEquals(2L, page.total());
        assertEquals(8L, result.get(0).getId());
        assertEquals("较早会话", result.get(1).getTitle());
        var json = objectMapper.valueToTree(result);
        assertEquals(25L,json.get(0).path("latestTurnId").asLong());
        assertEquals("RUNNING",json.get(0).path("latestTurnStatus").asText());
        assertEquals("COMPLETED",json.get(1).path("latestTurnStatus").asText());
        assertEquals(true,json.get(1).path("latestTurnHasIncompleteMessage").asBoolean());
        org.mockito.Mockito.verify(conversationMapper,org.mockito.Mockito.never()).selectLatestTurn(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(conversationMapper,org.mockito.Mockito.never()).selectActiveTurn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exposesLatestTurnFailureInOwnedConversationDetail() {
        ConversationPO conversation = conversation(8L,3L,"会话");
        conversation.setLatestTurnId(25L);
        conversation.setLatestTurnStatus("FAILED");
        conversation.setLatestTurnFailureMessage("Agent command timed out");
        when(conversationMapper.selectOwnedConversation(5L,8L,3L)).thenReturn(conversation);

        var json = objectMapper.valueToTree(service.getConversation(5L,8L,3L));

        assertEquals(25L,json.path("latestTurnId").asLong());
        assertEquals("FAILED",json.path("latestTurnStatus").asText());
        assertEquals("Agent command timed out",json.path("latestTurnFailureMessage").asText());
        org.mockito.Mockito.verify(conversationMapper,org.mockito.Mockito.never()).selectLatestTurn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void restoresActiveTurnAfterConversationOwnershipCheck() {
        ConversationPO conversation = conversation(8L, 3L, "会话");
        ConversationTurnPO turn = new ConversationTurnPO();
        turn.setId(15L); turn.setConversationId(8L); turn.setStatus("WAITING_APPROVAL");
        when(conversationMapper.selectOwnedConversation(5L,8L,3L)).thenReturn(conversation);
        when(conversationMapper.selectActiveTurn(8L)).thenReturn(turn);

        TurnVO result = service.getActiveTurn(5L,8L,3L);

        assertEquals(15L, result.getId());
        assertEquals("WAITING_APPROVAL", result.getStatus());
    }

    @Test
    void returnsNullWhenConversationHasNoActiveTurn() {
        when(conversationMapper.selectOwnedConversation(5L,8L,3L)).thenReturn(conversation(8L, 3L, "会话"));
        when(conversationMapper.selectActiveTurn(8L)).thenReturn(null);

        assertNull(service.getActiveTurn(5L,8L,3L));
    }

    private ConversationPO conversation(Long id, Long userId, String title) {
        ConversationPO value = new ConversationPO();
        value.setId(id); value.setUserId(userId); value.setDeviceId(2L); value.setWorkspaceId(4L);
        value.setProjectId(5L);
        value.setTitle(title); value.setStatus("ACTIVE");
        return value;
    }

    @Test
    void refusesOldWindowsThreadBeforeCreatingOrDispatchingTurn() {
        var project=new com.myharness.codex.entity.po.ProjectPO();
        project.setId(5L);project.setDeviceId(2L);project.setStatus("ACTIVE");project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/allowed");
        when(projectMapper.selectOwned(5L,3L)).thenReturn(project);
        var conversation=conversation(2L,3L,"旧会话");conversation.setCodexThreadId("old-thread");
        when(conversationMapper.selectOwnedConversation(5L,2L,3L)).thenReturn(conversation);
        when(conversationMapper.lockConversation(2L)).thenReturn(conversation);
        var device=new com.myharness.codex.entity.po.AgentDevicePO();
        device.setStatus("ONLINE");device.setIsolationMode("WINDOWS_PROJECT_PROFILE");
        when(deviceMapper.selectById(2L)).thenReturn(device);
        when(transactions.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(null));
        var request=new com.myharness.codex.entity.dto.StartTurnDTO();request.setMessage("读取外部文件");
        org.junit.jupiter.api.Assertions.assertThrows(com.myharness.codex.exception.BusinessException.class,
                ()->service.startTurn(5L,2L,request,3L));
        org.mockito.Mockito.verify(conversationMapper,org.mockito.Mockito.never()).insertTurn(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void sendsPersistedConversationBindingWithEveryTurnForAgentRecovery() {
        var project=new com.myharness.codex.entity.po.ProjectPO();
        project.setId(5L);project.setDeviceId(2L);project.setStatus("ACTIVE");project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/allowed");
        when(projectMapper.selectOwned(5L,3L)).thenReturn(project);
        ConversationPO conversation = conversation(2L, 3L, "旅游规划");
        conversation.setCodexThreadId("original-codex-thread");
        conversation.setWorkspaceName("allowed-workspace");
        conversation.setDeviceCode("device-1");
        when(conversationMapper.selectOwnedConversation(5L, 2L, 3L)).thenReturn(conversation);
        var device = new com.myharness.codex.entity.po.AgentDevicePO();
        device.setDeviceCode("device-1"); device.setStatus("ONLINE"); device.setIsolationMode("LINUX_PROJECT_PROFILE_V1");
        when(deviceMapper.selectById(2L)).thenReturn(device);
        when(gateway.isOnline("device-1")).thenReturn(true);
        var turn = new ConversationTurnPO();
        turn.setId(7L); turn.setConversationId(2L); turn.setStatus("CREATED");
        when(transactions.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                ((org.springframework.transaction.support.TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class)));
        when(conversationMapper.lockConversation(2L)).thenReturn(conversation);
        org.mockito.Mockito.doAnswer(invocation -> { ((ConversationTurnPO)invocation.getArgument(0)).setId(7L); return 1; }).when(conversationMapper).insertTurn(org.mockito.ArgumentMatchers.any());
        when(conversationMapper.selectTurn(7L)).thenReturn(turn);
        when(conversationMapper.canRecreateUnstartedThread(2L,7L)).thenReturn(true);
        var request = new com.myharness.codex.entity.dto.StartTurnDTO();
        request.setMessage("你好");

        service.startTurn(5L, 2L, request, 3L);

        var order=org.mockito.Mockito.inOrder(conversationMapper,gateway);
        order.verify(conversationMapper).touchActivity(org.mockito.ArgumentMatchers.eq(2L),org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(3L),org.mockito.ArgumentMatchers.any());
        order.verify(gateway).send(org.mockito.ArgumentMatchers.eq("device-1"),org.mockito.ArgumentMatchers.any());

        var sent = org.mockito.ArgumentCaptor.forClass(com.myharness.codex.gateway.AgentCommand.class);
        org.mockito.Mockito.verify(gateway).send(org.mockito.ArgumentMatchers.eq("device-1"), sent.capture());
        var payload = new ObjectMapper().valueToTree(sent.getValue().getPayload());
        assertEquals("START_TURN", sent.getValue().getType());
        assertEquals("5", payload.path("projectId").asText());
        assertEquals("allowed-workspace", payload.path("workspaceName").asText());
        assertEquals("original-codex-thread", payload.path("codexThreadId").asText());
        assertEquals("2", payload.path("conversationId").asText());
        assertEquals("7", payload.path("turnId").asText());
        assertEquals(true,payload.path("recreateUnstartedThread").asBoolean());
    }
}
