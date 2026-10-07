package com.vepi.balancing;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One balanced P2 or P3 material of an {@link InventoryBalancePlan}.
 *
 * <p>All quantities are exact longs; volumes are exact BigDecimal products of
 * the commodity's SDE unit volume — no floating point anywhere.
 *
 * @param commodity        the P2/P3 item (SDE name + unit volume)
 * @param requiredPerBlock units consumed per sustainable production block
 * @param currentQuantity  what the user currently has (0 when absent)
 * @param targetQuantity   {@code targetBlocks × requiredPerBlock} — the level
 *                         every stock must reach so ALL of it fits whole blocks
 * @param addQuantity      {@code targetQuantity − currentQuantity} ≥ 0
 */
public record InventoryBalanceMaterial(
        PiCommodity commodity,
        long requiredPerBlock,
        long currentQuantity,
        long targetQuantity,
        long addQuantity) {

    public InventoryBalanceMaterial {
        Objects.requireNonNull(commodity, "commodity");
        if (requiredPerBlock <= 0) {
            throw new IllegalArgumentException("requiredPerBlock must be > 0");
        }
        if (currentQuantity < 0 || targetQuantity < 0 || addQuantity < 0) {
            throw new IllegalArgumentException("quantities must be >= 0");
        }
    }

    public BigDecimal currentVolume() {
        return volumeOf(currentQuantity);
    }

    public BigDecimal targetVolume() {
        return volumeOf(targetQuantity);
    }

    public BigDecimal addVolume() {
        return volumeOf(addQuantity);
    }

    private BigDecimal volumeOf(long quantity) {
        return commodity.volume().multiply(BigDecimal.valueOf(quantity));
    }
}
