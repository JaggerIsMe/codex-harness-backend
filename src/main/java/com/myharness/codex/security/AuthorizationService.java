package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.RbacMapper;
import com.myharness.codex.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AuthorizationService {
    private final RbacMapper rbac;
    private final SysUserMapper users;
    public AuthorizationService(RbacMapper rbac, SysUserMapper users) { this.rbac=rbac; this.users=users; }
    public SysUserPO requireEnabled(Long id) {
        SysUserPO user=users.selectById(id);
        if(user==null || !"ENABLED".equals(user.getStatus())) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return user;
    }
    public boolean hasPermission(Long id,String permission) {
        SysUserPO user=users.selectById(id);
        return user!=null && "ENABLED".equals(user.getStatus()) && !user.isMustChangePassword() && rbac.permissions(id).contains(permission);
    }
    public void requirePermission(Long id,String permission) {
        if(!hasPermission(id,permission)) throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    public void requireCurrent(String permission) { requirePermission(UserContext.requireCurrentUser().getId(),permission); }
    public void requireDevice(Long userId,Long deviceId) {
        requirePermission(userId,"workspace:use");
        if(rbac.assigned(userId,deviceId)!=1) throw new BusinessException(ErrorCode.FORBIDDEN,"未获得该机器的使用授权");
    }
    public boolean canUseDevice(Long userId,Long deviceId) {
        return hasPermission(userId,"workspace:use") && rbac.assigned(userId,deviceId)==1;
    }
    public List<String> roles(Long userId) { return rbac.roles(userId); }
    public List<String> permissions(Long userId) { return rbac.permissions(userId); }
}

