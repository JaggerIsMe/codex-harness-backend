package com.myharness.codex.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.po.ExpertPO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import com.myharness.codex.service.mail.AccountMailService;
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
    private final ExpertMapper experts;
    private final PasswordEncoder passwords;
    private final AuthorizationService access;
    private final ClientEventWebSocketHandler sockets;
    private final ObjectMapper json;
    private final AccountEmailService accounts;
    private final AccountMailService mail;
    public UserManagementService(RbacMapper rbac,SysUserMapper users,AgentDeviceMapper devices,ExpertMapper experts,PasswordEncoder passwords,
                                 AuthorizationService access,ClientEventWebSocketHandler sockets,ObjectMapper json,
                                 AccountEmailService accounts,AccountMailService mail) {
        this.rbac=rbac;this.users=users;this.devices=devices;this.experts=experts;this.passwords=passwords;this.access=access;this.sockets=sockets;this.json=json;
        this.accounts=accounts;this.mail=mail;
    }
    private Long administrator() { Long id=UserContext.requireCurrentUser().getId();access.requirePermission(id,"system:user:manage");return id; }
    public PageVO<ManagedUserVO> list(String keyword,String status,int page,int size) {
        administrator();
        if(page<1 || page>100000 || size<1 || size>100 || status!=null && !status.isBlank() && !Set.of("ENABLED","DISABLED","PENDING").contains(status))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String query=keyword==null?"":keyword.trim();
        return new PageVO<>(rbac.users(query,status,size,(page-1)*size).stream().map(this::view).toList(),rbac.countUsers(query,status),page,size);
    }
    public List<RoleVO> roles() { administrator(); return rbac.listRoles(); }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO create(CreateUserDTO dto) {
        Long operator=administrator(); lockAdministration(); validateRole(dto.role());
        SysUserPO user=accounts.createInvitedUser(dto.email(),dto.role(),null,operator);
        audit(operator,"USER_CREATE",user.getId(),Map.of("role",dto.role()));
        return view(user);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public SendCodeVO resendActivation(Long id) {
        Long operator=administrator();lockAdministration();requireLocked(id);
        accounts.resendActivation(id);
        audit(operator,"USER_ACTIVATION_RESEND",id,Map.of());
        return new SendCodeVO(mail.resendCooldownSeconds());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO update(Long id,UpdateUserDTO dto) {
        Long operator=administrator(); lockAdministration(); SysUserPO user=requireLocked(id);
        protectLastAdmin(user,dto.status(),rbac.roles(id));
        rbac.updateUser(id,dto.displayName().trim(),dto.status());
        if("DISABLED".equals(dto.status())) accounts.revokeChallenges(id);
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
    @Transactional(isolation=Isolation.READ_COMMITTED) public ManagedUserVO assignExperts(Long id,AssignExpertsDTO dto) {
        Long operator=administrator();lockAdministration();requireLocked(id);
        List<Long> selected=new ArrayList<>(new LinkedHashSet<>(dto.expertIds()));
        if(selected.size()!=dto.expertIds().size()) throw new BusinessException(ErrorCode.INVALID_REQUEST,"专家列表不能重复");
        for(Long expertId:selected) {
            ExpertPO expert=experts.get(expertId);
            if(expert==null || !"PUBLISHED".equals(expert.getStatus()))
                throw new BusinessException(ErrorCode.INVALID_REQUEST,"只能分配已发布的专家");
        }
        List<Long> before=rbac.expertIds(id);rbac.revokeExperts(id);
        for(Long expertId:selected) rbac.assignExpert(id,expertId,operator);
        audit(operator,"USER_EXPERT_ASSIGN",id,Map.of("before",before,"after",selected));
        return view(users.selectById(id));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public void resetPassword(Long id,ResetPasswordDTO dto) {
        Long operator=administrator();lockAdministration();SysUserPO user=requireLocked(id);PasswordPolicy.validate(dto.password());
        if(!user.isActivated() || !"ENABLED".equals(user.getStatus()))
            throw new BusinessException(ErrorCode.CONFLICT,"只能重置已激活且启用账号的密码");
        rbac.changePassword(id,passwords.encode(dto.password()),true);accounts.revokeChallenges(id);
        mail.enqueuePasswordChanged(user);audit(operator,"USER_PASSWORD_RESET",id,Map.of());disconnectAfterCommit(id);
    }
    @Transactional public void changeOwnPassword(ChangePasswordDTO dto) {
        Long id=UserContext.requireCurrentUser().getId();SysUserPO user=requireLocked(id);
        if(!user.isActivated() || !"ENABLED".equals(user.getStatus())) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if(!passwords.matches(dto.currentPassword(),user.getPasswordHash())) throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        PasswordPolicy.validate(dto.newPassword());
        if(passwords.matches(dto.newPassword(),user.getPasswordHash())) throw new BusinessException(ErrorCode.INVALID_REQUEST,"新密码不能与原密码相同");
        rbac.changePassword(id,passwords.encode(dto.newPassword()),false);accounts.revokeChallenges(id);
        mail.enqueuePasswordChanged(user);audit(id,"USER_PASSWORD_CHANGE",id,Map.of());disconnectAfterCommit(id);
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
        if(user.isActivated() && "ENABLED".equals(user.getStatus()) && rbac.roles(user.getId()).contains("SYS_ADMIN")
                && (!"ENABLED".equals(nextStatus) || !roles.contains("SYS_ADMIN")) && rbac.enabledAdministrators()<=1)
            throw new BusinessException(ErrorCode.CONFLICT,"不能禁用或降级最后一名启用的管理员");
    }
    private void validateRole(String role) {if(!Set.of("SYS_ADMIN","USER").contains(role)) throw new BusinessException(ErrorCode.INVALID_REQUEST,"角色不存在");}
    private void validateDevices(List<Long> ids) {
        for(Long id:new LinkedHashSet<>(ids)) if(devices.selectById(id)==null) throw new BusinessException(ErrorCode.INVALID_REQUEST,"机器不存在");
    }
    private ManagedUserVO view(SysUserPO user) {return new ManagedUserVO(user,rbac.roles(user.getId()),rbac.deviceIds(user.getId()),rbac.expertIds(user.getId()),mail.latestActivationStatus(user.getId()));}
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
