package com.myharness.codex.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.*;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(UserSecurityTest.Config.class)
@WebAppConfiguration
class UserSecurityTest {
    private static final String TEST_JWT_SECRET = "test-secret-with-at-least-32-bytes-long";
    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired RbacMapper rbac;
    @Autowired JwtTokenService jwt;
    @Autowired RedisLoginSessionStore sessions;
    @Autowired com.myharness.codex.service.ExpertService experts;
    @Autowired com.myharness.codex.service.McpConfigurationService mcp;
    MockMvc mvc;SysUserPO user;String token;
    @BeforeEach void setup(){
        reset(users,rbac,experts,mcp,sessions);user=new SysUserPO();user.setId(3L);user.setEmail("normal@example.test");user.setDisplayName("Normal");user.setStatus("ENABLED");user.setPasswordHash("test-hash");user.setActivatedAt(java.time.LocalDateTime.of(2026,9,10,0,0));user.setEmailVerifiedAt(user.getActivatedAt());
        when(users.selectById(3L)).thenReturn(user);
        when(rbac.permissions(3L)).thenReturn(List.of("workspace:use","project:read"));
        token=jwt.createToken(3L,0);
        when(sessions.read(3L)).thenReturn(jwt.session(jwt.parseClaims(token)));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @Test void normalUserCanEnterWorkspaceButNotManagement() throws Exception {
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(String path:List.of("/api/v1/users","/api/v1/devices","/api/v1/skills","/api/v1/skill-expert-assignments","/api/v1/admin/mcp-configurations"))
            mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
    }
    @Test void mcpManagerCanManageAndExpertManagerCanOnlyReadSelector() throws Exception {
        when(rbac.permissions(3L)).thenReturn(List.of("mcp:manage"));
        String list="/api/v1/admin/mcp-configurations";
        mvc.perform(get(list).servletPath(list).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        verify(mcp).list("","",3L);
        String selector="/api/v1/admin/mcp-configurations/selectable-versions";
        when(rbac.permissions(3L)).thenReturn(List.of("expert:manage"));
        mvc.perform(get(selector).servletPath(selector).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get(list).servletPath(list).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        verify(mcp).selectableVersions(3L);
    }
    @Test void administratorCanManageUsers() throws Exception {
        when(rbac.permissions(3L)).thenReturn(List.of("system:user:manage"));
        mvc.perform(get("/api/v1/users").servletPath("/api/v1/users").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void administratorCanReadExpertManagement() throws Exception {
        when(rbac.permissions(3L)).thenReturn(List.of("expert:manage","expert:read","expert:use"));
        String path="/api/v1/admin/experts";
        mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(experts).list("",true,3L);
    }
    @Test void marketReaderCanReadMarketAndPublishedVersions() throws Exception {
        when(rbac.permissions(3L)).thenReturn(List.of("expert:read"));
        for(String path:List.of("/api/v1/expert-market","/api/v1/expert-market/10/versions"))
            mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(experts).list("",false,3L);verify(experts).versions(10L,3L);
    }
    @Test void expertManagerCanPublishButMarketReaderCannotManage() throws Exception {
        String path="/api/v1/admin/experts/10/publish";
        when(rbac.permissions(3L)).thenReturn(List.of("expert:read"));
        mvc.perform(get("/api/v1/admin/experts").servletPath("/api/v1/admin/experts").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).servletPath(path).header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"revision\":1,\"compatibleUpgrade\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(experts);
        when(rbac.permissions(3L)).thenReturn(List.of("expert:manage"));
        mvc.perform(post(path).servletPath(path).header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"revision\":1,\"compatibleUpgrade\":true}"))
                .andExpect(status().isOk());
        verify(experts).publish(10L,1L,true,3L);
    }
    @Test void expertRoutesStillRejectAnonymousAndMissingPermissions() throws Exception {
        for(String path:List.of("/api/v1/admin/experts","/api/v1/expert-market","/api/v1/expert-market/10/versions")) {
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(experts);
    }
    @Test void missingForgedRevokedAndDisabledCredentialsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        user.setTokenVersion(1);
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        user.setTokenVersion(0);user.setStatus("DISABLED");
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void signedLegacyTokenWithoutLoginSessionIdRequiresNewLogin() throws Exception {
        Instant now = Instant.now();
        String legacyToken = Jwts.builder()
                .setIssuer("my-harness-for-codex").setSubject("3").claim("version", 0)
                .setIssuedAt(Date.from(now)).setExpiration(Date.from(now.plusSeconds(7200)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        Assertions.assertEquals("3", jwt.parseClaims(legacyToken).getSubject());

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(users, sessions, rbac);
    }
    @Test void signedTokenRequiresCurrentRedisLoginSession() throws Exception {
        when(sessions.read(3L)).thenReturn(null);

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(rbac);
    }
    @Test void differentCurrentLoginSessionIdIsReportedAsReplacement() throws Exception {
        var original = jwt.session(jwt.parseClaims(token));
        when(sessions.read(3L)).thenReturn(new RedisLoginSessionStore.Session(
                original.version(), UUID.randomUUID().toString(), original.expiresAtEpochSeconds()));

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.SESSION_REPLACED.getCode()));
        verifyNoInteractions(rbac);
    }
    @Test void laterLoginRevokesOldTokenAndAllowsOnlyMatchingNewToken() throws Exception {
        user.setTokenVersion(1);
        String newerToken = jwt.createToken(3L, 1);
        when(sessions.read(3L)).thenReturn(jwt.session(jwt.parseClaims(newerToken)));

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.SESSION_REPLACED.getCode()));
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + newerToken))
                .andExpect(status().isOk());
    }
    @Test void databaseVersionRevocationIsNotMisreportedAsAnotherLogin() throws Exception {
        user.setTokenVersion(1);

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(rbac);
    }
    @Test void redisLoginSessionOutageReturnsServiceUnavailableWithoutAuthorizing() throws Exception {
        when(sessions.read(3L)).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.SESSION_UNAVAILABLE.getCode()));
        verifyNoInteractions(rbac);
        Assertions.assertThrows(BusinessException.class, UserContext::requireCurrentUser);
    }
    @Test void redisSessionDeadlineIsIndependentOfSignedAccessExpiry() throws Exception {
        var original = jwt.session(jwt.parseClaims(token));
        when(sessions.read(3L)).thenReturn(new RedisLoginSessionStore.Session(
                original.version(), original.sid(), original.expiresAtEpochSeconds() + 60));

        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
    @Test void publicAccountFlowsIgnoreStaleBearerButOtherAuthRoutesStayProtected() throws Exception {
        for (String path : PublicAuthEndpoints.postPaths()) {
            mvc.perform(post(path).servletPath(path)).andExpect(status().isOk());
            mvc.perform(post(path).servletPath(path).header("Authorization", "Bearer expired-token"))
                    .andExpect(status().isOk());
            mvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/v1/auth/profile", "/api/v1/auth/logout", "/api/v1/auth/change-password",
                "/api/v1/auth/socket-ticket", "/api/v1/auth/activation/unknown")) {
            mvc.perform(post(path).servletPath(path)).andExpect(status().isUnauthorized());
        }
    }
    @Test void pendingAccountCannotUsePreviouslySignedToken() throws Exception {
        user.setActivatedAt(null);
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
    @Test void initialPasswordOnlyAllowsAccountEndpoints() throws Exception {
        user.setMustChangePassword(true);
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        mvc.perform(get("/api/v1/auth/profile").servletPath("/api/v1/auth/profile").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void noAssignmentEvenForAdministratorDeniesExecution() {
        when(rbac.permissions(3L)).thenReturn(List.of("device:manage","workspace:use"));
        Assertions.assertThrows(com.myharness.codex.exception.BusinessException.class,()->context.getBean(AuthorizationService.class).requireDevice(3L,2L));
        when(rbac.assigned(3L,2L)).thenReturn(1);
        Assertions.assertDoesNotThrow(()->context.getBean(AuthorizationService.class).requireDevice(3L,2L));
    }
    @Test void threadLocalIsClearedAfterRequest() throws Exception {
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        Assertions.assertThrows(com.myharness.codex.exception.BusinessException.class,UserContext::requireCurrentUser);
    }
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(UserSecurityConfig.class)
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean SysUserMapper users(){return mock(SysUserMapper.class);}
        @Bean RbacMapper rbac(){return mock(RbacMapper.class);}
        @Bean JwtTokenService jwt(){SecurityProperties p=new SecurityProperties();p.setJwtSecret(TEST_JWT_SECRET);return new JwtTokenService(p);}
        @Bean RedisLoginSessionStore sessions(){return mock(RedisLoginSessionStore.class);}
        @Bean AuthorizationService access(RbacMapper r,SysUserMapper u){return new AuthorizationService(r,u);}
        @Bean UserAuthenticationService authentication(JwtTokenService j,SysUserMapper u,RedisLoginSessionStore s){return new UserAuthenticationService(j,u,s);}
        @Bean Endpoints endpoints(){return new Endpoints();}
        @Bean com.myharness.codex.service.ExpertService experts(){return mock(com.myharness.codex.service.ExpertService.class);}
        @Bean com.myharness.codex.controller.ExpertController expertController(com.myharness.codex.service.ExpertService experts){return new com.myharness.codex.controller.ExpertController(experts);}
        @Bean com.myharness.codex.service.McpConfigurationService mcp(){return mock(com.myharness.codex.service.McpConfigurationService.class);}
        @Bean com.myharness.codex.controller.McpConfigurationController mcpController(com.myharness.codex.service.McpConfigurationService mcp){return new com.myharness.codex.controller.McpConfigurationController(mcp);}
    }
    @RestController static class Endpoints {
        @org.springframework.web.bind.annotation.PostMapping({"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/activation/validate",
                "/api/v1/auth/activate", "/api/v1/auth/activation/resend", "/api/v1/auth/password-reset/code",
                "/api/v1/auth/password-reset"})
        String publicPost(){return "ok";}
        @GetMapping({"/api/v1/projects","/api/v1/users","/api/v1/devices","/api/v1/skills","/api/v1/skill-expert-assignments","/api/v1/auth/profile"})
        String get(){return "ok";}
    }
}
