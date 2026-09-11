package com.myharness.codex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.SecurityProperties;
import com.myharness.codex.config.UserSecurityConfig;
import com.myharness.codex.controller.AuthController;
import com.myharness.codex.controller.AccountController;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.SessionTokenVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.service.AuthService;
import com.myharness.codex.service.UserManagementService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP filters, controllers, cookies and JSON serialization; persistence is tested separately. */
@SpringJUnitConfig(AuthRenewalHttpSecurityTest.Config.class)
@WebAppConfiguration
class AuthRenewalHttpSecurityTest {
    private static final String SID = "a789cf89-2e61-4be7-95bb-98fd8a42f426";
    private static final String OTHER_SID = "c871b48d-2826-409c-bfc4-cea07f2d6eaa";
    private static final String REFRESH_SECRET = "http-test-refresh-secret-never-in-json";
    @Autowired WebApplicationContext context;
    @Autowired AuthService auth;
    @Autowired UserAuthenticationService authentication;
    @Autowired AuthorizationService authorization;
    @Autowired UserManagementService users;
    @Autowired SecurityProperties properties;
    @Autowired AtomicInteger mutations;
    MockMvc mvc;
    SessionTokenVO grant;

    @BeforeEach void setup() {
        reset(auth, authentication, authorization, users);
        mutations.set(0);
        properties.setRefreshCookieSecure(true);
        properties.setRefreshAllowedOrigins(List.of("http://localhost:8010"));
        long now = Instant.now().getEpochSecond();
        grant = new SessionTokenVO("http-test-access-token", 7200, now + 7200, SID,
                now + 604800, now + 7200, 600, 2, REFRESH_SECRET);
        when(auth.login(any())).thenReturn(new LoginVO(grant,
                new UserProfileVO(3L, "normal@example.test", "Normal")));
        when(auth.refresh(SID, REFRESH_SECRET)).thenReturn(grant);
        SysUserPO user = new SysUserPO();
        user.setId(3L);
        user.setEmail("normal@example.test");
        user.setDisplayName("Normal");
        when(authentication.authenticateSession("valid-access")).thenReturn(
                new UserAuthenticationService.AuthenticatedUser(user,
                        new RedisLoginSessionStore.Session(1, SID, now + 7200)));
        when(authorization.permissions(3L)).thenReturn(List.of("workspace:use"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test void loginEmitsScopedSecureCookieWithoutSerializingItsSecret() throws Exception {
        var result = mvc.perform(loginRequest())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.sessionId").value(SID))
                .andExpect(jsonPath("$.data.credentialGeneration").value(2))
                .andExpect(jsonPath("$.data.refreshCookieValue").doesNotExist())
                .andExpect(content().string(not(containsString(REFRESH_SECRET))))
                .andReturn();
        String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith(RefreshCookieService.PREFIX + SID + "=" + REFRESH_SECRET));
        assertTrue(cookie.contains("Path=/api/v1/auth"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertFalse(cookie.contains("Domain="));
    }

    @Test void loginWithoutCustomHeaderCannotCreateCookieOrLogin() throws Exception {
        mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"normal@example.test\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(auth);
    }

    @Test void refreshUsesCookieEvenIfAccessHeaderHasExpired() throws Exception {
        mvc.perform(refreshRequest().header(HttpHeaders.AUTHORIZATION, "Bearer expired-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("http-test-access-token"))
                .andExpect(jsonPath("$.data.refreshCookieValue").doesNotExist())
                .andExpect(content().string(not(containsString(REFRESH_SECRET))));
        verify(auth).refresh(SID, REFRESH_SECRET);
        verifyNoInteractions(authentication, authorization);
    }

    @Test void refreshRequiresCookieForExplicitSession() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Harness-Refresh", "1").header("X-Harness-Session", SID)
                        .cookie(new Cookie(RefreshCookieService.PREFIX + OTHER_SID, REFRESH_SECRET)))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(auth);
    }

    @Test void malformedSessionCannotSelectCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Harness-Refresh", "1").header("X-Harness-Session", "invalid-session"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test void crossOriginAndCrossSiteRequestsAreRejectedBeforeRefresh() throws Exception {
        mvc.perform(refreshRequest().header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(refreshRequest().header(HttpHeaders.ORIGIN, "http://localhost")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auth);
    }

    @Test void sameOriginAndExplicitLocalProxyOriginCanRefresh() throws Exception {
        mvc.perform(refreshRequest().header(HttpHeaders.ORIGIN, "http://localhost"))
                .andExpect(status().isOk());
        mvc.perform(refreshRequest().header(HttpHeaders.ORIGIN, "http://localhost:8010"))
                .andExpect(status().isOk());
        verify(auth, times(2)).refresh(SID, REFRESH_SECRET);
    }

    @Test void simpleContentTypeCannotRefresh() throws Exception {
        mvc.perform(refreshRequest().contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(auth);
    }

    @Test void refreshWithoutCustomHeaderIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Harness-Session", SID)
                        .cookie(new Cookie(RefreshCookieService.PREFIX + SID, REFRESH_SECRET)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auth);
    }

    @Test void foreignPreflightDoesNotEnableCredentialedCors() throws Exception {
        mvc.perform(options("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-harness-refresh,x-harness-session"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verifyNoInteractions(auth);
    }

    @Test void accessExpiryIsReportedBeforeAnyBusinessMutation() throws Exception {
        when(authentication.authenticateSession("expired-access"))
                .thenThrow(new BusinessException(ErrorCode.ACCESS_TOKEN_EXPIRED));
        mvc.perform(post("/api/v1/projects/renewal-test").servletPath("/api/v1/projects/renewal-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-access"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40122));
        assertEquals(0, mutations.get());
        verifyNoInteractions(authorization, auth);
    }

    @Test void validAccessReachesBusinessMutation() throws Exception {
        mvc.perform(post("/api/v1/projects/renewal-test").servletPath("/api/v1/projects/renewal-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-access"))
                .andExpect(status().isOk());
        assertEquals(1, mutations.get());
    }

    @Test void refreshCookieAloneCannotAuthorizeBusinessApi() throws Exception {
        mvc.perform(post("/api/v1/projects/renewal-test").servletPath("/api/v1/projects/renewal-test")
                        .cookie(new Cookie(RefreshCookieService.PREFIX + SID, REFRESH_SECRET)))
                .andExpect(status().isUnauthorized());
        assertEquals(0, mutations.get());
        verifyNoInteractions(auth, authentication, authorization);
    }

    @Test void logoutClearsOnlyTheAuthenticatedLoginCookie() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/logout").servletPath("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-access")
                        .header("X-Harness-Session", OTHER_SID)
                        .cookie(new Cookie(RefreshCookieService.PREFIX + SID, REFRESH_SECRET),
                                new Cookie(RefreshCookieService.PREFIX + OTHER_SID, "other-secret")))
                .andExpect(status().isOk()).andReturn();
        verify(users).logout();
        var cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(1, cookies.size());
        assertTrue(cookies.getFirst().startsWith(RefreshCookieService.PREFIX + SID + "="));
        assertTrue(cookies.getFirst().contains("Max-Age=0"));
    }

    @Test void failedLogoutDoesNotDeleteServerCookie() throws Exception {
        doThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE)).when(users).logout();
        mvc.perform(post("/api/v1/auth/logout").servletPath("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-access"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test void refreshFailureDoesNotClearOrReplaceCookie() throws Exception {
        when(auth.refresh(SID, REFRESH_SECRET)).thenThrow(new BusinessException(ErrorCode.SESSION_UNAVAILABLE));
        mvc.perform(refreshRequest()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50322))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test void refreshCannotDeleteCookiesBelongingToAnotherLogin() throws Exception {
        var result = mvc.perform(refreshRequest()
                        .cookie(new Cookie(RefreshCookieService.PREFIX + OTHER_SID, "other-refresh-secret")))
                .andExpect(status().isOk()).andReturn();
        var cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(1, cookies.size());
        assertTrue(cookies.getFirst().startsWith(RefreshCookieService.PREFIX + SID + "="));
    }

    @Test void loginCleansOnlyObservedOlderSessionCookies() throws Exception {
        var result = mvc.perform(loginRequest()
                        .cookie(new Cookie(RefreshCookieService.PREFIX + OTHER_SID, "old-refresh-secret"),
                                new Cookie("unrelated-cookie", "preserved")))
                .andExpect(status().isOk()).andReturn();
        var cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(value -> value.startsWith(RefreshCookieService.PREFIX + OTHER_SID + "=")
                && value.contains("Max-Age=0")));
        assertTrue(cookies.stream().noneMatch(value -> value.startsWith("unrelated-cookie=")));
    }

    @Test void insecureCookieOptOutOnlyAllowsLocalDevelopment() throws Exception {
        properties.setRefreshCookieSecure(false);
        var result = mvc.perform(loginRequest()).andExpect(status().isOk()).andReturn();
        assertFalse(result.getResponse().getHeader(HttpHeaders.SET_COOKIE).contains("Secure"));
        clearInvocations(auth);
        mvc.perform(loginRequest().with(request -> {
                    request.setServerName("harness.example");
                    return request;
                })).andExpect(status().isForbidden());
        verifyNoInteractions(auth);
    }

    private MockHttpServletRequestBuilder loginRequest() {
        return post("/api/v1/auth/login").servletPath("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).header("X-Harness-Refresh", "1")
                .content("{\"email\":\"normal@example.test\",\"password\":\"password123\"}");
    }

    private MockHttpServletRequestBuilder refreshRequest() {
        return post("/api/v1/auth/refresh").servletPath("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-Harness-Refresh", "1").header("X-Harness-Session", SID)
                .cookie(new Cookie(RefreshCookieService.PREFIX + SID, REFRESH_SECRET));
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({UserSecurityConfig.class, AuthController.class, AccountController.class,
            GlobalExceptionHandler.class, MutationController.class})
    static class Config {
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean UserAuthenticationService authentication() { return mock(UserAuthenticationService.class); }
        @Bean AuthorizationService authorization() { return mock(AuthorizationService.class); }
        @Bean UserManagementService users() { return mock(UserManagementService.class); }
        @Bean ClientSocketTickets tickets() { return mock(ClientSocketTickets.class); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean SecurityProperties properties() { return new SecurityProperties(); }
        @Bean RefreshCookieService cookies(SecurityProperties properties) { return new RefreshCookieService(properties); }
        @Bean AtomicInteger mutations() { return new AtomicInteger(); }
    }

    @RestController
    static class MutationController {
        private final AtomicInteger mutations;
        MutationController(AtomicInteger mutations) { this.mutations = mutations; }
        @PostMapping("/api/v1/projects/renewal-test") ApiResponseVO<Void> mutate() {
            mutations.incrementAndGet();
            return ApiResponseVO.success(null);
        }
    }
}
