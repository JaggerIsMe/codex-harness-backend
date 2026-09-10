package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SystemInitializationPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface SystemInitializationMapper {
    @Select("SELECT * FROM system_initialization WHERE initialization_key=#{key} FOR UPDATE")
    SystemInitializationPO lock(String key);

    @Insert("INSERT INTO system_initialization(initialization_key,user_id,email) VALUES(#{initializationKey},#{userId},#{email})")
    int insert(SystemInitializationPO initialization);

    @Update("UPDATE system_initialization SET completed_at=#{now} WHERE user_id=#{userId} AND completed_at IS NULL")
    int complete(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM sys_user")
    long userCount();

    @Select("SELECT p.permission_code FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id "
            + "JOIN sys_permission p ON p.id=rp.permission_id WHERE r.role_code=#{role} AND r.status='ENABLED'")
    List<String> rolePermissions(String role);
}
