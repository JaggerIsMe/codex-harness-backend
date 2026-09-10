package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.AccountEmailDTO;
import com.myharness.codex.entity.dto.ActivateAccountDTO;
import com.myharness.codex.entity.dto.ActivationTokenDTO;
import com.myharness.codex.entity.dto.EmailPasswordResetDTO;
import com.myharness.codex.entity.vo.ActivationValidationVO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.SendCodeVO;
import com.myharness.codex.service.AccountEmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AccountEmailController {
    private final AccountEmailService accounts;
    public AccountEmailController(AccountEmailService accounts) { this.accounts = accounts; }

    @PostMapping("/activation/validate")
    public ApiResponseVO<ActivationValidationVO> validate(@Valid @RequestBody ActivationTokenDTO dto) {
        return ApiResponseVO.success(accounts.validateActivation(dto.token()));
    }

    @PostMapping("/activate")
    public ApiResponseVO<Void> activate(@Valid @RequestBody ActivateAccountDTO dto) {
        accounts.activate(dto.token(), dto.newPassword(), dto.displayName());
        return ApiResponseVO.success("账号已激活，请使用邮箱和新密码登录", null);
    }

    @PostMapping("/activation/resend")
    public ApiResponseVO<SendCodeVO> resend(@Valid @RequestBody AccountEmailDTO dto, HttpServletRequest request) {
        return uniformSendResponse(() -> ApiResponseVO.success("若邮箱符合条件，激活邮件将发送至该邮箱", accounts.publicResendActivation(dto.email(), request.getRemoteAddr())));
    }

    @PostMapping("/password-reset/code")
    public ApiResponseVO<SendCodeVO> requestCode(@Valid @RequestBody AccountEmailDTO dto, HttpServletRequest request) {
        return uniformSendResponse(() -> ApiResponseVO.success("若邮箱符合条件，验证码将发送至该邮箱", accounts.requestPasswordReset(dto.email(), request.getRemoteAddr())));
    }

    @PostMapping("/password-reset")
    public ApiResponseVO<Void> reset(@Valid @RequestBody EmailPasswordResetDTO dto, HttpServletRequest request) {
        accounts.resetPassword(dto.email(), dto.code(), dto.newPassword(), request.getRemoteAddr());
        return ApiResponseVO.success("密码已重置，请使用新密码登录", null);
    }

    // Delay only after the service transaction has returned, so database locks are never held while waiting.
    // The same response floor and jitter apply to eligible, unknown, disabled and rate-limited addresses.
    private <T> T uniformSendResponse(Supplier<T> request) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400 + ThreadLocalRandom.current().nextInt(101));
        try { return request.get(); }
        finally {
            long remaining;
            while (!Thread.currentThread().isInterrupted() && (remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
        }
    }
}
