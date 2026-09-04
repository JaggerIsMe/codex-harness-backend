package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.AgentEnrollmentPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AgentEnrollmentMapper {
    @Insert("INSERT INTO agent_enrollment(enrollment_code_hash,status,expires_at,created_by) " +
            "VALUES(#{enrollmentCodeHash},#{status},#{expiresAt},#{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentEnrollmentPO enrollment);

    @Select("SELECT id,enrollment_code_hash,status,expires_at,used_at,created_by " +
            "FROM agent_enrollment WHERE enrollment_code_hash=#{hash} FOR UPDATE")
    AgentEnrollmentPO selectByHashForUpdate(@Param("hash") String hash);

    @Update("UPDATE agent_enrollment SET status='USED',used_at=#{usedAt} " +
            "WHERE id=#{id} AND status='PENDING'")
    int markUsed(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);

    @Update("UPDATE agent_enrollment SET status='EXPIRED' WHERE id=#{id} AND status='PENDING'")
    int markExpired(@Param("id") Long id);
}
