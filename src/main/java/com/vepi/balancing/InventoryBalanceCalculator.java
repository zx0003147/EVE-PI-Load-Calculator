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
 * <p>Question answered: “my current P2 stock is unbalanced relative to this
 * template's consumption ratio. To make ALL of it consumable by whole
 * sustainable production blocks, which P2 items do I still need to buy — and
 * how much of each — so that every item ends up at an exact multiple of its
 * per-block requirement?”
 *
 * <p>Algorithm (spec §6–8, exact integer arithmetic throughout):
 * <ol>
 *   <li>per required P2 {@code i}: {@code blocksNeeded[i] = ceil(current[i] /
 *       requiredPerBlock[i])};</li>
 *   <li>{@code targetBlocks = max(blocksNeeded[i])} — one shared block count
 *       for the whole template (a block is indivisible);</li>
 *   <li>{@code target[i] = targetBlocks × requiredPerBlock[i]} (≥ current[i]
 *       by construction, so {@code add ≥ 0} always);</li>
 *   <li>{@code add[i] = target[i] − current[i]}.</li>
 * </ol>
 *
 * <p>Only P2 items that the template actually consumes take part. A P2 the
 * template ignores never inflates targetBlocks — it is reported in
 * {@code unusedInventory} instead. P3 stock is parsed and displayed but never
 * influences the balance (P2-only mode). Templates whose external
 * requirements contain no P2 at all (pure P3→P4) are rejected with
 * {@link BalanceException.NoP2Requirements} — the tool must not invent a
 * virtual P2 chain.
 */
public final class InventoryBalanceCalculator {

    /**
     * @param plan      the template's P2-only sustainable production plan
     *                  (source of {@code requiredPerBlock})
     * @param inventory the user's current stock (P2 and anything else)
     * @param tierOf    tier resolver (SDE) to tell P2 from other stock
     * @param commodity SDE commodity lookup (name + volume for display)
     */
    public InventoryBalancePlan calculate(SustainableProductionPlan plan,
                                          InventorySnapshot inventory,
                                          LongToIntFunction tierOf,
                                          java.util.function.LongFunction<PiCommodity> commodity) {
        // ---- 1. the P2 subset of the template's external requirements ----
        Map<Long, Long> p2Requirements = new TreeMap<>();
        for (Map.Entry<Long, Long> e : plan.externalRequirementsPerBlock().entrySet()) {
            if (tierOf.applyAsInt(e.getKey()) == 2) {
                p2Requirements.put(e.getKey(), e.getValue());
            }
        }
        if (p2Requirements.isEmpty()) {
            throw new BalanceException.NoP2Requirements();
        }

        // ---- 2. one shared target block count: max over ceil(current / perBlock) ----
        long targetBlocks = 0;
        for (Map.Entry<Long, Long> e : p2Requirements.entrySet()) {
            long perBlock = e.getValue();
            long current = inventory.quantityOf(e.getKey());
            long needed = current / perBlock + (current % perBlock == 0 ? 0 : 1);  // ceil
            if (needed > targetBlocks) {
                targetBlocks = needed;
            }
        }

        // ---- 3. material rows (exact longs; volumes exact BigDecimal) ----
        List<InventoryBalanceMaterial> materials = new ArrayList<>();
        BigDecimal totalCurrent = BigDecimal.ZERO;
        BigDecimal totalTarget = BigDecimal.ZERO;
        BigDecimal totalAdd = BigDecimal.ZERO;
        for (Map.Entry<Long, Long> e : p2Requirements.entrySet()) {
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

        // ---- 4. stock not consumed by this template ----
        List<InventoryBalancePlan.UnusedItem> unused = new ArrayList<>();
        for (Map.Entry<Long, Long> e : inventory.quantities().entrySet()) {
            if (p2Requirements.containsKey(e.getKey())) {
                continue;
            }
            unused.add(new InventoryBalancePlan.UnusedItem(
                    commodity.apply(e.getKey()), e.getValue(),
                    tierOf.applyAsInt(e.getKey())));
        }
        unused.sort(Comparator.comparing(u -> u.commodity().name(), String.CASE_INSENSITIVE_ORDER));

        // ---- 5. final outputs over all blocks ----
        List<InventoryBalancePlan.ExpectedOutput> outputs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : plan.finalOutputsPerBlock().entrySet()) {
            outputs.add(new InventoryBalancePlan.ExpectedOutput(
                    commodity.apply(e.getKey()),
                    Math.multiplyExact(e.getValue(), targetBlocks)));
        }
        outputs.sort(Comparator.comparing(o -> o.commodity().name(), String.CASE_INSENSITIVE_ORDER));

        return new InventoryBalancePlan(targetBlocks, plan.blockDurationSeconds(),
                materials, unused, outputs, totalCurrent, totalTarget, totalAdd);
    }
}
