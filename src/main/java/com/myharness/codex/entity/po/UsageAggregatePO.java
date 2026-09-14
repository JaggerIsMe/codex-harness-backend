package com.myharness.codex.entity.po;
import java.math.BigDecimal;
public class UsageAggregatePO {
    public String dimension;
    public long requests, inputTokens, cachedTokens, outputTokens, pending;
    public BigDecimal cost;
}
