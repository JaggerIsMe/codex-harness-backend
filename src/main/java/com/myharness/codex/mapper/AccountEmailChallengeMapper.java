package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.AccountEmailChallengePO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

public interface AccountEmailChallengeMapper {
    @Insert("INSERT INTO account_email_challenge(user_id,email,purpose,generation,digest,expires_at,status) "
            + "VALUES(#{userId},#{email},#{purpose},#{generation},#{digest},#{expiresAt},'ACTIVE')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AccountEmailChallengePO challenge);

    @Select("SELECT * FROM account_email_challenge WHERE digest=#{digest} AND purpose='ACTIVATION'")
    AccountEmailChallengePO findActivation(String digest);

    @Select("SELECT * FROM account_email_challenge WHERE id=#{id}")
    AccountEmailChallengePO findById(Long id);

    @Select("SELECT * FROM account_email_challenge WHERE id=#{id} FOR UPDATE")
    AccountEmailChallengePO lockById(Long id);

    @Select("SELECT * FROM account_email_challenge WHERE user_id=#{userId} AND purpose=#{purpose} ORDER BY id DESC LIMIT 1")
    AccountEmailChallengePO latest(@Param("userId") Long userId, @Param("purpose") String purpose);

    @Select("SELECT * FROM account_email_challenge WHERE user_id=#{userId} AND purpose=#{purpose} ORDER BY id DESC LIMIT 1 FOR UPDATE")
    AccountEmailChallengePO lockLatest(@Param("userId") Long userId, @Param("purpose") String purpose);

    @Update("UPDATE account_email_challenge SET status='REVOKED',revoked_at=#{now} WHERE user_id=#{userId} "
            + "AND status='ACTIVE' AND (#{purpose} IS NULL OR purpose=#{purpose})")
    int revoke(@Param("userId") Long userId, @Param("purpose") String purpose, @Param("now") LocalDateTime now);

    @Update("UPDATE account_email_challenge SET status='USED',used_at=#{now} WHERE id=#{id} AND status='ACTIVE' "
            + "AND expires_at>#{now} AND failed_attempts<#{maxFailures}")
    int consume(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("maxFailures") int maxFailures);

    // The caller commits this update before returning a verification error.
    @Update("UPDATE account_email_challenge SET revoked_at=CASE WHEN failed_attempts+1>=#{maxFailures} THEN #{now} ELSE revoked_at END, "
            + "status=CASE WHEN failed_attempts+1>=#{maxFailures} THEN 'REVOKED' ELSE status END, failed_attempts=failed_attempts+1 "
            + "WHERE id=#{id} AND status='ACTIVE'")
    int recordFailure(@Param("id") Long id, @Param("maxFailures") int maxFailures, @Param("now") LocalDateTime now);

    @Update("UPDATE sys_user SET password_hash=#{hash},display_name=#{displayName},email_verified_at=#{now},activated_at=#{now}, "
            + "password_changed_at=#{now},must_change_password=FALSE,token_version=token_version+1 "
            + "WHERE id=#{userId} AND status='ENABLED' AND activated_at IS NULL AND email_verified_at IS NULL AND password_hash IS NULL")
    int activateUser(@Param("userId") Long userId, @Param("hash") String hash, @Param("displayName") String displayName,
                     @Param("now") LocalDateTime now);

    @Insert("INSERT INTO audit_log(operator_type,operator_id,action,target_type,target_id,result,detail) "
            + "VALUES(#{operatorType},#{operatorId},#{action},'USER',#{targetId},'SUCCESS','{}')")
    int audit(@Param("operatorType") String operatorType, @Param("operatorId") Long operatorId,
              @Param("action") String action, @Param("targetId") String targetId);

    @Update("UPDATE account_email_challenge SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=#{now} LIMIT 1000")
    int expire(LocalDateTime now);

    @Delete("DELETE FROM account_email_challenge WHERE status IN ('USED','REVOKED','EXPIRED') AND expires_at<#{before} "
            + "AND (used_at IS NULL OR used_at<#{before}) AND (revoked_at IS NULL OR revoked_at<#{before}) "
            + "AND NOT EXISTS(SELECT 1 FROM mail_delivery_task t WHERE t.challenge_id=account_email_challenge.id) LIMIT 1000")
    int deleteOldMetadata(LocalDateTime before);
}
