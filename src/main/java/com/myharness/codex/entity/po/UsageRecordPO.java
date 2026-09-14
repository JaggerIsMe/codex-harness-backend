package com.myharness.codex.entity.po;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public class UsageRecordPO {
    public String requestId, state, responseId, actualModel, outcome, dayPeriod, monthPeriod;
    public Long userId, turnId, deviceId, modelVersionId, priceVersionId;
    public Long projectId, conversationId, expertVersionId;
    public String displayName, modelName;
    public Long inputTokens, cachedTokens, outputTokens, reasoningTokens;
    public BigDecimal reservedAmount, cost;
    public LocalDateTime createdAt, settledAt;
}
