package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.service.AuthService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponseVO<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return ApiResponseVO.success(authService.login(dto));
    }

    @GetMapping("/profile")
    public ApiResponseVO<UserProfileVO> profile() {
        return ApiResponseVO.success(authService.getCurrentUser());
    }
}
