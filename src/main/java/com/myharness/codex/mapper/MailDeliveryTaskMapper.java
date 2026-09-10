package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.MailDeliveryTaskPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.LocalDateTime;
import java.util.List;

public interface MailDeliveryTaskMapper {
    @Insert("""
        INSERT INTO mail_delivery_task(user_id,challenge_id,template,recipient,encrypted_payload,status,attempts,
          next_attempt_at,expires_at,idempotency_key,created_at,updated_at)
        VALUES(#{userId},#{challengeId},#{template},#{recipient},#{encryptedPayload},'PENDING',0,
          #{nextAttemptAt},#{expiresAt},#{idempotencyKey},#{createdAt},#{updatedAt})
        ON DUPLICATE KEY UPDATE id = id
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MailDeliveryTaskPO task);

    @Select("""
        SELECT id FROM mail_delivery_task
        WHERE ((status = 'PENDING' AND next_attempt_at <= #{now})
          OR (status = 'PROCESSING' AND lease_until <= #{now}))
          AND expires_at > #{now} AND attempts < #{maxAttempts}
        ORDER BY next_attempt_at, id LIMIT #{limit}
        """)
    List<Long> findAvailable(@Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Update("""
        UPDATE mail_delivery_task SET status = 'PROCESSING', lease_until = #{leaseUntil},
          lease_token = #{leaseToken}, attempts = attempts + 1, updated_at = #{now}
        WHERE id = #{id} AND expires_at > #{now} AND attempts < #{maxAttempts}
          AND ((status = 'PENDING' AND next_attempt_at <= #{now})
            OR (status = 'PROCESSING' AND lease_until <= #{now}))
        """)
    int claim(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil, @Param("maxAttempts") int maxAttempts);

    @Select("SELECT * FROM mail_delivery_task WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}")
    MailDeliveryTaskPO selectClaimed(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("""
        UPDATE mail_delivery_task SET status = #{status}, encrypted_payload = NULL, lease_token = NULL,
          lease_until = NULL, error_code = #{errorCode}, accepted_at = #{acceptedAt}, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
        """)
    int finish(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("acceptedAt") LocalDateTime acceptedAt, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE mail_delivery_task SET status = 'PENDING', lease_token = NULL, lease_until = NULL,
          error_code = 'SMTP_TEMPORARY_FAILURE', next_attempt_at = #{nextAttemptAt}, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING' AND lease_token = #{leaseToken}
        """)
    int retry(@Param("id") Long id, @Param("leaseToken") String leaseToken,
              @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("now") LocalDateTime now);

    @Update("""
        <script>
        UPDATE mail_delivery_task SET status = 'CANCELLED', encrypted_payload = NULL,
          lease_token = NULL, lease_until = NULL, error_code = 'CHALLENGE_REVOKED', updated_at = CURRENT_TIMESTAMP(3)
        WHERE user_id = #{userId} AND challenge_id IS NOT NULL AND status IN ('PENDING','PROCESSING')
        <if test='purpose != null'> AND template = #{purpose} </if>
        </script>
        """)
    int cancelChallenges(@Param("userId") Long userId, @Param("purpose") String purpose);

    @Select("SELECT status FROM mail_delivery_task WHERE user_id = #{userId} AND template = 'ACTIVATION' ORDER BY id DESC LIMIT 1")
    String latestActivationStatus(@Param("userId") Long userId);

    @Update("""
        UPDATE mail_delivery_task SET status = 'CANCELLED', encrypted_payload = NULL,
          lease_token = NULL, lease_until = NULL, error_code = 'EXPIRED', updated_at = #{now}
        WHERE status IN ('PENDING','PROCESSING') AND expires_at <= #{now} LIMIT 1000
        """)
    int expire(@Param("now") LocalDateTime now);

    @Update("""
        UPDATE mail_delivery_task SET status = 'FAILED', encrypted_payload = NULL,
          lease_token = NULL, lease_until = NULL, error_code = 'RETRY_EXHAUSTED', updated_at = #{now}
        WHERE attempts >= #{maxAttempts} AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_until <= #{now})) LIMIT 1000
        """)
    int failExhausted(@Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts);

    @Delete("DELETE FROM mail_delivery_task WHERE status IN ('ACCEPTED','FAILED','CANCELLED') AND updated_at < #{before} LIMIT 1000")
    int deleteOldMetadata(@Param("before") LocalDateTime before);
}
