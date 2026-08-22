package com.vepi.load;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;

/**
 * Recommended load of ONE external input commodity: an integer unit quantity
 * (EVE items cannot be split) plus its exact volume and the runtime it supports.
 *
 * <p>Because the plan is balanced, every material supports exactly the same
 * runtime: {@code blockCount x basePeriodSeconds}.
 */
public record RecommendedMaterialLoad(
        PiCommodity commodity,
        long quantity,
        BigDecimal volume,
        long supportedRuntimeSeconds) {

    public long typeId() { return commodity.typeId(); }

    @Override
    public String toString() {
        return commodity.name() + " (" + commodity.typeId() + "): "
                + quantity + " units, "
                + volume.stripTrailingZeros().toPlainString() + " m3";
    }
}
