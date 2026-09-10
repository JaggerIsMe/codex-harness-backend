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
import com.myharness.codex.service.WorkspaceFilePreviewService;
import com.myharness.codex.service.WorkspaceAttachmentLocationService;
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
    @Autowired WorkspaceFileProperties properties;
    @Autowired ExpertMapper experts;
    @Autowired AgentCommandGateway gateway;
    MockMvc mvc;
    WorkspaceFileOperationPO op;
    static final String AGENT="/api/v1/agent/workspace-file-operations/1";
    static final String USER="/api/v1/projects/2/workspace-files/operations/1/content";

    @BeforeEach void setup() throws Exception {
        reset(operations,projects,devices,users,access,experts,gateway);properties.setStorageDir(storage.toString());
        when(experts.lockProject(2L)).thenReturn(0L);
        op=new WorkspaceFileOperationPO();op.setId(1L);op.setProjectId(2L);op.setUserId(3L);op.setDeviceId(4L);
        op.setWorkspaceName("demo");op.setKind("UPLOAD_WORKSPACE_FILE");op.setStatus("RUNNING");op.setCursor("");
        op.setPath("报告.txt");op.setStorageKey(UUID.randomUUID().toString());op.setSizeBytes(5);op.setSha256(SecureDigests.sha256("hello"));
        Files.writeString(storage.resolve(op.getStorageKey()),"hello");when(operations.get(1L)).thenReturn(op);
        var p=new ProjectPO();p.setId(2L);p.setUserId(3L);p.setDeviceId(4L);p.setWorkspaceName("demo");p.setStatus("ACTIVE");
        p.setDeviceCode("device");p.setWorkspaceStatus("ENABLED");when(gateway.isOnline("device")).thenReturn(true);
        when(projects.selectOwned(2L,3L)).thenReturn(p);
        var d=new AgentDevicePO();d.setId(4L);d.setStatus("ONLINE");d.setTokenHash(SecureDigests.sha256("device-token"));
        when(devices.selectByCode("device")).thenReturn(d);
        d.setWorkspaceFiles(true);when(devices.selectById(4L)).thenReturn(d);
        when(users.authenticate(anyString())).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        var u=new SysUserPO();u.setId(3L);u.setEmail("owner@example.test");doReturn(u).when(users).authenticate("user-token");
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
    @Test void previewRechecksOwnershipStateHiddenPathsAndAssignment() throws Exception {
        String preview=USER.replace("/content","/preview");
        op.setKind("PREPARE_WORKSPACE_DOWNLOAD");op.setStatus("SUCCEEDED");
        mvc.perform(get(preview).servletPath(preview).header("Authorization","Bearer user-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kind").value("TEXT"))
                .andExpect(jsonPath("$.data.sha256").value(op.getSha256()))
                .andExpect(jsonPath("$.data.fileName").value("报告.txt"));
        mvc.perform(get(preview).servletPath(preview)).andExpect(status().isUnauthorized());
        op.setProjectId(99L);
        mvc.perform(get(preview).servletPath(preview).header("Authorization","Bearer user-token")).andExpect(status().isNotFound());
        op.setProjectId(2L);op.setStatus("EXPIRED");
        mvc.perform(get(preview).servletPath(preview).header("Authorization","Bearer user-token")).andExpect(status().isBadRequest());
        op.setStatus("SUCCEEDED");
        for(String path:List.of(".git/config","docs/.CODEX/config",".harness/out.txt",".agent/a",".agents/skill.md")) {
            op.setPath(path);
            mvc.perform(get(preview).servletPath(preview).header("Authorization","Bearer user-token")).andExpect(status().isBadRequest());
        }
        op.setPath("报告.txt");
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(3L,4L);
        mvc.perform(get(preview).servletPath(preview).header("Authorization","Bearer user-token")).andExpect(status().isForbidden());
    }
    @Test void allNewUserRoutesRequireUserAuthentication() throws Exception {
        String base="/api/v1/projects/2/workspace-files";
        for(String suffix:List.of("/renames","/moves","/delete-plans","/deletions","/archive-downloads","/operations/1/reconcile")) {
            String path=base+suffix;
            mvc.perform(post(path).servletPath(path).contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\"}"))
                    .andExpect(status().isUnauthorized());
        }
        for(String suffix:List.of("/operations","/operations/1/items")) {
            String path=base+suffix;
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        }
        verify(operations,never()).insert(any());
    }
    @Test void newMutationsRequireTurnPermissionAndOldAgentsAreRejected() throws Exception {
        String path="/api/v1/projects/2/workspace-files/renames";
        String body="{\"requestKey\":\""+UUID.randomUUID()+"\",\"path\":\"a.txt\",\"name\":\"b.txt\",\"expectedRevision\":\"revision\"}";
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requirePermission(3L,"turn:start");
        mvc.perform(post(path).servletPath(path).header("Authorization","Bearer user-token").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        doNothing().when(access).requirePermission(3L,"turn:start");
        mvc.perform(post(path).servletPath(path).header("Authorization","Bearer user-token").contentType("application/json").content(body))
                .andExpect(status().isConflict());
        verify(operations,never()).insert(any());
    }
    @Test void operationDetailsRemainProjectScopedAndAssignmentRevocationAppliesToRecentList() throws Exception {
        String details="/api/v1/projects/2/workspace-files/operations/1/items";
        op.setProjectId(99L);
        mvc.perform(get(details).servletPath(details).header("Authorization","Bearer user-token"))
                .andExpect(status().isNotFound());
        verify(operations,never()).items(any(),anyLong(),anyInt());
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(3L,4L);
        String recent="/api/v1/projects/2/workspace-files/operations";
        mvc.perform(get(recent).servletPath(recent).header("Authorization","Bearer user-token"))
                .andExpect(status().isForbidden());
    }
    @Test void agentItemUploadAuthenticatesDeviceAndRejectsDifferentOperationOwner() throws Exception {
        String path=AGENT+"/items",body="{\"items\":[]}";
        mvc.perform(put(path).servletPath(path).header("X-Harness-Device-Code","device").header("Authorization","Bearer forged-token")
                        .header("X-Content-SHA256",SecureDigests.sha256(body)).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        op.setDeviceId(999L);
        mvc.perform(put(path).servletPath(path).header("X-Harness-Device-Code","device").header("Authorization","Bearer device-token")
                        .header("X-Content-SHA256",SecureDigests.sha256(body)).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
    }
    @Test void authorizationManifestRetainsEveryNewCommandField() throws Exception {
        var json=new ObjectMapper();
        for(String kind:List.of("RELOCATE_WORKSPACE_ENTRY","PREPARE_WORKSPACE_ARCHIVE","DELETE_WORKSPACE_ENTRY")) {
            op.setKind(kind);
            var command=new com.myharness.codex.entity.dto.WorkspaceFileCommandDTO("1","2","demo",op.getPath(),"",0,null,
                    "archive/report.txt","entry-revision","1ad51e77-3ac1-45a7-a751-c21c02e253a3","a".repeat(64),
                    List.of(new com.myharness.codex.entity.dto.WorkspaceFileActionRequestDTO.Item("reports/a.txt","file-revision")),
                    "b".repeat(64),new com.myharness.codex.entity.dto.WorkspaceFileCommandDTO.Limits(100,20_971_520,104_857_600,115_343_360,524_288,300),null);
            op.setPayloadJson(json.writeValueAsString(command));
            var response=mvc.perform(get(AGENT).servletPath(AGENT).header("X-Harness-Device-Code","device").header("Authorization","Bearer device-token"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertEquals(command,json.treeToValue(json.readTree(response).get("data"),
                    com.myharness.codex.entity.dto.WorkspaceFileCommandDTO.class));
        }
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({UserSecurityConfig.class,AgentWorkspaceFileController.class,WorkspaceFileController.class,
            WorkspaceFileService.class,WorkspaceFilePreviewService.class,WorkspacePreviewProperties.class,DeviceAuthenticationService.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean WorkspaceFileProperties properties(){return new WorkspaceFileProperties();}
        @Bean WorkspaceFileOperationMapper operations(){return mock(WorkspaceFileOperationMapper.class);}
        @Bean ProjectMapper projects(){return mock(ProjectMapper.class);}
        @Bean AgentDeviceMapper devices(){return mock(AgentDeviceMapper.class);}
        @Bean AuthorizationService access(){return mock(AuthorizationService.class);}
        @Bean UserAuthenticationService users(){return mock(UserAuthenticationService.class);}
        @Bean ClientEventWebSocketHandler events(){return mock(ClientEventWebSocketHandler.class);}
        @Bean AgentCommandGateway gateway(){return mock(AgentCommandGateway.class);}
        @Bean StringRedisTemplate redis(){return mock(StringRedisTemplate.class);}
        @Bean ExpertMapper experts(){return mock(ExpertMapper.class);}
        @Bean WorkspaceAttachmentLocationService attachmentLocations(){return mock(WorkspaceAttachmentLocationService.class);}
        @Bean org.springframework.transaction.support.TransactionTemplate transactions(){
            var tx=mock(org.springframework.transaction.support.TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>)call.getArgument(0))
                    .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
            return tx;
        }
    }
}
