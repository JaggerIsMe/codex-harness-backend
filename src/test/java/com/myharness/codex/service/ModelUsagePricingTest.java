package com.myharness.codex.service;

import com.myharness.codex.entity.dto.UsageDTO;
import com.myharness.codex.entity.po.ModelPricePO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ModelUsagePricingTest {
    private ModelPricePO price(){var p=new ModelPricePO();p.inputRate=new BigDecimal("2");p.cachedRate=new BigDecimal("0.2");p.outputRate=new BigDecimal("3");p.maxOutputTokens=1000;return p;}
    @Test void cachedAndReasoningAreSubsetsAndNoMoneyIsRoundedToCents() {
        var u=new UsageDTO.Settlement("id",10000L,8000L,1000L,600L,"response","model","REPORTED");
        assertEquals(0,new BigDecimal("0.0086").compareTo(ModelUsagePricing.cost(price(),u)));
        assertEquals(0,new BigDecimal("0.000002").compareTo(ModelUsagePricing.cost(price(),new UsageDTO.Settlement("id",1L,0L,0L,0L,null,null,null))));
    }
    @Test void missingOrInconsistentCountsCannotBeBilledAsZero() {
        assertFalse(ModelUsagePricing.complete(new UsageDTO.Settlement("id",1L,null,1L,0L,null,null,null)));
        assertFalse(ModelUsagePricing.complete(new UsageDTO.Settlement("id",1L,2L,1L,0L,null,null,null)));
        assertFalse(ModelUsagePricing.complete(new UsageDTO.Settlement("id",1L,0L,1L,2L,null,null,null)));
        assertFalse(ModelUsagePricing.complete(new UsageDTO.Settlement("id",-1L,0L,1L,0L,null,null,null)));
    }
    @Test void reservationAssumesNoCacheDiscountAndBoundsOutput() {
        assertEquals(0,new BigDecimal("0.259").compareTo(ModelUsagePricing.reserve(price(),128000)));
    }
    @Test void configuredOutputLimitSupportsLargeModelsAndStillRequiresPositiveValues() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(int limit:new int[]{384000,393216,1048576}) {
                var dto=new UsageDTO.Price(1L,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,limit);
                assertTrue(validator.validate(dto).isEmpty());
            }
            for(int limit:new int[]{0,-1}) {
                var dto=new UsageDTO.Price(1L,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,limit);
                assertFalse(validator.validate(dto).isEmpty());
            }
        }
        var p=price();p.maxOutputTokens=393216;
        assertEquals(0,new BigDecimal("3.2768").compareTo(ModelUsagePricing.reserve(p,1048576)));
    }
}
