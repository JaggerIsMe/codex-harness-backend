package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.*;
import com.myharness.codex.controller.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.*;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.*;
import com.myharness.codex.service.WorkspaceFileService;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(WorkspaceFileHttpSecurityTest.Config.class)
@WebAppConfiguration
class WorkspaceFileHttpSecurityTest {
    @TempDir Path storage;
    @Autowired WebApplicationContext context;
    @Autowired WorkspaceFileOperationMapper operations;
    @Autowired ProjectMapper projects;
    @Autowired AgentDeviceMapper devices;
    @Autowired UserAuthenticationService users;
    @Autowired AuthorizationService access;
    @Autowired AttachmentProperties properties;
    MockMvc mvc;
    WorkspaceFileOperationPO op;
    static final String AGENT="/api/v1/agent/workspace-file-operations/1";
    static final String USER="/api/v1/projects/2/workspace-files/operations/1/content";

    @BeforeEach void setup() throws Exception {
        reset(operations,projects,devices,users,access);properties.setStorageDir(storage.toString());
        op=new WorkspaceFileOperationPO();op.setId(1L);op.setProjectId(2L);op.setUserId(3L);op.setDeviceId(4L);
        op.setWorkspaceName("demo");op.setKind("UPLOAD_WORKSPACE_FILE");op.setStatus("RUNNING");op.setCursor("");
        op.setPath("报告.txt");op.setStorageKey(UUID.randomUUID().toString());op.setSizeBytes(5);op.setSha256(SecureDigests.sha256("hello"));
        Files.writeString(storage.resolve(op.getStorageKey()),"hello");when(operations.get(1L)).thenReturn(op);
        var p=new ProjectPO();p.setId(2L);p.setUserId(3L);p.setDeviceId(4L);p.setWorkspaceName("demo");p.setStatus("ACTIVE");
        when(projects.selectOwned(2L,3L)).thenReturn(p);
        var d=new AgentDevicePO();d.setId(4L);d.setStatus("ONLINE");d.setTokenHash(SecureDigests.sha256("device-token"));
        when(devices.selectByCode("device")).thenReturn(d);
        when(users.authenticate(anyString())).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        var u=new SysUserPO();u.setId(3L);u.setUsername("owner");doReturn(u).when(users).authenticate("user-token");
        when(access.permissions(3L)).thenReturn(List.of("workspace:use"));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @Test void deviceCredentialsAreRequiredOnEveryPermittedAgentRoute() throws Exception {
        for(var request:List.of(get(AGENT).servletPath(AGENT),get(AGENT+"/content").servletPath(AGENT+"/content"),
                put(AGENT+"/content").servletPath(AGENT+"/content").contentType("application/octet-stream").header("X-Content-SHA256",op.getSha256()).content("hello"))) {
            mvc.perform(request.header("X-Harness-Device-Code","device").header("Authorization","Bearer forged-token"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get(AGENT).servletPath(AGENT).header("X-Harness-Device-Code","device").header("Authorization","Bearer device-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("报告.txt"));
    }
    @Test void revokedAssignmentBlocksAgentAndFinalBrowserDownload() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(3L,4L);
        mvc.perform(get(AGENT+"/content").servletPath(AGENT+"/content").header("X-Harness-Device-Code","device").header("Authorization","Bearer device-token"))
                .andExpect(status().isForbidden());
        op.setKind("PREPARE_WORKSPACE_DOWNLOAD");op.setStatus("SUCCEEDED");
        mvc.perform(get(USER).servletPath(USER).header("Authorization","Bearer user-token")).andExpect(status().isForbidden());
    }
    @Test void ownerDownloadsPreparedBytesAndDeviceTokenCannotAccessUserRoute() throws Exception {
        op.setKind("PREPARE_WORKSPACE_DOWNLOAD");op.setStatus("SUCCEEDED");
        mvc.perform(get(USER).servletPath(USER).header("Authorization","Bearer user-token"))
                .andExpect(status().isOk()).andExpect(content().string("hello"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get(USER).servletPath(USER).header("Authorization","Bearer device-token")).andExpect(status().isUnauthorized());
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({UserSecurityConfig.class,AgentWorkspaceFileController.class,WorkspaceFileController.class,
            WorkspaceFileService.class,DeviceAuthenticationService.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean AttachmentProperties properties(){return new AttachmentProperties();}
        @Bean WorkspaceFileOperationMapper operations(){return mock(WorkspaceFileOperationMapper.class);}
        @Bean ProjectMapper projects(){return mock(ProjectMapper.class);}
        @Bean AgentDeviceMapper devices(){return mock(AgentDeviceMapper.class);}
        @Bean AuthorizationService access(){return mock(AuthorizationService.class);}
        @Bean UserAuthenticationService users(){return mock(UserAuthenticationService.class);}
        @Bean ClientEventWebSocketHandler events(){return mock(ClientEventWebSocketHandler.class);}
        @Bean AgentCommandGateway gateway(){return mock(AgentCommandGateway.class);}
        @Bean StringRedisTemplate redis(){return mock(StringRedisTemplate.class);}
    }
}
