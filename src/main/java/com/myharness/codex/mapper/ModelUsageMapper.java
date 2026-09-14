package com.myharness.codex.mapper;

import com.myharness.codex.entity.po.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

public interface ModelUsageMapper {
    @Select("SELECT DATE_FORMAT(DATE_ADD(r.created_at,INTERVAL 8 HOUR),'%Y-%m-%d') dimension,COUNT(*) requests,COALESCE(SUM(input_tokens),0) input_tokens,COALESCE(SUM(cached_tokens),0) cached_tokens,COALESCE(SUM(output_tokens),0) output_tokens,COALESCE(SUM(cost),0) cost,SUM(state IN ('RESERVED','PENDING')) pending FROM model_usage_record r"+FILTER+"GROUP BY dimension ORDER BY dimension")
    List<UsageAggregatePO> daily(@Param("userId") Long uid,@Param("modelVersionId") Long model,@Param("turnId") Long turn,@Param("projectId") Long project,
        @Param("start") java.time.LocalDateTime start,@Param("end") java.time.LocalDateTime end,@Param("state") String state);
    @Insert("INSERT INTO model_price_version(model_version_id,input_rate,cached_rate,output_rate,max_output_tokens,created_by) VALUES(#{modelVersionId},#{inputRate},#{cachedRate},#{outputRate},#{maxOutputTokens},#{createdBy})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertPrice(ModelPricePO p);
    @Select("SELECT p.*,v.name model_name,JSON_UNQUOTE(JSON_EXTRACT(v.runtime_spec,'$.modelId')) model_id FROM model_price_version p JOIN model_configuration_version v ON v.id=p.model_version_id WHERE p.id=#{id}") ModelPricePO price(Long id);
    @Select("SELECT * FROM model_price_version WHERE model_version_id=#{id} ORDER BY id DESC LIMIT 1") ModelPricePO currentPrice(Long id);
    @Select("SELECT p.*,v.name model_name,JSON_UNQUOTE(JSON_EXTRACT(v.runtime_spec,'$.modelId')) model_id FROM model_price_version p JOIN model_configuration_version v ON v.id=p.model_version_id WHERE p.id=(SELECT MAX(q.id) FROM model_price_version q WHERE q.model_version_id=p.model_version_id) ORDER BY p.id DESC LIMIT 500") List<ModelPricePO> prices();
    // A duplicate-key no-op UPDATE acquires X immediately; INSERT IGNORE would first acquire S and deadlock on upgrade.
    @Insert("INSERT INTO user_quota_policy(user_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)") int ensurePolicy(Long id);
    @Select("SELECT * FROM user_quota_policy WHERE user_id=#{id} FOR UPDATE") UserQuotaPO lockPolicy(Long id);
    @Select("SELECT * FROM user_quota_policy WHERE user_id=#{id}") UserQuotaPO policy(Long id);
    @Update("UPDATE user_quota_policy SET daily_budget=#{dailyBudget},monthly_budget=#{monthlyBudget},max_concurrent_turns=#{maxConcurrentTurns} WHERE user_id=#{userId}") int updatePolicy(UserQuotaPO p);
    @Insert("INSERT IGNORE INTO user_quota_bucket(user_id,period) VALUES(#{userId},#{period})") int ensureBucket(@Param("userId") Long uid,@Param("period") String period);
    @Select("SELECT * FROM user_quota_bucket WHERE user_id=#{userId} AND period=#{period} FOR UPDATE") QuotaBucketPO bucket(@Param("userId") Long uid,@Param("period") String period);
    @Update("UPDATE user_quota_bucket SET spent=spent+#{spent},reserved=reserved+#{reserved} WHERE user_id=#{userId} AND period=#{period}")
    int changeBucket(@Param("userId") Long uid,@Param("period") String period,@Param("spent") BigDecimal spent,@Param("reserved") BigDecimal reserved);
    @Select("SELECT * FROM model_usage_record WHERE request_id=#{id}") UsageRecordPO request(String id);
    @Select("SELECT * FROM model_usage_record WHERE request_id=#{id} FOR UPDATE") UsageRecordPO lockRequest(String id);
    @Insert("INSERT INTO model_usage_record(request_id,user_id,turn_id,device_id,project_id,conversation_id,expert_version_id,model_version_id,price_version_id,state,reserved_amount,day_period,month_period,created_at) VALUES(#{requestId},#{userId},#{turnId},#{deviceId},#{projectId},#{conversationId},#{expertVersionId},#{modelVersionId},#{priceVersionId},#{state},#{reservedAmount},#{dayPeriod},#{monthPeriod},#{createdAt})") int insertUsage(UsageRecordPO p);
    @Update("UPDATE model_usage_record SET state=#{state},cost=#{cost},input_tokens=#{inputTokens},cached_tokens=#{cachedTokens},output_tokens=#{outputTokens},reasoning_tokens=#{reasoningTokens},response_id=#{responseId},actual_model=#{actualModel},outcome=#{outcome},settled_at=#{settledAt} WHERE request_id=#{requestId}") int updateUsage(UsageRecordPO p);
    @Insert("INSERT INTO user_quota_ledger(request_id,user_id,action,amount,operator_id,reason) VALUES(#{requestId},#{userId},#{action},#{amount},#{operatorId},#{reason})")
    int ledger(@Param("requestId") String rid,@Param("userId") Long uid,@Param("action") String action,@Param("amount") BigDecimal amount,@Param("operatorId") Long operator,@Param("reason") String reason);
    @Select("SELECT COUNT(*) FROM conversation_turn t JOIN conversation c ON c.id=t.conversation_id WHERE c.user_id=#{id} AND t.model_configuration_version_id IS NOT NULL AND t.status IN ('CREATED','RUNNING','WAITING_APPROVAL')") int activeTurns(Long id);
    String FILTER = " WHERE (#{userId} IS NULL OR r.user_id=#{userId}) AND (#{modelVersionId} IS NULL OR r.model_version_id=#{modelVersionId}) AND (#{turnId} IS NULL OR r.turn_id=#{turnId}) AND (#{projectId} IS NULL OR r.project_id=#{projectId}) AND r.created_at >= #{start} AND r.created_at < #{end} AND (#{state}='' OR r.state=#{state}) ";
    @Select("SELECT r.*,u.display_name,v.name model_name FROM model_usage_record r LEFT JOIN sys_user u ON u.id=r.user_id LEFT JOIN model_configuration_version v ON v.id=r.model_version_id"+FILTER+"ORDER BY r.created_at DESC,r.request_id LIMIT #{limit} OFFSET #{offset}")
    List<UsageRecordPO> records(@Param("userId") Long uid,@Param("modelVersionId") Long model,@Param("turnId") Long turn,@Param("projectId") Long project,
        @Param("start") java.time.LocalDateTime start,@Param("end") java.time.LocalDateTime end,@Param("state") String state,@Param("limit") int limit,@Param("offset") int offset);
    @Select("SELECT COUNT(*) FROM model_usage_record r"+FILTER)
    long count(@Param("userId") Long uid,@Param("modelVersionId") Long model,@Param("turnId") Long turn,@Param("projectId") Long project,
        @Param("start") java.time.LocalDateTime start,@Param("end") java.time.LocalDateTime end,@Param("state") String state);
}
