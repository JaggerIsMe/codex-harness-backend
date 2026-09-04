package com.myharness.codex.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.*;
import java.util.*;

@Service
public class UserManagementService {
    private final RbacMapper rbac;
    private final SysUserMapper users;
    private final AgentDeviceMapper devices;
    private final PasswordEncoder passwords;
    private final AuthorizationService access;
    private final ClientEventWebSocketHandler sockets;
    private final ObjectMapper json;
    public UserManagementService(RbacMapper rbac,SysUserMapper users,AgentDeviceMapper devices,PasswordEncoder passwords,
                                 AuthorizationService access,ClientEventWebSocketHandler sockets,ObjectMapper json) {
        this.rbac=rbac;this.users=users;this.devices=devices;this.passwords=passwords;this.access=access;this.sockets=sockets;this.json=json;
    }
    private Long administrator() { Long id=UserContext.requireCurrentUser().getId();access.requirePermission(id,"system:user:manage");return id; }
    public PageVO<ManagedUserVO> list(String keyword,String status,int page,int size) {
        administrator();
        if(page<1 || page>100000 || size<1 || size>100 || status!=null && !status.isBlank() && !Set.of("ENABLED","DISABLED").contains(status))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String query=keyword==null?"":keyword.trim();
        return new PageVO<>(rbac.users(query,status,size,(page-1)*size).stream().map(this::view).toList(),rbac.countUsers(query,status),page,size);
    }
    public List<RoleVO> roles() { administrator(); return rbac.listRoles(); }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO create(CreateUserDTO dto) {
        Long operator=administrator(); lockAdministration(); PasswordPolicy.validate(dto.password()); validateRole(dto.role()); validateDevices(dto.deviceIds());
        SysUserPO user=new SysUserPO();user.setUsername(dto.username().trim());user.setDisplayName(dto.displayName().trim());
        user.setPasswordHash(passwords.encode(dto.password()));user.setStatus("ENABLED");user.setMustChangePassword(true);
        try { users.insert(user); } catch(DuplicateKeyException ex) { throw new BusinessException(ErrorCode.CONFLICT,"用户名已存在"); }
        rbac.assignRole(user.getId(),dto.role());
        for(Long device:new LinkedHashSet<>(dto.deviceIds())) rbac.assignDevice(user.getId(),device,operator);
        audit(operator,"USER_CREATE",user.getId(),Map.of("role",dto.role(),"deviceIds",dto.deviceIds()));
        return view(user);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO update(Long id,UpdateUserDTO dto) {
        Long operator=administrator(); lockAdministration(); SysUserPO user=requireLocked(id);
        protectLastAdmin(user,dto.status(),rbac.roles(id));
        rbac.updateUser(id,dto.displayName().trim(),dto.status());
        audit(operator,"USER_UPDATE",id,Map.of("previousStatus",user.getStatus(),"status",dto.status(),"displayName",dto.displayName()));
        disconnectAfterCommit(id);return view(users.selectById(id));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO role(Long id,AssignRoleDTO dto) {
        Long operator=administrator();lockAdministration();SysUserPO user=requireLocked(id);validateRole(dto.role());
        List<String> before=rbac.roles(id);protectLastAdmin(user,user.getStatus(),List.of(dto.role()));
        rbac.deleteRoles(id);rbac.assignRole(id,dto.role());rbac.revokeTokens(id);
        audit(operator,"USER_ROLE_ASSIGN",id,Map.of("before",before,"after",List.of(dto.role())));
        disconnectAfterCommit(id);return view(users.selectById(id));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO assignDevices(Long id,AssignDevicesDTO dto) {
        Long operator=administrator();lockAdministration();requireLocked(id);validateDevices(dto.deviceIds());
        List<Long> before=rbac.deviceIds(id);rbac.revokeDevices(id);
        for(Long device:new LinkedHashSet<>(dto.deviceIds())) rbac.assignDevice(id,device,operator);
        rbac.revokeTokens(id);audit(operator,"USER_DEVICE_ASSIGN",id,Map.of("before",before,"after",dto.deviceIds()));
        disconnectAfterCommit(id);return view(users.selectById(id));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void resetPassword(Long id,ResetPasswordDTO dto) {
        Long operator=administrator();lockAdministration();requireLocked(id);PasswordPolicy.validate(dto.password());
        rbac.changePassword(id,passwords.encode(dto.password()),true);audit(operator,"USER_PASSWORD_RESET",id,Map.of());disconnectAfterCommit(id);
    }
    @Transactional public void changeOwnPassword(ChangePasswordDTO dto) {
        Long id=UserContext.requireCurrentUser().getId();SysUserPO user=requireLocked(id);
        if(!passwords.matches(dto.currentPassword(),user.getPasswordHash())) throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        PasswordPolicy.validate(dto.newPassword());
        if(passwords.matches(dto.newPassword(),user.getPasswordHash())) throw new BusinessException(ErrorCode.INVALID_REQUEST,"新密码不能与原密码相同");
        rbac.changePassword(id,passwords.encode(dto.newPassword()),false);audit(id,"USER_PASSWORD_CHANGE",id,Map.of());disconnectAfterCommit(id);
    }
    /** Logout revokes every login credential of the account. */
    @Transactional public void logout() {
        Long id=UserContext.requireCurrentUser().getId();rbac.revokeTokens(id);audit(id,"USER_LOGOUT",id,Map.of());disconnectAfterCommit(id);
    }
    private void lockAdministration() {
        // READ_COMMITTED is essential: after waiting for this lock, do not reuse a pre-lock MySQL snapshot.
        if(rbac.lockAdministratorRole()==null) throw new IllegalStateException("RBAC seed migration is required");
        access.requireCurrent("system:user:manage");
    }
    private SysUserPO requireLocked(Long id) {
        SysUserPO user=rbac.lockUser(id);if(user==null) throw new BusinessException(ErrorCode.NOT_FOUND,"用户不存在");return user;
    }
    private void protectLastAdmin(SysUserPO user,String nextStatus,List<String> roles) {
        if("ENABLED".equals(user.getStatus()) && rbac.roles(user.getId()).contains("SYS_ADMIN")
                && (!"ENABLED".equals(nextStatus) || !roles.contains("SYS_ADMIN")) && rbac.enabledAdministrators()<=1)
            throw new BusinessException(ErrorCode.CONFLICT,"不能禁用或降级最后一名启用的管理员");
    }
    private void validateRole(String role) {if(!Set.of("SYS_ADMIN","USER").contains(role)) throw new BusinessException(ErrorCode.INVALID_REQUEST,"角色不存在");}
    private void validateDevices(List<Long> ids) {
        for(Long id:new LinkedHashSet<>(ids)) if(devices.selectById(id)==null) throw new BusinessException(ErrorCode.INVALID_REQUEST,"机器不存在");
    }
    private ManagedUserVO view(SysUserPO user) {return new ManagedUserVO(user,rbac.roles(user.getId()),rbac.deviceIds(user.getId()));}
    private void audit(Long operator,String action,Long target,Object detail) {
        try {rbac.audit(operator,action,"USER",String.valueOf(target),json.writeValueAsString(detail));}
        catch(JsonProcessingException ex) {throw new IllegalStateException(ex);}
    }
    private void disconnectAfterCommit(Long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {sockets.disconnectUser(id);}
        });
    }
}
