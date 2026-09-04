package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SysUserPO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface SysUserMapper {

    SysUserPO selectByUsername(@Param("username") String username);

    SysUserPO selectById(@Param("id") Long id);

    int insert(SysUserPO user);

    int updateLastLoginAt(@Param("id") Long id, @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
