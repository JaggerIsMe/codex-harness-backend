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
    @Mock private ConversationMapper conversationMapper;
    @Mock private AgentDeviceMapper deviceMapper;
    @Mock private AgentCommandGateway gateway;
    @Mock private TransactionTemplate transactions;
    @Mock private ApprovalMapper approvalMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProjectMapper projectMapper;
    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(conversationMapper, deviceMapper, gateway, transactions,
                approvalMapper, objectMapper, projectMapper,org.mockito.Mockito.mock(com.myharness.codex.service.stream.ConversationMessageStream.class));
    }

    @Test
    void returnsRecentConversationsForCurrentUser() {
        ConversationPO first = conversation(8L, 3L, "最近会话");
        ConversationPO second = conversation(6L, 3L, "较早会话");
        com.myharness.codex.entity.po.ProjectPO project = new com.myharness.codex.entity.po.ProjectPO();
        project.setId(5L); project.setStatus("ACTIVE");
        when(projectMapper.selectOwned(5L,3L)).thenReturn(project);
        when(conversationMapper.selectProjectConversations(5L,3L)).thenReturn(Arrays.asList(first, second));

        List<ConversationVO> result = service.getProjectConversations(5L,3L);

        assertEquals(2, result.size());
        assertEquals(8L, result.get(0).getId());
        assertEquals("较早会话", result.get(1).getTitle());
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
}
