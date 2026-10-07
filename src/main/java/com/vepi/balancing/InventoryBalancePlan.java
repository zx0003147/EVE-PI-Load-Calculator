package com.vepi.balancing;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of the <b>Balance Inventory</b> feature. P2 and P3 are balanced as
 * two independent tiers. The legacy top-level fields remain the P2 view so
 * existing callers keep exactly the same P2 semantics.
 *
 * <p>Deliberately independent of the multi-planet allocation model — this
 * feature has no planet capacity, no per-planet split, no runtime fairness.
 *
 * @param targetBlocks           P2 max over ceil(current / requiredPerBlock)
 * @param blockDurationSeconds   duration of one sustainable block
 * @param materials              one row per template-required P2 (name-sorted)
 * @param unusedInventory        inventory items absent from both the template's
 *                               P2 and P3 external requirements, name-sorted
 * @param expectedFinalOutputs   legacy diagnostic output projection, based on
 *                               P2 blocks when present, otherwise P3 blocks
 * @param totalCurrentVolume     sum of current volumes over materials
 * @param totalTargetVolume      sum of target volumes over materials
 * @param totalAdditionalVolume  sum of add volumes over materials — the m³ the
 *                               user still has to buy/produce for P2
 * @param p3Balance              the independent P3 result
 */
public record InventoryBalancePlan(
        long targetBlocks,
        long blockDurationSeconds,
        List<InventoryBalanceMaterial> materials,
        List<UnusedItem> unusedInventory,
        List<ExpectedOutput> expectedFinalOutputs,
        BigDecimal totalCurrentVolume,
        BigDecimal totalTargetVolume,
        BigDecimal totalAdditionalVolume,
        TierBalance p3Balance,
        long p2P4RecipeCyclesPerBalanceBlock,
        long p2P4UnitsPerBalanceBlock) {

    /** Backwards-compatible constructor for the original P2-only result. */
    public InventoryBalancePlan(long targetBlocks,
                                long blockDurationSeconds,
                                List<InventoryBalanceMaterial> materials,
                                List<UnusedItem> unusedInventory,
                                List<ExpectedOutput> expectedFinalOutputs,
                                BigDecimal totalCurrentVolume,
                                BigDecimal totalTargetVolume,
                                BigDecimal totalAdditionalVolume) {
        this(targetBlocks, blockDurationSeconds, materials, unusedInventory,
                expectedFinalOutputs, totalCurrentVolume, totalTargetVolume,
                totalAdditionalVolume, TierBalance.empty(3, blockDurationSeconds), 0, 0);
    }

    /** Compatibility constructor for callers that do not carry executable-block metadata. */
    public InventoryBalancePlan(long targetBlocks,
                                long blockDurationSeconds,
                                List<InventoryBalanceMaterial> materials,
                                List<UnusedItem> unusedInventory,
                                List<ExpectedOutput> expectedFinalOutputs,
                                BigDecimal totalCurrentVolume,
                                BigDecimal totalTargetVolume,
                                BigDecimal totalAdditionalVolume,
                                TierBalance p3Balance) {
        this(targetBlocks, blockDurationSeconds, materials, unusedInventory,
                expectedFinalOutputs, totalCurrentVolume, totalTargetVolume,
                totalAdditionalVolume, p3Balance, 0, 0);
    }

    public InventoryBalancePlan {
        materials = List.copyOf(materials);
        unusedInventory = List.copyOf(unusedInventory);
        expectedFinalOutputs = List.copyOf(expectedFinalOutputs);
        if (p3Balance == null) {
            p3Balance = TierBalance.empty(3, blockDurationSeconds);
        }
    }

    /** Independent P2 result; also exposed through the legacy top-level accessors. */
    public TierBalance p2Balance() {
        return new TierBalance(2, targetBlocks, blockDurationSeconds, materials,
                totalCurrentVolume, totalTargetVolume, totalAdditionalVolume,
                p2P4RecipeCyclesPerBalanceBlock, p2P4UnitsPerBalanceBlock);
    }

    /** Total purchase/production volume across the two independently balanced tiers. */
    public BigDecimal totalPurchaseVolume() {
        return totalAdditionalVolume.add(p3Balance.totalAdditionalVolume());
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

    /** One independently calculated tier balance. */
    public record TierBalance(
            int tier,
            long targetBlocks,
            long blockDurationSeconds,
            List<InventoryBalanceMaterial> materials,
            BigDecimal totalCurrentVolume,
            BigDecimal totalTargetVolume,
            BigDecimal totalAdditionalVolume,
            long p4RecipeCyclesPerBalanceBlock,
            long p4UnitsPerBalanceBlock) {

        public TierBalance {
            materials = List.copyOf(materials);
        }

        public static TierBalance empty(int tier, long blockDurationSeconds) {
            return new TierBalance(tier, 0, blockDurationSeconds, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        /** Compatibility constructor for the legacy template-driven tests. */
        public TierBalance(int tier, long targetBlocks, long blockDurationSeconds,
                           List<InventoryBalanceMaterial> materials,
                           BigDecimal totalCurrentVolume, BigDecimal totalTargetVolume,
                           BigDecimal totalAdditionalVolume) {
            this(tier, targetBlocks, blockDurationSeconds, materials, totalCurrentVolume,
                    totalTargetVolume, totalAdditionalVolume, 0, 0);
        }

        public boolean hasRequirements() {
            return !materials.isEmpty();
        }

        public long productionTimeSeconds() {
            return Math.multiplyExact(targetBlocks, blockDurationSeconds);
        }

        public long equivalentP4Units() {
            return Math.multiplyExact(targetBlocks, p4UnitsPerBalanceBlock);
        }
    }
}
