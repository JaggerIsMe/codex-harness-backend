package com.myharness.codex.config;

import com.myharness.codex.entity.enums.UserStatus;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "harness.bootstrap-admin", name = "enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminProperties properties;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final com.myharness.codex.mapper.RbacMapper rbac;

    public BootstrapAdminRunner(BootstrapAdminProperties properties,
                                SysUserMapper sysUserMapper,
                                PasswordEncoder passwordEncoder, com.myharness.codex.mapper.RbacMapper rbac) {
        this.properties = properties;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.rbac=rbac;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword())) {
            throw new IllegalStateException(
                    "Bootstrap admin requires HARNESS_BOOTSTRAP_ADMIN_USERNAME and HARNESS_BOOTSTRAP_ADMIN_PASSWORD");
        }
        String username = properties.getUsername().trim();
        rbac.lockAdministratorRole();
        if (sysUserMapper.selectByUsername(username) != null) {
            LOGGER.info("Bootstrap administrator already exists: {}", username);
            return;
        }

        SysUserPO user = new SysUserPO();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(properties.getPassword()));
        user.setDisplayName(StringUtils.hasText(properties.getDisplayName())
                ? properties.getDisplayName().trim() : username);
        user.setStatus(UserStatus.ENABLED.name());
        user.setMustChangePassword(true);
        sysUserMapper.insert(user);
        rbac.assignRole(user.getId(),"SYS_ADMIN");
        LOGGER.info("Bootstrap administrator created: {}", username);
    }
}
