package com.myharness.codex.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.*;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.*;
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
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(UserSecurityTest.Config.class)
@WebAppConfiguration
class UserSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired SysUserMapper users;
    @Autowired RbacMapper rbac;
    @Autowired JwtTokenService jwt;
    @Autowired com.myharness.codex.service.ExpertService experts;
    MockMvc mvc;SysUserPO user;String token;
    @BeforeEach void setup(){
        reset(users,rbac,experts);user=new SysUserPO();user.setId(3L);user.setUsername("normal");user.setDisplayName("Normal");user.setStatus("ENABLED");
        when(users.selectById(3L)).thenReturn(user);
        when(rbac.permissions(3L)).thenReturn(List.of("workspace:use","project:read"));
        token=jwt.createToken(3L,"normal",0);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @Test void normalUserCanEnterWorkspaceButNotManagement() throws Exception {
        mvc.perform(get("/api/v1/projects").servletPath("/api/v1/projects").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(String path:List.of("/api/v1/users","/api/v1/devices","/api/v1/skills","/api/v1/skill-deployments"))
            mvc.perform(get(path).servletPath(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
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
        @Bean JwtTokenService jwt(){SecurityProperties p=new SecurityProperties();p.setJwtSecret("test-secret-with-at-least-32-bytes-long");return new JwtTokenService(p);}
        @Bean AuthorizationService access(RbacMapper r,SysUserMapper u){return new AuthorizationService(r,u);}
        @Bean UserAuthenticationService authentication(JwtTokenService j,SysUserMapper u){return new UserAuthenticationService(j,u);}
        @Bean Endpoints endpoints(){return new Endpoints();}
        @Bean com.myharness.codex.service.ExpertService experts(){return mock(com.myharness.codex.service.ExpertService.class);}
        @Bean com.myharness.codex.controller.ExpertController expertController(com.myharness.codex.service.ExpertService experts){return new com.myharness.codex.controller.ExpertController(experts);}
    }
    @RestController static class Endpoints {
        @GetMapping({"/api/v1/projects","/api/v1/users","/api/v1/devices","/api/v1/skills","/api/v1/skill-deployments","/api/v1/auth/profile"})
        String get(){return "ok";}
    }
}
