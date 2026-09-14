package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class UsageDTO {
    private UsageDTO() {}
    public record Price(@NotNull Long modelVersionId,
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=8) BigDecimal inputRate,
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=8) BigDecimal cachedRate,
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=8) BigDecimal outputRate,
        @Min(1) int maxOutputTokens) {}
    public record Policy(@DecimalMin("0") @Digits(integer=8,fraction=8) BigDecimal dailyBudget,
        @DecimalMin("0") @Digits(integer=8,fraction=8) BigDecimal monthlyBudget,
        @Min(1) @Max(100) int maxConcurrentTurns) {}
    public record Reserve(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String requestId, boolean compact) {
        public Reserve(String requestId){this(requestId,false);}
    }
    public record Settlement(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String requestId,
        @PositiveOrZero Long inputTokens, @PositiveOrZero Long cachedTokens,
        @PositiveOrZero Long outputTokens, @PositiveOrZero Long reasoningTokens,
        @Size(max=200) String responseId, @Size(max=128) String actualModel,
        @Size(max=32) String outcome) {}
    public record Resolve(@NotBlank @Size(max=500) String reason, @NotNull Boolean noCharge,
        @PositiveOrZero Long inputTokens, @PositiveOrZero Long cachedTokens,
        @PositiveOrZero Long outputTokens, @PositiveOrZero Long reasoningTokens) {}
}
