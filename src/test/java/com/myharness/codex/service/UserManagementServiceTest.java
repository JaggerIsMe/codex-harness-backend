package com.myharness.codex.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserManagementServiceTest {
    RbacMapper rbac;SysUserMapper users;AgentDeviceMapper devices;ClientEventWebSocketHandler sockets;
    UserManagementService service;SysUserPO target;BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
    @BeforeEach void setup(){
        rbac=mock(RbacMapper.class);users=mock(SysUserMapper.class);devices=mock(AgentDeviceMapper.class);sockets=mock(ClientEventWebSocketHandler.class);
        service=new UserManagementService(rbac,users,devices,encoder,mock(AuthorizationService.class),sockets,new ObjectMapper());
        UserContext.set(new UserPrincipal(1L,"admin","Administrator"));TransactionSynchronizationManager.initSynchronization();
        when(rbac.lockAdministratorRole()).thenReturn(1L);
        target=new SysUserPO();target.setId(2L);target.setUsername("normal");target.setDisplayName("Normal");target.setStatus("ENABLED");
        when(rbac.lockUser(2L)).thenReturn(target);when(users.selectById(2L)).thenReturn(target);
    }
    @AfterEach void cleanup(){TransactionSynchronizationManager.clearSynchronization();UserContext.clear();}
    @Test void lastEnabledAdministratorCannotBeDisabled(){
        when(rbac.roles(2L)).thenReturn(List.of("SYS_ADMIN"));when(rbac.enabledAdministrators()).thenReturn(1);
        var ex=assertThrows(BusinessException.class,()->service.update(2L,new UpdateUserDTO("Admin","DISABLED")));
        assertEquals(ErrorCode.CONFLICT,ex.getErrorCode());verify(rbac,never()).updateUser(any(),any(),any());
    }
    @Test void lastEnabledAdministratorCannotBeDemoted(){
        when(rbac.roles(2L)).thenReturn(List.of("SYS_ADMIN"));when(rbac.enabledAdministrators()).thenReturn(1);
        assertThrows(BusinessException.class,()->service.role(2L,new AssignRoleDTO("USER")));
        verify(rbac,never()).deleteRoles(any());
    }
    @Test void deviceAssignmentRevokesCredentialsAndClosesSocketsOnlyAfterCommit(){
        when(devices.selectById(8L)).thenReturn(new AgentDevicePO());when(devices.selectById(9L)).thenReturn(new AgentDevicePO());
        when(rbac.deviceIds(2L)).thenReturn(List.of(7L));
        service.assignDevices(2L,new AssignDevicesDTO(List.of(8L,9L)));
        verify(rbac).revokeDevices(2L);verify(rbac).assignDevice(2L,8L,1L);verify(rbac).assignDevice(2L,9L,1L);verify(rbac).revokeTokens(2L);
        verify(sockets,never()).disconnectUser(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
        verify(sockets).disconnectUser(2L);
        var detail=ArgumentCaptor.forClass(String.class);verify(rbac).audit(eq(1L),eq("USER_DEVICE_ASSIGN"),eq("USER"),eq("2"),detail.capture());
        assertTrue(detail.getValue().contains("before"));assertTrue(detail.getValue().contains("after"));
    }
    @Test void resetStoresOnlyHashAndRequiresPasswordChange(){
        service.resetPassword(2L,new ResetPasswordDTO("LongPassword123"));
        var hash=ArgumentCaptor.forClass(String.class);verify(rbac).changePassword(eq(2L),hash.capture(),eq(true));
        assertNotEquals("LongPassword123",hash.getValue());assertTrue(encoder.matches("LongPassword123",hash.getValue()));
        verify(rbac).audit(1L,"USER_PASSWORD_RESET","USER","2","{}");
    }
    @Test void userResponseNeverContainsPasswordHashOrTokenVersion() throws Exception {
        target.setPasswordHash("SECRET_HASH");target.setTokenVersion(55);
        when(rbac.users("",null,20,0)).thenReturn(List.of(target));when(rbac.countUsers("",null)).thenReturn(1L);
        String body=new ObjectMapper().writeValueAsString(service.list("",null,1,20));
        assertFalse(body.contains("SECRET_HASH"));assertFalse(body.contains("passwordHash"));assertFalse(body.contains("tokenVersion"));
    }
    @Test void invalidMachineDoesNotRevokeExistingAssignments(){
        assertThrows(BusinessException.class,()->service.assignDevices(2L,new AssignDevicesDTO(List.of(99L))));
        verify(rbac,never()).revokeDevices(any());verify(rbac,never()).revokeTokens(any());
    }
}

