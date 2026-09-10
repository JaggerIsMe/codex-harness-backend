package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.UserSecurityConfig;
import com.myharness.codex.config.WorkspaceFileProperties;
import com.myharness.codex.controller.ConversationAttachmentController;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.WorkspaceFileOperationVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.mapper.*;
import com.myharness.codex.service.ConversationAttachmentService;
import com.myharness.codex.service.WorkspaceFileService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AttachmentDownloadHttpSecurityTest.Config.class)
@WebAppConfiguration
class AttachmentDownloadHttpSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired ConversationAttachmentMapper attachments;
    @Autowired ConversationMapper conversations;
    @Autowired ProjectMapper projects;
    @Autowired AuthorizationService access;
    @Autowired UserAuthenticationService users;
    @Autowired WorkspaceFileService files;
    MockMvc mvc;
    ConversationAttachmentPO attachment;
    static final String PATH="/api/v1/projects/2/conversations/3/attachments/9/downloads";
    static final String BODY="{\"requestKey\":\"be798dc9-51a5-42b8-83d1-ceeb4738433a\"}";

    @BeforeEach void setup() {
        reset(attachments,conversations,projects,access,users,files);
        var user=new SysUserPO();user.setId(1L);user.setEmail("owner@example.test");
        when(users.authenticate(anyString())).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        doReturn(user).when(users).authenticate("user-token");when(access.permissions(1L)).thenReturn(List.of("workspace:use"));
        var project=new ProjectPO();project.setId(2L);project.setStatus("ACTIVE");when(projects.selectOwned(2L,1L)).thenReturn(project);
        var conversation=new ConversationPO();conversation.setId(3L);conversation.setProjectId(2L);conversation.setUserId(1L);conversation.setDeviceId(4L);
        when(conversations.selectOwnedConversation(2L,3L,1L)).thenReturn(conversation);
        attachment=new ConversationAttachmentPO();attachment.setId(9L);attachment.setProjectId(2L);attachment.setConversationId(3L);
        attachment.setUserId(1L);attachment.setWorkspacePath("archive/report.txt");attachment.setStatus("ATTACHED");
        when(attachments.find(3L,9L)).thenReturn(attachment);
        when(files.download(eq(2L),eq(1L),any())).thenReturn(new WorkspaceFileOperationVO("12","PREPARE_WORKSPACE_DOWNLOAD","archive/report.txt","QUEUED",null));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test void ownerDownloadsThroughCurrentAttachmentLocation() throws Exception {
        mvc.perform(post(PATH).servletPath(PATH).header("Authorization","Bearer user-token").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("archive/report.txt"));
        verify(files).download(eq(2L),eq(1L),argThat(request -> request.path().equals("archive/report.txt")));
    }

    @Test void anonymousAndForeignConversationCannotCreateDownload() throws Exception {
        mvc.perform(post(PATH).servletPath(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        String other=PATH.replace("conversations/3","conversations/99");
        mvc.perform(post(other).servletPath(other).header("Authorization","Bearer user-token").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound());
        verify(files,never()).download(any(),any(),any());
    }

    @Test void revokedAssignmentAndDeletedLocationCannotCreateDownload() throws Exception {
        attachment.setWorkspaceLocationState("MISSING");
        mvc.perform(post(PATH).servletPath(PATH).header("Authorization","Bearer user-token").contentType("application/json").content(BODY))
                .andExpect(status().isConflict());
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,4L);
        mvc.perform(post(PATH).servletPath(PATH).header("Authorization","Bearer user-token").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        verify(files,never()).download(any(),any(),any());
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({UserSecurityConfig.class,ConversationAttachmentController.class,ConversationAttachmentService.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ConversationAttachmentMapper attachments(){return mock(ConversationAttachmentMapper.class);}
        @Bean ConversationMapper conversations(){return mock(ConversationMapper.class);}
        @Bean ProjectMapper projects(){return mock(ProjectMapper.class);}
        @Bean AgentDeviceMapper devices(){return mock(AgentDeviceMapper.class);}
        @Bean AuthorizationService access(){return mock(AuthorizationService.class);}
        @Bean DeviceAuthenticationService deviceAuthentication(){return mock(DeviceAuthenticationService.class);}
        @Bean UserAuthenticationService users(){return mock(UserAuthenticationService.class);}
        @Bean WorkspaceFileProperties properties(){return new WorkspaceFileProperties();}
        @Bean WorkspaceFileService files(){return mock(WorkspaceFileService.class);}
        @Bean ExpertMapper experts(){return mock(ExpertMapper.class);}
        @Bean com.myharness.codex.service.WorkspaceAttachmentLocationService locations(){return mock(com.myharness.codex.service.WorkspaceAttachmentLocationService.class);}
        @Bean TransactionTemplate transactions(){
            var tx=mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
            return tx;
        }
    }
}
