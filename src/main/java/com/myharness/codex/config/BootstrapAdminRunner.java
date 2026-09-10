package com.myharness.codex.config;

import com.myharness.codex.service.AccountEmailService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "harness.bootstrap-admin", name = "enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {
    private final BootstrapAdminProperties properties;
    private final AccountEmailService accounts;

    public BootstrapAdminRunner(BootstrapAdminProperties properties, AccountEmailService accounts) {
        this.properties = properties;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        accounts.bootstrap(properties.getEmail(), properties.getDisplayName());
    }
}
