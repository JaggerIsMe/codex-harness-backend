package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SysUserPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface SysUserMapper {

    SysUserPO selectByEmail(@Param("email") String email);

    SysUserPO lockByEmail(@Param("email") String email);
    SysUserPO lockById(@Param("id") Long id);
    int revokeVersion(@Param("id") Long id,@Param("expectedVersion") long expectedVersion);

    SysUserPO selectById(@Param("id") Long id);

    int insert(SysUserPO user);

    int advanceLogin(@Param("id") Long id, @Param("expectedVersion") long expectedVersion,
                     @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
