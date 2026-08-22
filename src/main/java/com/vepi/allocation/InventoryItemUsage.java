package com.vepi.allocation;

import com.vepi.domain.PiCommodity;

/**
 * Per-item global inventory accounting for one allocation run:
 * original stock, total allocated across all planets, and what remains.
 */
public record InventoryItemUsage(PiCommodity commodity, int tier,
                                 long original, long allocated, long remaining) {

    public long typeId() { return commodity.typeId(); }

    public String name() { return commodity.name(); }

    @Override
    public String toString() {
        return name() + " (P" + tier + "): original " + original + ", allocated "
                + allocated + ", remaining " + remaining;
    }
}
