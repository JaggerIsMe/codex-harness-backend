package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.*;
import com.myharness.codex.controller.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.service.ConversationArtifactService;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP security chain, controllers, Device authentication, service and filesystem. */
@SpringJUnitConfig(ArtifactHttpSecurityTest.Config.class)
@WebAppConfiguration
class ArtifactHttpSecurityTest {
    @TempDir Path storage;
    @Autowired WebApplicationContext context;
    @Autowired ConversationArtifactMapper artifacts;
    @Autowired ConversationMapper conversations;
    @Autowired ProjectMapper projects;
    @Autowired AgentDeviceMapper devices;
    @Autowired UserAuthenticationService users;
    @Autowired AuthorizationService access;
    @Autowired ArtifactProperties limits;
    MockMvc mvc;
    ConversationArtifactPO record;
    ConversationTurnPO turn;
    static final String BASE="/api/v1/agent/turns/21/artifacts";
    static final String USER="/api/v1/projects/4/conversations/7/artifacts";
    static final String METADATA="{\"artifactKey\":\"fixture-publication\",\"fileName\":\"report.txt\",\"sizeBytes\":8,\"sha256\":\""+SecureDigests.sha256("original")+"\"}";

    @BeforeEach void setup() {
        reset(artifacts,conversations,projects,devices,users,access);record=null;
        limits.setStorageDir(storage.toString());
        var c=new ConversationPO();c.setId(7L);c.setProjectId(4L);c.setDeviceId(2L);c.setUserId(1L);c.setStatus("ACTIVE");
        when(conversations.selectConversation(7L)).thenReturn(c);
        when(conversations.selectOwnedConversation(4L,7L,1L)).thenReturn(c);
        var p=new ProjectPO();p.setStatus("ACTIVE");when(projects.selectOwned(4L,1L)).thenReturn(p);
        turn=new ConversationTurnPO();turn.setId(21L);turn.setConversationId(7L);turn.setStatus("COMPLETED");
        when(conversations.selectTurn(21L)).thenReturn(turn);
        var device=new AgentDevicePO();device.setId(2L);device.setStatus("ONLINE");device.setTokenHash(SecureDigests.sha256("fixture-device-token"));
        when(devices.selectByCode("fixture-device")).thenReturn(device);
        when(artifacts.forTurn(21L)).thenAnswer(i -> record==null?List.of():List.of(record));
        when(artifacts.findKey(eq(21L),any())).thenAnswer(i -> record);
        when(artifacts.find(9L)).thenAnswer(i -> record);when(artifacts.lock(9L)).thenAnswer(i -> record);
        when(artifacts.insert(any())).thenAnswer(i -> {record=i.getArgument(0);record.setId(9L);return 1;});
        when(artifacts.ready(9L)).thenReturn(1);
        when(artifacts.fail(eq(9L),any())).thenAnswer(i -> {
            if(!"UPLOADING".equals(record.getStatus()))return 0;record.setStatus("FAILED");return 1;
        });
        when(artifacts.retry(9L)).thenReturn(1);
        when(users.authenticate(any())).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        var user=new SysUserPO();user.setId(1L);user.setUsername("owner");user.setDisplayName("Owner");
        doReturn(user).when(users).authenticate("fixture-user-token");
        when(access.permissions(1L)).thenReturn(List.of("workspace:use"));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    MockHttpServletRequestBuilder device(MockHttpServletRequestBuilder request,String path) {
        return request.servletPath(path).header("X-Harness-Device-Code","fixture-device")
                .header("Authorization","Bearer fixture-device-token");
    }
    @Test void devicePublishesThroughSecurityChainAndOwnerDownloadsExactBytes() throws Exception {
        byte[] bytes="original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String metadata=METADATA;
        // Optional read-only incident replay. Destination storage and credentials remain isolated fixtures.
        String replay=System.getProperty("artifact.replay.manifest");
        if(replay!=null) {
            Path manifest=Path.of(replay);
            var json=new ObjectMapper();
            var job=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(manifest.toFile());
            assertEquals("21",job.path("turnId").asText());
            assertTrue(job.path("artifactKey").asText().matches("[a-f0-9-]{36}"));
            bytes=Files.readAllBytes(manifest.resolveSibling(job.path("artifactKey").asText()+".bin"));
            assertEquals(job.path("sizeBytes").asLong(),bytes.length);
            assertEquals(job.path("sha256").asText(),java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            job.remove("turnId");metadata=json.writeValueAsString(job);
        }
        mvc.perform(device(post(BASE),BASE).contentType("application/json").content(metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UPLOADING"));
        mvc.perform(device(put(BASE+"/9/content"),BASE+"/9/content").contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mvc.perform(device(get(BASE+"/9"),BASE+"/9")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mvc.perform(device(post(BASE+"/9/failed"),BASE+"/9/failed")).andExpect(status().isOk());
        mvc.perform(get(USER+"/9/download").servletPath(USER+"/9/download").header("Authorization","Bearer fixture-user-token"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        assertArrayEquals(bytes,Files.readAllBytes(storage.resolve(record.getStorageKey())));
    }
    @Test void everyAgentArtifactRouteStillRequiresValidDeviceCredentials() throws Exception {
        List<MockHttpServletRequestBuilder> requests=List.of(
                post(BASE).servletPath(BASE).contentType("application/json").content(METADATA),
                get(BASE+"/9").servletPath(BASE+"/9"),
                put(BASE+"/9/content").servletPath(BASE+"/9/content").contentType("application/octet-stream").content("original"),
                post(BASE+"/9/failed").servletPath(BASE+"/9/failed"));
        for(var request:requests)
            mvc.perform(request.header("X-Harness-Device-Code","fixture-device").header("Authorization","Bearer forged-token"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40112));
        verify(artifacts,never()).insert(any());
    }
    @Test void validDeviceCannotBypassTurnOwnershipRevocationOrUserApiAuthentication() throws Exception {
        var foreign=new AgentDevicePO();foreign.setId(99L);foreign.setStatus("ONLINE");foreign.setTokenHash(SecureDigests.sha256("fixture-device-token"));
        when(devices.selectByCode("fixture-device")).thenReturn(foreign);
        mvc.perform(device(post(BASE),BASE).contentType("application/json").content(METADATA)).andExpect(status().isNotFound());
        foreign.setId(2L);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(1L,2L);
        mvc.perform(device(post(BASE),BASE).contentType("application/json").content(METADATA)).andExpect(status().isForbidden());
        mvc.perform(device(get(USER),USER)).andExpect(status().isUnauthorized());
        verify(artifacts,never()).insert(any());
    }
    @Test void unmappedAgentRoutesRemainDenied() throws Exception {
        mvc.perform(device(delete(BASE+"/9"),BASE+"/9")).andExpect(status().isUnauthorized());
        mvc.perform(device(post("/api/v1/agent/unknown"),"/api/v1/agent/unknown")).andExpect(status().isUnauthorized());
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({UserSecurityConfig.class,AgentArtifactController.class,ConversationArtifactController.class,
            ConversationArtifactService.class,DeviceAuthenticationService.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ArtifactProperties limits(){return new ArtifactProperties();}
        @Bean ConversationArtifactMapper artifacts(){return mock(ConversationArtifactMapper.class);}
        @Bean ConversationMapper conversations(){return mock(ConversationMapper.class);}
        @Bean ProjectMapper projects(){return mock(ProjectMapper.class);}
        @Bean AgentDeviceMapper devices(){return mock(AgentDeviceMapper.class);}
        @Bean AuthorizationService access(){return mock(AuthorizationService.class);}
        @Bean UserAuthenticationService users(){return mock(UserAuthenticationService.class);}
        @Bean ClientEventWebSocketHandler events(){return mock(ClientEventWebSocketHandler.class);}
        @Bean TransactionTemplate transactions() {
            var tx=mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
            return tx;
        }
    }
}
