package com.myharness.codex.config;

import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.mapper.RbacMapper;
import com.myharness.codex.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminRunnerTest {

    @Mock private SysUserMapper users;
    @Mock private RbacMapper rbac;
    private BootstrapAdminRunner runner;

    @BeforeEach
    void setUp() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setUsername("bootstrap-test");
        properties.setPassword("FixturePassword123");
        properties.setDisplayName("Bootstrap test administrator");
        runner = new BootstrapAdminRunner(properties, users, new BCryptPasswordEncoder(4), rbac);
    }

    @Test
    void rejectsMissingRoleSeedBeforeCreatingUser() {
        when(rbac.lockAdministratorRole()).thenReturn(null);
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments()));

        assertTrue(exception.getMessage().contains("seed-rbac.sql"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsUnsuccessfulAdministratorRoleAssignment(int assignedRows) {
        when(rbac.lockAdministratorRole()).thenReturn(1L);
        when(users.insert(any())).thenAnswer(invocation -> {
            SysUserPO user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });
        when(rbac.assignRole(42L, "SYS_ADMIN")).thenReturn(assignedRows);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments()));

        assertTrue(exception.getMessage().contains("SYS_ADMIN"));
    }

    @Test
    void createsEnabledAdministratorRequiringPasswordChange() {
        when(rbac.lockAdministratorRole()).thenReturn(1L);
        when(users.insert(any())).thenAnswer(invocation -> {
            SysUserPO user = invocation.getArgument(0);
            user.setId(42L);
            return 1;
        });
        when(rbac.assignRole(42L, "SYS_ADMIN")).thenReturn(1);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));

        ArgumentCaptor<SysUserPO> createdUser = ArgumentCaptor.forClass(SysUserPO.class);
        verify(users).insert(createdUser.capture());
        SysUserPO user = createdUser.getValue();
        assertEquals("bootstrap-test", user.getUsername());
        assertEquals("Bootstrap test administrator", user.getDisplayName());
        assertEquals("ENABLED", user.getStatus());
        assertTrue(user.isMustChangePassword());
        assertTrue(new BCryptPasswordEncoder(4).matches("FixturePassword123", user.getPasswordHash()));
        verify(rbac).assignRole(42L, "SYS_ADMIN");
    }

    @Test
    void keepsExistingUserCredentialsAndRolesUnchanged() {
        SysUserPO existing = new SysUserPO();
        existing.setId(42L);
        existing.setUsername("bootstrap-test");
        existing.setPasswordHash("existing-fixture-hash");
        existing.setStatus("DISABLED");
        existing.setMustChangePassword(false);
        when(rbac.lockAdministratorRole()).thenReturn(1L);
        when(users.selectByUsername("bootstrap-test")).thenReturn(existing);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));

        assertEquals("existing-fixture-hash", existing.getPasswordHash());
        assertEquals("DISABLED", existing.getStatus());
        assertFalse(existing.isMustChangePassword());
        verify(users).selectByUsername("bootstrap-test");
        verify(rbac).lockAdministratorRole();
        verifyNoMoreInteractions(users, rbac);
    }
}
