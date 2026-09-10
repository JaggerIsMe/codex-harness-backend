package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.vo.ActivationValidationVO;
import com.myharness.codex.entity.vo.SendCodeVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.exception.GlobalExceptionHandler;
import com.myharness.codex.service.AccountEmailService;
import com.myharness.codex.service.mail.MailRateLimitException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountEmailControllerTest {
    private AccountEmailService accounts;
    private MockMvc mvc;

    @BeforeEach void setup() {
        accounts = mock(AccountEmailService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountEmailController(accounts))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void previewReturnsOnlyMaskedEmailAndPageInformationWithoutActivating() throws Exception {
        when(accounts.validateActivation("opaque-token")).thenReturn(new ActivationValidationVO("u***@example.com", "Display name", LocalDateTime.of(2026, 9, 11, 10, 0)));
        mvc.perform(post("/api/v1/auth/activation/validate").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"opaque-token\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.maskedEmail").value("u***@example.com"))
                .andExpect(jsonPath("$.data.token").doesNotExist()).andExpect(jsonPath("$.data.email").doesNotExist());
        verify(accounts).validateActivation("opaque-token");
        verifyNoMoreInteractions(accounts);
    }

    @Test void activationUsesPostAndDoesNotReturnLoginCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"opaque-token\",\"newPassword\":\"FormalPassword123\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(accounts).activate("opaque-token", "FormalPassword123", "Alice");
    }

    @Test void recoveryResponseIsGenericAndUsesTrustedRemoteAddressForLimits() throws Exception {
        when(accounts.requestPasswordReset("unknown@example.com", "127.0.0.1")).thenReturn(new SendCodeVO(60));
        mvc.perform(post("/api/v1/auth/password-reset/code").header("X-Forwarded-For", "attacker-supplied")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"unknown@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retryAfterSeconds").value(60))
                .andExpect(jsonPath("$.info").value("若邮箱符合条件，验证码将发送至该邮箱"));
        verify(accounts).requestPasswordReset("unknown@example.com", "127.0.0.1");
    }

    @Test void publicResendDoesNotCreateAccountsOrAcceptRoles() throws Exception {
        when(accounts.publicResendActivation("pending@example.com", "127.0.0.1")).thenReturn(new SendCodeVO(60));
        mvc.perform(post("/api/v1/auth/activation/resend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pending@example.com\",\"role\":\"SYS_ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.info").value("若邮箱符合条件，激活邮件将发送至该邮箱"));
        verify(accounts).publicResendActivation("pending@example.com", "127.0.0.1");
        verifyNoMoreInteractions(accounts);
    }

    @Test void verificationErrorsRetainTheRecoveryPageAndLimitsReturnServerCooldown() throws Exception {
        doThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID)).when(accounts)
                .resetPassword("user@example.com", "000012", "NewPassword123", "127.0.0.1");
        mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"code\":\"000012\",\"newPassword\":\"NewPassword123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40021));
        when(accounts.requestPasswordReset("user@example.com", "127.0.0.1")).thenThrow(new MailRateLimitException(42));
        mvc.perform(post("/api/v1/auth/password-reset/code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(42)).andExpect(jsonPath("$.code").value(42921));
    }

    @Test void malformedCodeIsRejectedAndRequestToStringNeverRevealsSecrets() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"code\":\"123\",\"newPassword\":\"NewPassword123\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
        assertThat(new ActivationTokenDTO("secret-token").toString()).doesNotContain("secret-token");
        assertThat(new ActivateAccountDTO("secret-token", "secret-password", "name").toString()).doesNotContain("secret-token", "secret-password");
        assertThat(new EmailPasswordResetDTO("private@example.com", "000012", "secret-password").toString()).doesNotContain("private@example.com", "000012", "secret-password");
        assertThat(new AccountEmailDTO("private@example.com").toString()).doesNotContain("private@example.com");
    }
}
