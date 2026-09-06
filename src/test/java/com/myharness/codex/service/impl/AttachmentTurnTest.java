package com.myharness.codex.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.StartTurnDTO;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.service.ConversationAttachmentService;
import com.myharness.codex.service.stream.ConversationMessageStream;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentTurnTest {
    ConversationMapper mapper; AgentCommandGateway gateway; ConversationAttachmentService attachments;
    ConversationServiceImpl service; ConversationPO conversation; AgentDevicePO device;
    Map<String,ConversationTurnPO> requests;
    @BeforeEach void setup() {
        mapper=mock(ConversationMapper.class);var devices=mock(AgentDeviceMapper.class);var projects=mock(ProjectMapper.class);
        gateway=mock(AgentCommandGateway.class);attachments=mock(ConversationAttachmentService.class);
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        var experts=mock(com.myharness.codex.service.ExpertService.class);
        when(experts.freeze(any(),any())).thenReturn(new com.myharness.codex.entity.dto.ExpertRuntimeDTO());
        service=new ConversationServiceImpl(mapper,devices,gateway,tx,mock(ApprovalMapper.class),new ObjectMapper(),projects,
                mock(ConversationMessageStream.class),mock(AuthorizationService.class),attachments,experts);
        conversation=new ConversationPO();conversation.setId(3L);conversation.setProjectId(2L);conversation.setUserId(1L);
        conversation.setDeviceId(4L);conversation.setDeviceCode("device");conversation.setWorkspaceName("workspace");conversation.setCodexThreadId("thread");
        when(mapper.selectOwnedConversation(2L,3L,1L)).thenReturn(conversation);when(mapper.lockConversation(3L)).thenReturn(conversation);
        var project=new ProjectPO();project.setStatus("ACTIVE");project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/project");when(projects.selectOwned(2L,1L)).thenReturn(project);
        device=new AgentDevicePO();device.setDeviceCode("device");device.setStatus("ONLINE");device.setIsolationMode("WINDOWS_PROJECT_PROFILE");device.setConversationAttachments(true);
        when(devices.selectById(4L)).thenReturn(device);when(gateway.isOnline("device")).thenReturn(true);
        requests=new HashMap<>();
        when(mapper.byClientRequest(eq(3L),anyString())).thenAnswer(i -> requests.get(i.getArgument(1)));
        when(mapper.insertTurn(any())).thenAnswer(i -> {ConversationTurnPO turn=i.getArgument(0);turn.setId(7L);requests.put(turn.getClientRequestId(),turn);return 1;});
        when(mapper.selectTurn(7L)).thenAnswer(i -> requests.values().iterator().next());
    }
    @Test void attachmentOnlyRequestIsBoundAndNetworkRetryDoesNotDispatchTwice() {
        var input=input();var first=service.startTurn(2L,3L,input,1L);var second=service.startTurn(2L,3L,input,1L);
        assertEquals(first.getId(),second.getId());assertEquals("DOWNLOADING",first.getPreparationPhase());
        verify(attachments).bind(conversation,7L,List.of(9L));verify(mapper).insertMessage(eq(3L),eq(7L),anyLong(),eq("USER"),eq("TEXT"),eq(""));
        verify(gateway,times(1)).send(eq("device"),any());
        input.setMessage("different");assertThrows(com.myharness.codex.exception.BusinessException.class,() -> service.startTurn(2L,3L,input,1L));
    }
    @Test void oldAgentCannotSilentlyDropAttachments() {
        device.setConversationAttachments(false);
        assertThrows(com.myharness.codex.exception.BusinessException.class,() -> service.startTurn(2L,3L,input(),1L));
        verify(mapper,never()).insertTurn(any());verify(gateway,never()).send(any(),any());
    }
    @Test void cancellationPersistsBeforeInterruptCommand() {
        service.startTurn(2L,3L,input(),1L);clearInvocations(gateway);
        service.interruptTurn(2L,3L,7L,1L);
        var order=inOrder(mapper,gateway);
        order.verify(mapper).finishTurn(eq(7L),eq(3L),eq(4L),eq("INTERRUPTED"),isNull(),anyString(),any());
        order.verify(gateway).send(eq("device"),any());
    }
    private StartTurnDTO input(){var input=new StartTurnDTO();input.setMessage("");input.setAttachmentIds(List.of(9L));input.setClientRequestId("request-1");return input;}
}
