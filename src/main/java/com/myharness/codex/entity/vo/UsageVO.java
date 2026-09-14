package com.myharness.codex.entity.vo;
import com.myharness.codex.entity.po.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public final class UsageVO {
    private UsageVO() {}
    public record Price(Long id, Long modelVersionId, String modelName, String modelId, String currency,
        String rule, BigDecimal inputRate, BigDecimal cachedRate, BigDecimal outputRate, int maxOutputTokens) {
        public Price(ModelPricePO p) {this(p.id,p.modelVersionId,p.modelName,p.modelId,p.currency,p.rule,p.inputRate,p.cachedRate,p.outputRate,p.maxOutputTokens);}
    }
    public record Policy(Long userId, BigDecimal dailyBudget, BigDecimal monthlyBudget, int maxConcurrentTurns) {
        public Policy(UserQuotaPO p){this(p.userId,p.dailyBudget,p.monthlyBudget,p.maxConcurrentTurns);}
    }
    public record Permit(String requestId, int maxOutputTokens, BigDecimal reservedAmount) {}
    public record Record(String requestId, Long userId, String displayName, Long turnId, Long deviceId,
        Long projectId, Long conversationId, Long expertVersionId,
        Long modelVersionId, String modelName, String state, Long inputTokens, Long cachedTokens,
        Long outputTokens, Long reasoningTokens, BigDecimal cost, BigDecimal reservedAmount,
        LocalDateTime createdAt, String outcome) {
        public Record(UsageRecordPO p){this(p.requestId,p.userId,p.displayName,p.turnId,p.deviceId,p.projectId,p.conversationId,p.expertVersionId,p.modelVersionId,
            p.modelName,p.state,p.inputTokens,p.cachedTokens,p.outputTokens,p.reasoningTokens,p.cost,p.reservedAmount,p.createdAt,p.outcome);}
    }
    public record Bucket(String period, BigDecimal budget, BigDecimal spent, BigDecimal reserved,
        BigDecimal remaining, String alert) {}
    public record Summary(Policy policy, List<Bucket> buckets) {}
    public record Aggregate(String dimension,long requests,long inputTokens,long cachedTokens,long outputTokens,long pending,BigDecimal cost) {
        public Aggregate(UsageAggregatePO p){this(p.dimension,p.requests,p.inputTokens,p.cachedTokens,p.outputTokens,p.pending,p.cost);}
    }
    public record Page(List<Record> records, long total, int page, int pageSize, List<Aggregate> daily) {}
}
