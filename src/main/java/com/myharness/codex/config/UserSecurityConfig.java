package com.myharness.codex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.security.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class UserSecurityConfig {
    @Bean
    SecurityFilterChain userSecurity(HttpSecurity http,UserAuthenticationService authentication,AuthorizationService authorization,ObjectMapper json) throws Exception {
        // Business APIs use Bearer credentials; refresh cookies have explicit JSON/header/Origin guards.
        http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
            .authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.GET,"/api/v1/agent/workspace-file-operations/*","/api/v1/agent/workspace-file-operations/*/content").permitAll()
                .requestMatchers(HttpMethod.PUT,"/api/v1/agent/workspace-file-operations/*/content","/api/v1/agent/workspace-file-operations/*/items").permitAll()
                .requestMatchers(HttpMethod.POST, PublicAuthEndpoints.postPaths()).permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/agent/enroll").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/agent/skill-versions/*/download","/api/v1/agent/turns/*/attachments","/ws/client","/ws/agent").permitAll()
                .requestMatchers("/api/v1/auth/profile","/api/v1/auth/logout","/api/v1/auth/change-password","/api/v1/auth/socket-ticket","/api/v1/auth/activity").authenticated()
                .requestMatchers("/api/v1/users/**","/api/v1/roles").hasAuthority("system:user:manage")
                .requestMatchers("/api/v1/devices/available").hasAuthority("workspace:use")
                .requestMatchers("/api/v1/admin/model-configurations/**","/api/v1/devices/*/model-assignment").hasAuthority("model:manage")
                .requestMatchers("/api/v1/devices/**").hasAuthority("device:manage")
                .requestMatchers("/api/v1/skills/**","/api/v1/skill-deployments/**").hasAuthority("skill:manage")
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/mcp-configurations/selectable-versions").hasAuthority("expert:manage")
                .requestMatchers("/api/v1/admin/mcp-configurations/**").hasAuthority("mcp:manage")
                .requestMatchers("/api/v1/admin/experts/**").hasAuthority("expert:manage")
                .requestMatchers(HttpMethod.GET,"/api/v1/expert-market/**").hasAuthority("expert:read")
                .requestMatchers("/api/v1/projects/**","/api/v1/approvals/**").hasAuthority("workspace:use")
                .anyRequest().denyAll())
            .exceptionHandling(e->e
                .authenticationEntryPoint((request,response,error)->{
                    response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getOutputStream(),ApiResponseVO.error(401,"未登录或登录已失效"));
                })
                .accessDeniedHandler((request,response,error)->{
                    response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getOutputStream(),ApiResponseVO.error(403,"没有执行此操作的权限"));
                }))
            .addFilterBefore(new UserBearerFilter(authentication,authorization,json),UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new WorkspaceFileRequestLimitFilter(json),UserBearerFilter.class);
        return http.build();
    }
}
