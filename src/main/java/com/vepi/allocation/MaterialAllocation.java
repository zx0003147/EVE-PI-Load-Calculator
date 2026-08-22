package com.vepi.allocation;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;

/**
 * One external material allocated to one planet: commodity, its PI tier
 * (2 = P2, 3 = P3, ...), exact integer quantity and exact volume.
 *
 * <p>The tier lets the UI separate "P2 to load" (tier 2) from
 * "P3 from inventory" (tier 3) without hand-written name lists.
 */
public record MaterialAllocation(PiCommodity commodity, int tier, long quantity, BigDecimal volume) {

    public long typeId() { return commodity.typeId(); }

    public String name() { return commodity.name(); }

    @Override
    public String toString() {
        return name() + " (P" + tier + "): " + quantity + " ("
                + volume.stripTrailingZeros().toPlainString() + " m3)";
    }
}
