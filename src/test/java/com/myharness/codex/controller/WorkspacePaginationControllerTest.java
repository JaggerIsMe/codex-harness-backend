package com.myharness.codex.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.security.UserPrincipal;
import com.myharness.codex.service.ConversationAttachmentService;
import com.myharness.codex.service.ExpertService;
import com.myharness.codex.service.ModelConfigurationService;
import com.myharness.codex.service.impl.ConversationServiceImpl;
import com.myharness.codex.service.impl.ProjectServiceImpl;
import com.myharness.codex.service.stream.ConversationMessageStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspacePaginationControllerTest {
    private MockMvc mvc;
    private ProjectMapper projects;
    private ConversationMapper conversations;

    @BeforeEach
    void setup() {
        projects=mock(ProjectMapper.class);conversations=mock(ConversationMapper.class);
        var devices=mock(AgentDeviceMapper.class);
        var access=mock(AuthorizationService.class);
        var gateway=mock(AgentCommandGateway.class);
        var transactions=mock(TransactionTemplate.class);
        var projectService=new ProjectServiceImpl(projects,devices,access,gateway,transactions);
        var conversationService=new ConversationServiceImpl(conversations,devices,gateway,transactions,
                mock(ApprovalMapper.class),new ObjectMapper(),projects,mock(ConversationMessageStream.class),access,
                mock(ConversationAttachmentService.class),mock(ExpertService.class),mock(ModelConfigurationService.class));
        mvc=MockMvcBuilders.standaloneSetup(new ProjectController(projectService),new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UserContext.set(new UserPrincipal(7L,"user","User"));
        var project=new ProjectPO();project.setId(9L);project.setDeviceId(10L);project.setStatus("ACTIVE");
        project.setWorkspaceStatus("ENABLED");project.setRootPath("D:/allowed");
        when(projects.selectOwned(9L,7L)).thenReturn(project);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void defaultsProjectsToTwentyAndConversationsToTenWithTheUnifiedPageEnvelope() throws Exception {
        when(projects.countOwnedProjects(7L,"")).thenReturn(125L);
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.data.total").value(125))
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20));
        verify(projects).selectOwnedProjects(7L,"",20,0L);

        mvc.perform(get("/api/v1/projects/9/conversations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(10));
        verify(conversations).selectProjectConversations(9L,7L,"",10,0L);
    }

    @Test
    void projectDetailsAndConversationStatusExposeTheirActivityTimestamps() throws Exception {
        var activity=java.time.LocalDateTime.of(2026,9,10,16,30);
        var project=projects.selectOwned(9L,7L);project.setLastActivityAt(activity);
        var conversation=new ConversationPO();conversation.setId(101L);conversation.setLastActivityAt(activity);
        when(conversations.selectConversationStatuses(9L,7L,List.of(101L))).thenReturn(List.of(conversation));

        mvc.perform(get("/api/v1/projects/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastActivityAt").exists());
        mvc.perform(get("/api/v1/projects/9/conversations/status").param("ids","101")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lastActivityAt").exists());
    }

    @Test
    void acceptsExplicitPageSizeAndKeywordForBothLists() throws Exception {
        for(String path:List.of("/api/v1/projects","/api/v1/projects/9/conversations"))
            mvc.perform(get(path).param("page","4").param("size","50").param("keyword"," old "))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(4))
                    .andExpect(jsonPath("$.data.size").value(50));
        verify(projects).selectOwnedProjects(7L,"old",50,150L);
        verify(conversations).selectProjectConversations(9L,7L,"old",50,150L);
    }

    @Test
    void invalidAndMalformedPageParametersUseTheUnifiedFourHundredResponse() throws Exception {
        for(String path:List.of("/api/v1/projects","/api/v1/projects/9/conversations")) {
            for(String page:List.of("0","100001","2147483648","bad"))
                mvc.perform(get(path).param("page",page)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.status").value("error")).andExpect(jsonPath("$.code").value(400));
            mvc.perform(get(path).param("size","101")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("keyword","x".repeat(201))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void boundedStatusRouteParsesCommaSeparatedIdsAndDoesNotShadowConversationDetail() throws Exception {
        var conversation=new ConversationPO();conversation.setId(101L);conversation.setLatestTurnId(901L);conversation.setLatestTurnStatus("RUNNING");
        when(conversations.selectConversationStatuses(9L,7L,List.of(101L))).thenReturn(List.of(conversation));
        mvc.perform(get("/api/v1/projects/9/conversations/status").param("ids","101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(101))
                .andExpect(jsonPath("$.data[0].latestTurnStatus").value("RUNNING"));

        var second=new ConversationPO();second.setId(2L);
        when(conversations.selectConversationStatuses(9L,7L,List.of(101L,2L))).thenReturn(List.of(conversation,second));
        mvc.perform(get("/api/v1/projects/9/conversations/status").param("ids","101,2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        verify(conversations).selectConversationStatuses(9L,7L,List.of(101L,2L));
        when(conversations.selectOwnedConversation(9L,101L,7L)).thenReturn(conversation);
        mvc.perform(get("/api/v1/projects/9/conversations/101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(101));
    }

    @Test
    void missingEmptyMalformedAndDuplicateStatusIdsAreRejectedWithoutAListRead() throws Exception {
        String path="/api/v1/projects/9/conversations/status";
        mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        for(String ids:List.of("","0","-1","1,1","1,,2","one","9223372036854775808"))
            mvc.perform(get(path).param("ids",ids)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("error")).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(conversations);
    }
}
