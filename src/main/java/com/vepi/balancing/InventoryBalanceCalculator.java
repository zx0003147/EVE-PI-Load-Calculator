package com.vepi.balancing;

import com.vepi.domain.PiCommodity;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongToIntFunction;

/**
 * Core of the <b>Balance Inventory</b> feature — an independent second
 * product function next to Load Allocation.
 *
 * <p>P2 and P3 use the same arithmetic, but are intentionally calculated as
 * separate pools. Stock in one tier can never increase the target block count
 * in the other tier.
 *
 * <p>Algorithm (spec §6–8, exact integer arithmetic throughout):
 * <ol>
 *   <li>per required item {@code i}: {@code blocksNeeded[i] = ceil(current[i] /
 *       requiredPerBlock[i])};</li>
 *   <li>inside each tier, {@code targetBlocks = max(blocksNeeded[i])}; P2 and
 *       P3 never share this value;</li>
 *   <li>{@code target[i] = targetBlocks × requiredPerBlock[i]} (≥ current[i]
 *       by construction, so {@code add ≥ 0} always);</li>
 *   <li>{@code add[i] = target[i] − current[i]}.</li>
 * </ol>
 *
 * <p>Only P2/P3 items present in the solver's external requirements take part.
 * Other inventory is reported as unused. No recipe is reconstructed here.
 */
public final class InventoryBalanceCalculator {

    /**
     * Product-driven balance entry.  P2 and P3 are evaluated in separate calls
     * to {@link #calculateTier}; inventory in one tier therefore cannot alter
     * the other tier's target or additions.
     */
    public InventoryBalancePlan calculate(P4BalanceRecipe recipe,
                                          InventorySnapshot inventory,
                                          LongToIntFunction tierOf,
                                          java.util.function.LongFunction<PiCommodity> commodity) {
        InventoryBalancePlan.TierBalance p2 = calculateTier(2, recipe.p2Requirements(),
                0, inventory, commodity, recipe.p2P4RecipeCyclesPerBlock(),
                recipe.p2P4UnitsPerBlock());
        InventoryBalancePlan.TierBalance p3 = calculateTier(3, recipe.p3Requirements(),
                0, inventory, commodity, recipe.p3P4RecipeCyclesPerBlock(),
                recipe.p3P4UnitsPerBlock());

        List<InventoryBalancePlan.UnusedItem> unused = unusedInventory(inventory,
                recipe.p2Requirements(), recipe.p3Requirements(), tierOf, commodity);
        return new InventoryBalancePlan(p2.targetBlocks(), 0, p2.materials(), unused,
                List.of(), p2.totalCurrentVolume(), p2.totalTargetVolume(),
                p2.totalAdditionalVolume(), p3, p2.p4RecipeCyclesPerBalanceBlock(),
                p2.p4UnitsPerBalanceBlock());
    }

    /**
     * @param plan      the template's sustainable production plan; its
     *                  external P2/P3 requirements are the only balance inputs
     *                  (source of {@code requiredPerBlock})
     * @param inventory the user's current stock (P2 and anything else)
     * @param tierOf    tier resolver (SDE) to tell P2 from other stock
     * @param commodity SDE commodity lookup (name + volume for display)
     */
    public InventoryBalancePlan calculate(SustainableProductionPlan plan,
                                          InventorySnapshot inventory,
                                          LongToIntFunction tierOf,
                                          java.util.function.LongFunction<PiCommodity> commodity) {
        // ---- 1. independent P2/P3 subsets from the solver output ----
        Map<Long, Long> p2Requirements = new TreeMap<>();
        Map<Long, Long> p3Requirements = new TreeMap<>();
        for (Map.Entry<Long, Long> e : plan.externalRequirementsPerBlock().entrySet()) {
            int tier = tierOf.applyAsInt(e.getKey());
            if (tier == 2) {
                p2Requirements.put(e.getKey(), e.getValue());
            } else if (tier == 3) {
                p3Requirements.put(e.getKey(), e.getValue());
            }
        }
        if (p2Requirements.isEmpty() && p3Requirements.isEmpty()) {
            throw new BalanceException.NoBalanceRequirements();
        }

        InventoryBalancePlan.TierBalance p2 = calculateTier(2, p2Requirements,
                plan.blockDurationSeconds(), inventory, commodity, 0, 0);
        InventoryBalancePlan.TierBalance p3 = calculateTier(3, p3Requirements,
                plan.blockDurationSeconds(), inventory, commodity, 0, 0);

        // ---- 2. stock not consumed by either tier balance ----
        List<InventoryBalancePlan.UnusedItem> unused = unusedInventory(inventory,
                p2Requirements, p3Requirements, tierOf, commodity);

        // Keep the historical output estimate attached to P2. Pure-P3 plans use
        // the P3 target so the legacy diagnostic remains useful.
        long outputBlocks = p2.hasRequirements() ? p2.targetBlocks() : p3.targetBlocks();
        List<InventoryBalancePlan.ExpectedOutput> outputs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : plan.finalOutputsPerBlock().entrySet()) {
            outputs.add(new InventoryBalancePlan.ExpectedOutput(
                    commodity.apply(e.getKey()),
                    Math.multiplyExact(e.getValue(), outputBlocks)));
        }
        outputs.sort(Comparator.comparing(o -> o.commodity().name(), String.CASE_INSENSITIVE_ORDER));

        return new InventoryBalancePlan(p2.targetBlocks(), plan.blockDurationSeconds(),
                p2.materials(), unused, outputs, p2.totalCurrentVolume(),
                p2.totalTargetVolume(), p2.totalAdditionalVolume(), p3);
    }

    private List<InventoryBalancePlan.UnusedItem> unusedInventory(
            InventorySnapshot inventory,
            Map<Long, Long> p2Requirements,
            Map<Long, Long> p3Requirements,
            LongToIntFunction tierOf,
            java.util.function.LongFunction<PiCommodity> commodity) {
        List<InventoryBalancePlan.UnusedItem> unused = new ArrayList<>();
        for (Map.Entry<Long, Long> e : inventory.quantities().entrySet()) {
            if (p2Requirements.containsKey(e.getKey()) || p3Requirements.containsKey(e.getKey())) {
                continue;
            }
            unused.add(new InventoryBalancePlan.UnusedItem(
                    commodity.apply(e.getKey()), e.getValue(), tierOf.applyAsInt(e.getKey())));
        }
        unused.sort(Comparator.comparing(u -> u.commodity().name(), String.CASE_INSENSITIVE_ORDER));
        return unused;
    }

    private InventoryBalancePlan.TierBalance calculateTier(
            int tier,
            Map<Long, Long> requirements,
            long blockDurationSeconds,
            InventorySnapshot inventory,
            java.util.function.LongFunction<PiCommodity> commodity,
            long p4RecipeCyclesPerBlock,
            long p4UnitsPerBlock) {

        long targetBlocks = 0;
        for (Map.Entry<Long, Long> e : requirements.entrySet()) {
            long perBlock = e.getValue();
            long current = inventory.quantityOf(e.getKey());
            long needed = current / perBlock + (current % perBlock == 0 ? 0 : 1);  // ceil
            if (needed > targetBlocks) {
                targetBlocks = needed;
            }
        }

        List<InventoryBalanceMaterial> materials = new ArrayList<>();
        BigDecimal totalCurrent = BigDecimal.ZERO;
        BigDecimal totalTarget = BigDecimal.ZERO;
        BigDecimal totalAdd = BigDecimal.ZERO;
        for (Map.Entry<Long, Long> e : requirements.entrySet()) {
            PiCommodity c = commodity.apply(e.getKey());
            long current = inventory.quantityOf(e.getKey());
            long target = Math.multiplyExact(targetBlocks, e.getValue());
            long add = target - current;
            InventoryBalanceMaterial m = new InventoryBalanceMaterial(
                    c, e.getValue(), current, target, add);
            materials.add(m);
            totalCurrent = totalCurrent.add(m.currentVolume());
            totalTarget = totalTarget.add(m.targetVolume());
            totalAdd = totalAdd.add(m.addVolume());
        }
        materials.sort(Comparator.comparing(m -> m.commodity().name(), String.CASE_INSENSITIVE_ORDER));

        return new InventoryBalancePlan.TierBalance(tier, targetBlocks,
                blockDurationSeconds, materials, totalCurrent, totalTarget, totalAdd,
                p4RecipeCyclesPerBlock, p4UnitsPerBlock);
    }
}
