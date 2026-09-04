package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.SysUserPO;
import java.time.LocalDateTime;
import java.util.List;
public record ManagedUserVO(Long id, String username, String displayName, String status,
                            boolean mustChangePassword, List<String> roles, List<Long> deviceIds,
                            LocalDateTime lastLoginAt, LocalDateTime createdAt) {
    public ManagedUserVO(SysUserPO u, List<String> roles, List<Long> devices) {
        this(u.getId(),u.getUsername(),u.getDisplayName(),u.getStatus(),u.isMustChangePassword(),roles,devices,u.getLastLoginAt(),u.getCreatedAt());
    }
}

