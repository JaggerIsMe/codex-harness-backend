package com.myharness.codex.entity.po;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public class ModelPricePO {
    public Long id, modelVersionId, createdBy;
    public String modelName, modelId, currency, rule;
    public BigDecimal inputRate, cachedRate, outputRate;
    public int maxOutputTokens;
    public LocalDateTime createdAt;
}
