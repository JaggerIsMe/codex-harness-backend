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
        // Human HTTP API uses explicit Bearer credentials, never cookies.
        http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
            .authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/login","/api/v1/agent/enroll").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/agent/skill-versions/*/download","/ws/client","/ws/agent").permitAll()
                .requestMatchers("/api/v1/auth/profile","/api/v1/auth/logout","/api/v1/auth/change-password","/api/v1/auth/socket-ticket").authenticated()
                .requestMatchers("/api/v1/users/**","/api/v1/roles").hasAuthority("system:user:manage")
                .requestMatchers("/api/v1/devices/available").hasAuthority("workspace:use")
                .requestMatchers("/api/v1/devices/**").hasAuthority("device:manage")
                .requestMatchers("/api/v1/skills/**","/api/v1/skill-deployments/**").hasAuthority("skill:manage")
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
            .addFilterBefore(new UserBearerFilter(authentication,authorization,json),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
