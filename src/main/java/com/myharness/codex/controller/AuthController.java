package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.entity.vo.SessionTokenVO;
import com.myharness.codex.entity.vo.SessionActivityVO;
import com.myharness.codex.security.RefreshCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.myharness.codex.service.AuthService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieService cookies;

    public AuthController(AuthService authService, RefreshCookieService cookies) {
        this.authService = authService;
        this.cookies=cookies;
    }

    @PostMapping("/login")
    public ApiResponseVO<LoginVO> login(@Valid @RequestBody LoginDTO dto,HttpServletRequest request,HttpServletResponse response) {
        cookies.requireRequest(request);
        var result=authService.login(dto);
        cookies.write(request,response,result,true);
        return ApiResponseVO.success(result);
    }

    @PostMapping("/refresh")
    public ApiResponseVO<SessionTokenVO> refresh(HttpServletRequest request,HttpServletResponse response) {
        cookies.requireRequest(request);
        String sid=cookies.requestedSession(request);
        var result=authService.refresh(sid,cookies.credential(request,sid));
        cookies.write(request,response,result,false);
        return ApiResponseVO.success(result);
    }

    @PostMapping("/activity")
    public ApiResponseVO<SessionActivityVO> activity(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return ApiResponseVO.success(authService.activity());
    }

    @GetMapping("/profile")
    public ApiResponseVO<UserProfileVO> profile() {
        return ApiResponseVO.success(authService.getCurrentUser());
    }
}
