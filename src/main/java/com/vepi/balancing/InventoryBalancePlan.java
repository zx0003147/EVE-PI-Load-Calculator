package com.vepi.balancing;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of the <b>Balance Inventory</b> feature: how many sustainable
 * production blocks are needed so that every unit of the user's current P2
 * stock (for the P2 items the selected template consumes) fits into whole
 * blocks, which P2 items must be topped up to which target level, and what
 * the completed plan finally produces.
 *
 * <p>Deliberately independent of the multi-planet allocation model — this
 * feature has no planet capacity, no per-planet split, no runtime fairness.
 *
 * @param targetBlocks           max over ceil(current / requiredPerBlock)
 * @param blockDurationSeconds   duration of one sustainable block
 * @param materials              one row per template-required P2 (name-sorted)
 * @param unusedInventory        inventory items NOT part of this balance
 *                               (P2 the template does not consume, plus all
 *                               non-P2 stock such as P3), name-sorted
 * @param expectedFinalOutputs   final products over ALL target blocks,
 *                               name-sorted ({@code perBlock × targetBlocks})
 * @param totalCurrentVolume     sum of current volumes over materials
 * @param totalTargetVolume      sum of target volumes over materials
 * @param totalAdditionalVolume  sum of add volumes over materials — the m³ the
 *                               user still has to buy/produce
 */
public record InventoryBalancePlan(
        long targetBlocks,
        long blockDurationSeconds,
        List<InventoryBalanceMaterial> materials,
        List<UnusedItem> unusedInventory,
        List<ExpectedOutput> expectedFinalOutputs,
        BigDecimal totalCurrentVolume,
        BigDecimal totalTargetVolume,
        BigDecimal totalAdditionalVolume) {

    public InventoryBalancePlan {
        materials = List.copyOf(materials);
        unusedInventory = List.copyOf(unusedInventory);
        expectedFinalOutputs = List.copyOf(expectedFinalOutputs);
    }

    /** Production time of the whole plan: {@code targetBlocks × blockDuration}. */
    public long productionTimeSeconds() {
        return Math.multiplyExact(targetBlocks, blockDurationSeconds);
    }

    /** An inventory item that does not participate in this balance. */
    public record UnusedItem(PiCommodity commodity, long quantity, int tier) {
    }

    /** Final product totals over the whole balanced plan. */
    public record ExpectedOutput(PiCommodity commodity, long quantity) {
    }
}
