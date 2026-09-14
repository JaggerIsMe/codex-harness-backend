package com.myharness.codex.service;

import com.myharness.codex.entity.dto.UsageDTO;
import com.myharness.codex.entity.po.ModelPricePO;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** RESPONSES_TEXT_V1: cached input and reasoning output are subsets, not additional tokens. */
public final class ModelUsagePricing {
    private ModelUsagePricing() {}
    public static boolean complete(UsageDTO.Settlement u) {
        return u.inputTokens()!=null && u.cachedTokens()!=null && u.outputTokens()!=null && u.reasoningTokens()!=null
            && u.inputTokens()>=0 && u.cachedTokens()>=0 && u.outputTokens()>=0 && u.reasoningTokens()>=0
            && u.cachedTokens()<=u.inputTokens() && u.reasoningTokens()<=u.outputTokens()
            && u.inputTokens()<=100_000_000 && u.outputTokens()<=100_000_000;
    }
    public static BigDecimal cost(ModelPricePO p, UsageDTO.Settlement u) {
        if(!complete(u)) throw new IllegalArgumentException("Incomplete or inconsistent usage");
        return BigDecimal.valueOf(u.inputTokens()-u.cachedTokens()).multiply(p.inputRate)
            .add(BigDecimal.valueOf(u.cachedTokens()).multiply(p.cachedRate))
            .add(BigDecimal.valueOf(u.outputTokens()).multiply(p.outputRate))
            .divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.CEILING);
    }
    public static BigDecimal reserve(ModelPricePO p, int contextWindow) {
        return reserve(p,contextWindow,p.maxOutputTokens);
    }
    public static BigDecimal reserve(ModelPricePO p, int contextWindow,int outputLimit) {
        return BigDecimal.valueOf(contextWindow).multiply(p.inputRate.max(p.cachedRate))
            .add(BigDecimal.valueOf(outputLimit).multiply(p.outputRate))
            .divide(BigDecimal.valueOf(1_000_000),12,RoundingMode.CEILING);
    }
}
