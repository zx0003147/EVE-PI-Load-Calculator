package com.vepi.allocation;

import com.vepi.balance.ProductionBlock;
import com.vepi.domain.PiCommodity;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.load.LoadException;
import com.vepi.load.LoadOptimizer;
import com.vepi.load.RecommendedLoadPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-planet allocation planner tests with synthetic sustainable plans
 * (no SDE needed). Rewritten for <b>P2-only mode</b>: only tier-2 external
 * inputs consume shared inventory; higher-tier externals (a P3 the template
 * cannot produce itself) are hauled but never block production and never
 * consume stock.
 *
 * <p>Type IDs: A=100, B=101, C=102 (P2, 1 or 3 m3 as noted); X=200 (P3); Y=300.
 */
class MultiPlanetAllocationPlannerTest {

    private final MultiPlanetAllocationPlanner planner = new MultiPlanetAllocationPlanner();

    // ------------------------------------------------------------- helpers

    private static PiCommodity commodity(long typeId, String name, String volume) {
        return new PiCommodity(typeId, name, new BigDecimal(volume));
    }

    private static Function<Long, PiCommodity> resolver(PiCommodity... commodities) {
        Map<Long, PiCommodity> map = java.util.Arrays.stream(commodities)
                .collect(Collectors.toMap(PiCommodity::typeId, c -> c));
        return map::get;
    }

    private static final Function<Long, Integer> TIERS = typeId -> {
        if (typeId == 100L || typeId == 101L || typeId == 102L) return 2;
        if (typeId == 200L) return 3;
        return 4;
    };

    private static InventorySnapshot inventory(long typeId, long quantity) {
        return new InventorySnapshot(Map.of(typeId, quantity));
    }

    private static SustainableProductionPlan plan(long blockDuration,
                                                  Map<Long, Long> externalPerBlock,
                                                  Map<Long, Long> outputsPerBlock) {
        return SustainableProductionPlan.simple(blockDuration, externalPerBlock, outputsPerBlock);
    }

    /** IRD-shaped plan: 3x48 P3 inputs at 3 m3 each -> blockVolume 432 m3, 3600s. */
    private static SustainableProductionPlan irdShapedPlan() {
        return plan(3600, Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L), Map.of(2868L, 8L));
    }

    private static final Function<Long, PiCommodity> IRD_RESOLVER = resolver(
            commodity(2348L, "Gel-Matrix Biopaste", "3"),
            commodity(2366L, "Hazmat Detection Systems", "3"),
            commodity(9846L, "Planetary Vehicles", "3"),
            commodity(2868L, "Integrity Response Drones", "50"));

    // ---------------------------------------- single planet == Phase 1 optimizer

    @Test
    void singlePlanet_withPlentyInventory_matchesLoadOptimizer() {
        // The equivalent legacy ProductionBlock drives the Phase 1 optimizer
        // for comparison; the planner now consumes the sustainable plan.
        ProductionBlock legacyBlock = new ProductionBlock(3600,
                Map.of(2868L, 8L),
                Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L));
        BigDecimal capacity = new BigDecimal("20000");
        InventorySnapshot plenty = new InventorySnapshot(Map.of(
                2348L, 1_000_000L, 2366L, 1_000_000L, 9846L, 1_000_000L));

        RecommendedLoadPlan phase1 = new LoadOptimizer().optimize(legacyBlock, capacity, IRD_RESOLVER);
        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", irdShapedPlan(), capacity)), plenty, IRD_RESOLVER, TIERS);

        PlanetAllocation p1 = plan.planets().get(0);
        assertEquals(phase1.blockCount(), p1.blockCount(), "same blocks as Phase 1 (46)");
        assertEquals(phase1.runtimeSeconds(), p1.runtimeSeconds());
        assertEquals(0, p1.usedCapacity().compareTo(phase1.usedCapacity()));
        assertEquals(0, p1.remainingCapacity().compareTo(phase1.remainingCapacity()));
        assertEquals(phase1.materials().stream()
                        .collect(Collectors.toMap(m -> m.typeId(), m -> m.quantity())),
                p1.quantitiesByTypeId());
        assertEquals(2208L, p1.quantitiesByTypeId().get(2348L), "48 x 46");
    }

    // ------------------------------------------------------- plenty inventory

    @Test
    void plentyInventory_allocationLimitedOnlyByCapacity() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("25"))),
                inventory(100L, 1_000_000L), res, TIERS);

        assertEquals(2L, plan.planets().get(0).blockCount(), "25 / 10 = 2 blocks");
        assertEquals(20L, plan.planets().get(0).quantitiesByTypeId().get(100L));
        assertEquals(0, plan.planets().get(0).remainingCapacity()
                .compareTo(new BigDecimal("5")));
    }

    // ------------------------------------------------------ inventory bottleneck

    @Test
    void hugeCapacityButScarceP2_bottleneckedByInventory() {
        SustainableProductionPlan p = plan(3600,
                Map.of(100L, 48L, 101L, 48L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(101L, "B", "1"), commodity(300L, "Y", "1"));

        // A=500 -> at most floor(500/48) = 10 blocks; B is plentiful.
        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("1000000"))),
                new InventorySnapshot(Map.of(100L, 500L, 101L, 100_000L)), res, TIERS);

        assertEquals(10L, plan.planets().get(0).blockCount());
        assertEquals(480L, plan.planets().get(0).quantitiesByTypeId().get(100L));
        assertEquals(480L, plan.planets().get(0).quantitiesByTypeId().get(101L));
    }

    // --------------------------------------------------------- shared inventory

    @Test
    void sharedInventory_neverExceedsGlobalStock() {
        // Both planets consume 40x A per block; stock 1000 -> 25 blocks total.
        SustainableProductionPlan p = plan(3600, Map.of(100L, 40L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(List.of(
                new PlanetRequest("P1", p, new BigDecimal("100000")),
                new PlanetRequest("P2", p, new BigDecimal("100000"))),
                inventory(100L, 1000L), res, TIERS);

        long p1 = plan.planets().get(0).quantitiesByTypeId().get(100L);
        long p2 = plan.planets().get(1).quantitiesByTypeId().get(100L);
        assertTrue(p1 + p2 <= 1000L, "global constraint: " + p1 + " + " + p2 + " <= 1000");
        assertEquals(1000L, p1 + p2, "fully used — no greedy starving");
        assertEquals(13L, plan.planets().get(0).blockCount(), "fair split 13/12");
        assertEquals(12L, plan.planets().get(1).blockCount());
    }

    @Test
    void identicalPlanets_fairBlockSplit() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        // Stock 200 -> 20 blocks total -> exactly 10 + 10 for identical planets.
        AllocationPlan plan = planner.plan(List.of(
                new PlanetRequest("P1", p, new BigDecimal("100000")),
                new PlanetRequest("P2", p, new BigDecimal("100000"))),
                inventory(100L, 200L), res, TIERS);

        assertEquals(10L, plan.planets().get(0).blockCount());
        assertEquals(10L, plan.planets().get(1).blockCount());
        assertEquals(100L, plan.planets().get(0).quantitiesByTypeId().get(100L));
        assertEquals(100L, plan.planets().get(1).quantitiesByTypeId().get(100L));
    }

    // ---------------------------------------------------- runtime fairness

    @Test
    void differentBlockDurations_fairnessMeasuredInRuntimeNotBlocks() {
        // P1: 1800s block consuming 1x A. P2: 3600s block consuming 2x A. Stock 30.
        SustainableProductionPlan halfHour = plan(1800, Map.of(100L, 1L), Map.of(300L, 1L));
        SustainableProductionPlan fullHour = plan(3600, Map.of(100L, 2L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(List.of(
                new PlanetRequest("P1", halfHour, new BigDecimal("1000")),
                new PlanetRequest("P2", fullHour, new BigDecimal("1000"))),
                inventory(100L, 30L), res, TIERS);

        PlanetAllocation p1 = plan.planets().get(0);
        PlanetAllocation p2 = plan.planets().get(1);
        assertEquals(16L, p1.blockCount());
        assertEquals(7L, p2.blockCount());
        assertEquals(28800L, p1.runtimeSeconds());
        assertEquals(25200L, p2.runtimeSeconds());
        assertTrue(Math.abs(p1.runtimeSeconds() - p2.runtimeSeconds()) <= 3600L,
                "runtimes within one block of each other");
        assertEquals(30L, p1.quantitiesByTypeId().get(100L)
                + p2.quantitiesByTypeId().get(100L), "stock fully used");
    }

    // --------------------------- P2-only mode: tier-3+ externals never constrain

    @Test
    void tier3ExternalInput_hauledButNeverConsumesInventory() {
        // Multi-stage template whose plan still needs an external P3 (X, 18 per
        // block) the template cannot produce itself: X is on the haul list but
        // no X stock exists — production must NOT be blocked by it.
        SustainableProductionPlan p = plan(3600,
                Map.of(100L, 100L, 200L, 18L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(200L, "X", "6"), commodity(300L, "Y", "1"));

        // Block volume = 100x1 + 18x6 = 208 m3; capacity 2080 -> 10 blocks,
        // limited ONLY by capacity even though X stock is zero.
        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("2080"))),
                inventory(100L, 1_000_000L), res, TIERS);

        PlanetAllocation p1 = plan.planets().get(0);
        assertEquals(10L, p1.blockCount(), "P3 stock (absent) must not limit blocks");
        assertEquals(1000L, p1.quantitiesByTypeId().get(100L));
        assertEquals(180L, p1.quantitiesByTypeId().get(200L), "X still on the haul list");
        assertEquals(1, p1.materialsOfTier(2).size());
        assertEquals(1, p1.materialsOfTier(3).size());
    }

    @Test
    void p3StockAmounts_doNotChangeTheAllocation() {
        // Spec acceptance Case A vs Case B: identical result with and without
        // P3 stock; P3 stays untouched (allocated 0).
        SustainableProductionPlan p = plan(3600,
                Map.of(100L, 100L, 200L, 18L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(200L, "X", "6"), commodity(300L, "Y", "1"));

        AllocationPlan withoutP3 = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("1000000"))),
                inventory(100L, 10_000L), res, TIERS);
        AllocationPlan withP3 = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("1000000"))),
                new InventorySnapshot(Map.of(100L, 10_000L, 200L, 54L)), res, TIERS);

        assertEquals(withoutP3.planets().get(0).blockCount(),
                withP3.planets().get(0).blockCount(), "P3 stock changes nothing");
        assertEquals(withoutP3.planets().get(0).quantitiesByTypeId(),
                withP3.planets().get(0).quantitiesByTypeId());

        InventoryItemUsage x = withP3.inventoryUsage().stream()
                .filter(u -> u.typeId() == 200L).findFirst().orElseThrow();
        assertEquals(0L, x.allocated(), "P3 never consumed in P2-only mode");
        assertEquals(54L, x.remaining());
        assertEquals(54L, x.original());
    }

    @Test
    void scarceP3Stock_doesNotBlockProduction() {
        // X stock = 5 < 18/block: the planet still produces; the player hauls
        // the shortfall from elsewhere. P2-only mode never lets P3 stop a block.
        SustainableProductionPlan p = plan(3600,
                Map.of(100L, 100L, 200L, 18L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(200L, "X", "6"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("2080"))),
                new InventorySnapshot(Map.of(100L, 1_000_000L, 200L, 5L)), res, TIERS);

        assertTrue(plan.planets().get(0).blockCount() > 0, "P3 scarcity must not zero the planet");
        assertEquals(5L, plan.inventoryUsage().stream()
                .filter(u -> u.typeId() == 200L).findFirst().orElseThrow().remaining(),
                "scarce P3 stays untouched too");
    }

    // ------------------------------------------------------ irrelevant inventory

    @Test
    void irrelevantItems_allocatedZero_remainingUntouched() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(102L, "C", "1"), commodity(300L, "Y", "1"));

        // C=999 is stocked but consumed by no template: parsed fine, allocated 0.
        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("100"))),
                new InventorySnapshot(Map.of(100L, 500L, 102L, 999L)), res, TIERS);

        InventoryItemUsage cUsage = plan.inventoryUsage().stream()
                .filter(u -> u.typeId() == 102L).findFirst().orElseThrow();
        assertEquals(0L, cUsage.allocated());
        assertEquals(999L, cUsage.remaining());
        assertEquals(999L, cUsage.original());
    }

    // ------------------------------------------------- independent capacities

    @Test
    void perPlanetCapacitiesAreIndependent() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(List.of(
                new PlanetRequest("P1", p, new BigDecimal("20")),    // 2 blocks
                new PlanetRequest("P2", p, new BigDecimal("50"))),   // 5 blocks
                inventory(100L, 1_000_000L), res, TIERS);

        assertEquals(2L, plan.planets().get(0).blockCount());
        assertEquals(5L, plan.planets().get(1).blockCount());
        assertEquals(0, plan.planets().get(0).remainingCapacity()
                .compareTo(BigDecimal.ZERO));
    }

    // ------------------------------------- spec §17: interacting bottlenecks

    @Test
    void mixedBottlenecks_sharedItemLimitsBothPlanets() {
        // Planet A needs 100xA + 100xB per block; Planet B needs 50xA + 100xC.
        // Stock: A=10000, B=5000, C=10000. Greedy interplay:
        //  - 50 alternating rounds consume B fully (50 x 100) -> Planet A stops at 50.
        //  - Planet B continues alone on the remaining 2500x A for 50 more blocks,
        //    exactly exhausting A and C: Planet B ends at 100 blocks.
        SustainableProductionPlan blockA = plan(3600,
                Map.of(100L, 100L, 101L, 100L), Map.of(300L, 1L));
        SustainableProductionPlan blockB = plan(3600,
                Map.of(100L, 50L, 102L, 100L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(101L, "B", "1"),
                commodity(102L, "C", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(List.of(
                new PlanetRequest("Planet A", blockA, new BigDecimal("1000000")),
                new PlanetRequest("Planet B", blockB, new BigDecimal("1000000"))),
                new InventorySnapshot(Map.of(100L, 10000L, 101L, 5000L, 102L, 10000L)),
                res, TIERS);

        PlanetAllocation pa = plan.planets().get(0);
        PlanetAllocation pb = plan.planets().get(1);
        assertEquals(50L, pa.blockCount(), "B stock 5000 / 100 per block");
        assertEquals(100L, pb.blockCount(), "continues alone after A stops");
        assertEquals(5000L, pa.quantitiesByTypeId().get(100L));
        assertEquals(5000L, pa.quantitiesByTypeId().get(101L));
        assertEquals(5000L, pb.quantitiesByTypeId().get(100L));
        assertEquals(10000L, pb.quantitiesByTypeId().get(102L));
        assertEquals(10000L, pa.quantitiesByTypeId().get(100L)
                + pb.quantitiesByTypeId().get(100L), "shared A fully consumed, never exceeded");

        // Global report: every stocked item fully allocated.
        Map<Long, InventoryItemUsage> usage = plan.inventoryUsage().stream()
                .collect(Collectors.toMap(InventoryItemUsage::typeId, u -> u));
        assertEquals(0L, usage.get(100L).remaining());
        assertEquals(0L, usage.get(101L).remaining());
        assertEquals(0L, usage.get(102L).remaining());
        assertEquals(5000L, usage.get(101L).allocated());
    }

    // ----------------------------------------------------------- edge cases

    @Test
    void emptyInventory_allPlanetsGetZeroBlocks() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, new BigDecimal("100"))),
                new InventorySnapshot(Map.of()), res, TIERS);

        assertEquals(0L, plan.planets().get(0).blockCount());
        plan.planets().forEach(pl -> pl.materials().forEach(m -> assertEquals(0L, m.quantity())));
    }

    @Test
    void zeroCapacity_zeroBlocks_notAnError() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        AllocationPlan plan = planner.plan(
                List.of(new PlanetRequest("P1", p, BigDecimal.ZERO)),
                inventory(100L, 1000L), res, TIERS);

        assertEquals(0L, plan.planets().get(0).blockCount());
    }

    @Test
    void templateWithoutExternalInputs_throwsClearError() {
        // A plan with no external requirements has unbounded runtime.
        SustainableProductionPlan selfSufficient = plan(3600, Map.of(), Map.of(200L, 10L));
        Function<Long, PiCommodity> res = resolver(commodity(200L, "X", "1"));

        LoadException ex = assertThrows(LoadException.class, () -> planner.plan(
                List.of(new PlanetRequest("P1", selfSufficient, new BigDecimal("100"))),
                inventory(200L, 1000L), res, TIERS));
        assertTrue(ex.getMessage().contains("no external inputs"));
    }

    @Test
    void negativePlanetCapacity_rejected() {
        SustainableProductionPlan p = plan(3600, Map.of(100L, 10L), Map.of(300L, 1L));
        assertThrows(IllegalArgumentException.class, () ->
                new PlanetRequest("P1", p, new BigDecimal("-1")));
    }

    @Test
    void noPlanetsAtAll_emptyPlan() {
        AllocationPlan plan = planner.plan(List.of(),
                new InventorySnapshot(Map.of(100L, 5L)),
                resolver(commodity(100L, "A", "1")), TIERS);
        assertTrue(plan.planets().isEmpty());
        assertEquals(1, plan.inventoryUsage().size());
    }

    @Test
    void deterministic_sameInputsSamePlan() {
        // Same inputs run twice must produce byte-identical results (the
        // allocator is the deterministic seam the UI relies on).
        SustainableProductionPlan pa = plan(3600, Map.of(100L, 100L, 101L, 100L), Map.of(300L, 1L));
        SustainableProductionPlan pb = plan(3600, Map.of(100L, 50L, 102L, 100L), Map.of(300L, 1L));
        Function<Long, PiCommodity> res = resolver(
                commodity(100L, "A", "1"), commodity(101L, "B", "1"),
                commodity(102L, "C", "1"), commodity(300L, "Y", "1"));
        List<PlanetRequest> requests = List.of(
                new PlanetRequest("Planet A", pa, new BigDecimal("1000000")),
                new PlanetRequest("Planet B", pb, new BigDecimal("1000000")));
        InventorySnapshot stock = new InventorySnapshot(
                Map.of(100L, 10000L, 101L, 5000L, 102L, 10000L));

        AllocationPlan first = planner.plan(requests, stock, res, TIERS);
        AllocationPlan second = planner.plan(requests, stock, res, TIERS);

        assertFalse(first == second);
        assertEquals(first.planets().size(), second.planets().size());
        for (int i = 0; i < first.planets().size(); i++) {
            assertEquals(first.planets().get(i).blockCount(), second.planets().get(i).blockCount());
            assertEquals(first.planets().get(i).quantitiesByTypeId(),
                    second.planets().get(i).quantitiesByTypeId());
            assertEquals(first.planets().get(i).runtimeSeconds(),
                    second.planets().get(i).runtimeSeconds());
        }
    }
}
