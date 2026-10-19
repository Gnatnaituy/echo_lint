package com.echolint.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合规抽样配置（app.sample.dfa-miss-rate）：默认关闭，越界/NaN 被钳制，避免配置笔误影响全量流水线。
 */
class AppPropertiesTest {

    @Test
    void samplingIsDisabledByDefault() {
        AppProperties properties = new AppProperties();

        assertEquals(0.0, properties.dfaMissSampleRate());
        assertFalse(properties.samplingEnabled());
    }

    @Test
    void rateIsClampedToValidRange() {
        AppProperties properties = new AppProperties();

        properties.getSample().setDfaMissRate(2.5);
        assertEquals(1.0, properties.dfaMissSampleRate());
        assertTrue(properties.samplingEnabled());

        properties.getSample().setDfaMissRate(-3);
        assertEquals(0.0, properties.dfaMissSampleRate());
        assertFalse(properties.samplingEnabled());

        properties.getSample().setDfaMissRate(0.35);
        assertEquals(0.35, properties.dfaMissSampleRate(), 1e-9);

        properties.getSample().setDfaMissRate(Double.NaN);
        assertEquals(0.0, properties.dfaMissSampleRate(), "NaN 按关闭处理");
    }

    @Test
    void nullOrMissingSampleBlockFallsBackToDisabled() {
        AppProperties properties = new AppProperties();
        properties.setSample(null);

        assertEquals(0.0, properties.dfaMissSampleRate());
        assertFalse(properties.samplingEnabled());
    }

    @Test
    void reviewApprovalsStillFloorAtOne() {
        AppProperties properties = new AppProperties();
        properties.getReview().setRequiredApprovals(0);

        assertEquals(1, properties.reviewRequiredApprovals());
    }
}
