package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.SysUserPO;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
public record ManagedUserVO(Long id, String email, String displayName, String status,
                            boolean activated, String activationEmailStatus,
                            boolean mustChangePassword, List<String> roles, List<Long> deviceIds, List<Long> expertIds,
                            OffsetDateTime lastLoginAt, LocalDateTime createdAt) {
    public ManagedUserVO(SysUserPO u, List<String> roles, List<Long> devices, List<Long> experts, String activationEmailStatus) {
        this(u.getId(),u.getEmail(),u.getDisplayName(),u.getStatus(),u.isActivated(),activationEmailStatus,u.isMustChangePassword(),List.copyOf(roles),List.copyOf(devices),List.copyOf(experts),u.getLastLoginAt()==null?null:u.getLastLoginAt().atOffset(ZoneOffset.UTC),u.getCreatedAt());
    }
}

