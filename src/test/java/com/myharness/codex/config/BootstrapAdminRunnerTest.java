package com.myharness.codex.config;

import com.myharness.codex.service.AccountEmailService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.*;

class BootstrapAdminRunnerTest {
    @Test
    void delegatesConfiguredEmailToSharedAccountActivationService() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setEmail("administrator@example.com");
        properties.setDisplayName("管理员");
        AccountEmailService accounts = mock(AccountEmailService.class);

        new BootstrapAdminRunner(properties, accounts).run(new DefaultApplicationArguments());

        verify(accounts).bootstrap("administrator@example.com", "管理员");
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void leavesRepeatedStartupAndValidationDecisionsToPermanentInitializationService() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        AccountEmailService accounts = mock(AccountEmailService.class);

        new BootstrapAdminRunner(properties, accounts).run(new DefaultApplicationArguments());

        verify(accounts).bootstrap(null, null);
    }
}
