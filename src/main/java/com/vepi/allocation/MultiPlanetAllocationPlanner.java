package com.vepi.allocation;

import com.vepi.domain.PiCommodity;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.load.LoadException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Deterministic multi-planet allocator for <b>P2-only sustainable
 * production</b>: distributes a shared, finite <b>P2</b> inventory across
 * planets with different templates and capacities so that every planet
 * receives whole {@link SustainableProductionPlan} blocks and the planets'
 * continuous runtimes come out as balanced as the constraints allow.
 *
 * <p>Constraints (all hard, all checked on every single block):
 * <ol>
 *   <li><b>Per-planet capacity</b>: accumulated external-material volume of a
 *       planet never exceeds its declared capacity (exact BigDecimal math).</li>
 *   <li><b>Global P2 inventory</b>: the sum of every planet's allocation of a
 *       <b>tier-2</b> external input never exceeds the user's real stock.
 *       Higher-tier external inputs (e.g. a P3 the template cannot produce
 *       itself) are part of the haul list but do <b>not</b> consume inventory —
 *       P3 stock must never block a planet from producing.</li>
 * </ol>
 *
 * <p>Fairness strategy (deliberately simple and predictable): greedy
 * block-by-block. Repeatedly pick the planet with the <b>shortest allocated
 * runtime</b> (ties broken by insertion order) and try to add exactly one more
 * sustainable block. If either constraint blocks the addition, that planet is
 * marked finished. The loop ends when every planet is finished. Because
 * fairness is measured in {@code runtimeSeconds}, templates with different
 * block durations are still treated equitably.
 */
public final class MultiPlanetAllocationPlanner {

    /** Defensive bound against pathological inputs (defensive only; real runs are small). */
    private static final long MAX_TOTAL_BLOCKS = 2_000_000L;

    /** The PI tier whose external requirements consume shared inventory (P2-only mode). */
    private static final int INVENTORY_CONSTRAINED_TIER = 2;

    /**
     * @param planets           one request per planet, in insertion order (the order
     *                          is the deterministic tie-breaker)
     * @param inventory         the user's real inventory — global shared constraint
     *                          (only its P2 part participates in P2-only mode)
     * @param commodityResolver typeID -> commodity (typically the SDE)
     * @param tierResolver      typeID -> PI tier (2 for P2, 3 for P3, ...; typically
     *                          {@link com.vepi.sde.PiTierResolver}); may return
     *                          null/0 when tiers are unknown
     * @return the complete allocation plan
     * @throws LoadException     if a planet's template requires no external inputs
     * @throws IllegalStateException if the defensive block bound is exceeded
     */
    public AllocationPlan plan(List<PlanetRequest> planets,
                               InventorySnapshot inventory,
                               Function<Long, PiCommodity> commodityResolver,
                               Function<Long, Integer> tierResolver) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(commodityResolver, "commodityResolver");
        Objects.requireNonNull(tierResolver, "tierResolver");
        List<PlanetRequest> requests = planets == null ? List.of() : List.copyOf(planets);

        int n = requests.size();

        // ---- Per-planet precomputation: external requirements, block volume, outputs.
        List<Map<Long, Long>> externalPerPlanet = new ArrayList<>(n);
        List<Map<Long, PiCommodity>> commodities = new ArrayList<>(n);
        List<Map<Long, Boolean>> inventoryConstrained = new ArrayList<>(n);
        List<BigDecimal> blockVolume = new ArrayList<>(n);
        for (PlanetRequest req : requests) {
            SustainableProductionPlan plan = req.plan();
            Map<Long, Long> external = plan.externalRequirementsPerBlock();
            if (external.isEmpty()) {
                throw new LoadException("planet '" + req.name() + "': template requires no "
                        + "external inputs — runtime is unbounded and no allocation is needed");
            }
            Map<Long, PiCommodity> resolved = new LinkedHashMap<>();
            Map<Long, Boolean> constrained = new LinkedHashMap<>();
            BigDecimal volume = BigDecimal.ZERO;
            for (Map.Entry<Long, Long> e : external.entrySet()) {
                PiCommodity c = commodityResolver.apply(e.getKey());
                resolved.put(e.getKey(), c);
                // P2-only mode: only tier-2 externals consume shared inventory.
                constrained.put(e.getKey(), tierOf(tierResolver, e.getKey()) == INVENTORY_CONSTRAINED_TIER);
                volume = volume.add(c.volume().multiply(BigDecimal.valueOf(e.getValue())));
            }
            externalPerPlanet.add(external);
            commodities.add(resolved);
            inventoryConstrained.add(constrained);
            blockVolume.add(volume);
        }

        // ---- Greedy fair-runtime allocation, one whole block at a time.
        Map<Long, Long> remainingInventory = new HashMap<>(inventory.quantities());
        long[] blocks = new long[n];
        long[] runtime = new long[n];
        BigDecimal[] used = new BigDecimal[n];
        boolean[] done = new boolean[n];
        java.util.Arrays.fill(used, BigDecimal.ZERO);

        long totalBlocks = 0;
        int active = n;
        while (active > 0) {
            // Pick the active planet with the shortest runtime; ties -> insertion order.
            int pick = -1;
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                if (pick < 0 || runtime[i] < runtime[pick]) pick = i;
            }

            if (canAddBlock(pick, requests, externalPerPlanet, inventoryConstrained,
                    blockVolume, used, remainingInventory)) {
                applyBlock(pick, requests, externalPerPlanet, inventoryConstrained,
                        blockVolume, used, blocks, runtime, remainingInventory);
                if (++totalBlocks > MAX_TOTAL_BLOCKS) {
                    throw new IllegalStateException(
                            "allocation exceeded defensive bound of " + MAX_TOTAL_BLOCKS
                                    + " total blocks — inputs look pathological");
                }
            } else {
                done[pick] = true;
                active--;
            }
        }

        // ---- Assemble per-planet results.
        List<PlanetAllocation> allocations = new ArrayList<>(n);
        Map<Long, long[]> inventoryTotals = new TreeMap<>();   // typeId -> [original, allocated]
        for (Map.Entry<Long, Long> e : inventory.quantities().entrySet()) {
            inventoryTotals.put(e.getKey(), new long[]{e.getValue(), 0L});
        }

        for (int i = 0; i < n; i++) {
            PlanetRequest req = requests.get(i);
            SustainableProductionPlan plan = req.plan();
            long planetRuntime = runtime[i];

            List<MaterialAllocation> materials = new ArrayList<>();
            for (Map.Entry<Long, Long> e : externalPerPlanet.get(i).entrySet()) {
                PiCommodity c = commodities.get(i).get(e.getKey());
                long quantity = Math.multiplyExact(blocks[i], e.getValue());
                BigDecimal volume = c.volume().multiply(BigDecimal.valueOf(quantity));
                materials.add(new MaterialAllocation(c, tierOf(tierResolver, e.getKey()),
                        quantity, volume));

                if (inventoryConstrained.get(i).get(e.getKey())) {
                    long[] totals = inventoryTotals.computeIfAbsent(e.getKey(),
                            k -> new long[]{0L, 0L});
                    totals[1] = Math.addExact(totals[1], quantity);
                }
            }

            List<ExpectedOutput> outputs = new ArrayList<>();
            for (Map.Entry<Long, Long> e : plan.finalOutputsPerBlock().entrySet()) {
                PiCommodity c = commodityResolver.apply(e.getKey());
                outputs.add(new ExpectedOutput(c, Math.multiplyExact(blocks[i], e.getValue())));
            }

            allocations.add(new PlanetAllocation(req, blocks[i], planetRuntime, used[i],
                    req.capacity().subtract(used[i]), List.copyOf(materials), List.copyOf(outputs)));
        }

        // ---- Global inventory usage report (every stock item, even unused ones).
        // Non-P2 stock (e.g. existing P3) is displayed but never consumed in
        // P2-only mode — its allocation stays 0.
        List<InventoryItemUsage> usage = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : inventoryTotals.entrySet()) {
            PiCommodity c = commodityResolver.apply(e.getKey());
            long original = e.getValue()[0];
            long allocated = e.getValue()[1];
            usage.add(new InventoryItemUsage(c, tierOf(tierResolver, e.getKey()),
                    original, allocated, Math.subtractExact(original, allocated)));
        }

        return new AllocationPlan(allocations, usage);
    }

    private static int tierOf(Function<Long, Integer> tierResolver, long typeId) {
        Integer tier = tierResolver.apply(typeId);
        return tier == null ? 0 : tier;
    }

    private boolean canAddBlock(int i,
                                List<PlanetRequest> requests,
                                List<Map<Long, Long>> externalPerPlanet,
                                List<Map<Long, Boolean>> inventoryConstrained,
                                List<BigDecimal> blockVolume,
                                BigDecimal[] used,
                                Map<Long, Long> remainingInventory) {
        // Capacity check (exact).
        BigDecimal next = used[i].add(blockVolume.get(i));
        if (next.compareTo(requests.get(i).capacity()) > 0) return false;
        // Global P2 inventory check (tier-2 externals only).
        for (Map.Entry<Long, Long> e : externalPerPlanet.get(i).entrySet()) {
            if (!inventoryConstrained.get(i).get(e.getKey())) continue;
            long have = remainingInventory.getOrDefault(e.getKey(), 0L);
            if (have < e.getValue()) return false;
        }
        return true;
    }

    private void applyBlock(int i,
                            List<PlanetRequest> requests,
                            List<Map<Long, Long>> externalPerPlanet,
                            List<Map<Long, Boolean>> inventoryConstrained,
                            List<BigDecimal> blockVolume,
                            BigDecimal[] used,
                            long[] blocks,
                            long[] runtime,
                            Map<Long, Long> remainingInventory) {
        used[i] = used[i].add(blockVolume.get(i));
        blocks[i]++;
        runtime[i] = Math.addExact(runtime[i], requests.get(i).plan().blockDurationSeconds());
        for (Map.Entry<Long, Long> e : externalPerPlanet.get(i).entrySet()) {
            if (!inventoryConstrained.get(i).get(e.getKey())) continue;
            remainingInventory.merge(e.getKey(), -e.getValue(), Long::sum);
        }
    }
}
