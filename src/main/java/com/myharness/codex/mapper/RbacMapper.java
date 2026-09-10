package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.SysUserPO;
import com.myharness.codex.entity.vo.RoleVO;
import com.myharness.codex.entity.vo.ExecutableDeviceVO;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface RbacMapper {
    @Select("SELECT r.role_code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=#{userId} AND r.status='ENABLED'")
    List<String> roles(Long userId);

    @Select("SELECT DISTINCT p.permission_code FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id " +
            "JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id " +
            "WHERE ur.user_id=#{userId} AND r.status='ENABLED'")
    List<String> permissions(Long userId);

    @Select("SELECT role_code code,role_name name FROM sys_role WHERE status='ENABLED' ORDER BY id")
    List<RoleVO> listRoles();

    @Select("SELECT id FROM sys_role WHERE role_code='SYS_ADMIN' FOR UPDATE")
    Long lockAdministratorRole();

    @Select("SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id JOIN sys_role r ON r.id=ur.role_id " +
            "WHERE u.status='ENABLED' AND u.activated_at IS NOT NULL AND u.email_verified_at IS NOT NULL AND u.password_hash IS NOT NULL AND r.role_code='SYS_ADMIN' AND r.status='ENABLED'")
    int enabledAdministrators();

    @Delete("DELETE FROM sys_user_role WHERE user_id=#{userId}")
    int deleteRoles(Long userId);

    @Insert("INSERT INTO sys_user_role(user_id,role_id) SELECT #{userId},id FROM sys_role WHERE role_code=#{role} AND status='ENABLED'")
    int assignRole(@Param("userId") Long userId,@Param("role") String role);

    @Select("SELECT COUNT(*) FROM user_device_assignment WHERE user_id=#{userId} AND device_id=#{deviceId} AND status='ENABLED'")
    int assigned(@Param("userId") Long userId,@Param("deviceId") Long deviceId);

    @Select("SELECT device_id FROM user_device_assignment WHERE user_id=#{userId} AND status='ENABLED' ORDER BY device_id")
    List<Long> deviceIds(Long userId);

    @Update("UPDATE user_device_assignment SET status='DISABLED' WHERE user_id=#{userId}")
    int revokeDevices(Long userId);

    @Insert("INSERT INTO user_device_assignment(user_id,device_id,status,assigned_by) VALUES(#{userId},#{deviceId},'ENABLED',#{operatorId}) " +
            "ON DUPLICATE KEY UPDATE status='ENABLED',assigned_by=VALUES(assigned_by)")
    int assignDevice(@Param("userId") Long userId,@Param("deviceId") Long deviceId,@Param("operatorId") Long operatorId);

    @Select("SELECT expert_id FROM user_expert_assignment WHERE user_id=#{userId} AND status='ENABLED' ORDER BY expert_id")
    List<Long> expertIds(Long userId);

    @Select("SELECT COUNT(*) FROM user_expert_assignment WHERE user_id=#{userId} AND expert_id=#{expertId} AND status='ENABLED'")
    int expertAssigned(@Param("userId") Long userId,@Param("expertId") Long expertId);

    @Update("UPDATE user_expert_assignment SET status='DISABLED' WHERE user_id=#{userId}")
    int revokeExperts(Long userId);

    @Insert("INSERT INTO user_expert_assignment(user_id,expert_id,status,assigned_by) VALUES(#{userId},#{expertId},'ENABLED',#{operatorId}) " +
            "ON DUPLICATE KEY UPDATE status='ENABLED',assigned_by=VALUES(assigned_by)")
    int assignExpert(@Param("userId") Long userId,@Param("expertId") Long expertId,@Param("operatorId") Long operatorId);

    @Select("SELECT d.id,d.device_name,d.status,d.isolation_mode,EXISTS(SELECT 1 FROM agent_workspace_root w WHERE w.device_id=d.id AND w.status='ENABLED') provisioning_available " +
            "FROM agent_device d JOIN user_device_assignment a ON a.device_id=d.id AND a.user_id=#{userId} AND a.status='ENABLED' ORDER BY d.device_name,d.id")
    List<ExecutableDeviceVO> executableDevices(Long userId);

    String USER_FILTER = " WHERE (#{keyword}='' OR email LIKE CONCAT('%',LOWER(#{keyword}),'%') OR display_name LIKE CONCAT('%',#{keyword},'%')) "
            + "AND (#{status} IS NULL OR #{status}='' "
            + "OR (#{status}='DISABLED' AND status='DISABLED') "
            + "OR (#{status}='ENABLED' AND status='ENABLED' AND activated_at IS NOT NULL AND email_verified_at IS NOT NULL AND password_hash IS NOT NULL) "
            + "OR (#{status}='PENDING' AND status='ENABLED' AND (activated_at IS NULL OR email_verified_at IS NULL OR password_hash IS NULL)))";

    @Select("SELECT * FROM sys_user" + USER_FILTER + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<SysUserPO> users(@Param("keyword") String keyword,@Param("status") String status,@Param("limit") int limit,@Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM sys_user" + USER_FILTER)
    long countUsers(@Param("keyword") String keyword,@Param("status") String status);

    @Select("SELECT * FROM sys_user WHERE id=#{userId} FOR UPDATE")
    SysUserPO lockUser(Long userId);

    @Update("UPDATE sys_user SET display_name=#{displayName},status=#{status},token_version=token_version+1 WHERE id=#{id}")
    int updateUser(@Param("id") Long id,@Param("displayName") String displayName,@Param("status") String status);

    @Update("UPDATE sys_user SET password_hash=#{hash},must_change_password=#{mustChange},password_changed_at=UTC_TIMESTAMP(3),token_version=token_version+1 WHERE id=#{id}")
    int changePassword(@Param("id") Long id,@Param("hash") String hash,@Param("mustChange") boolean mustChange);

    @Update("UPDATE sys_user SET token_version=token_version+1 WHERE id=#{id}")
    int revokeTokens(Long id);

    @Insert("INSERT INTO audit_log(operator_type,operator_id,action,target_type,target_id,result,detail) " +
            "VALUES('USER',#{operatorId},#{action},#{targetType},#{targetId},'SUCCESS',#{detail})")
    int audit(@Param("operatorId") Long operatorId,@Param("action") String action,@Param("targetType") String targetType,
              @Param("targetId") String targetId,@Param("detail") String detail);
}
