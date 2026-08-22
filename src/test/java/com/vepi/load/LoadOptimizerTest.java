package com.vepi.load;

import com.vepi.balance.ProductionBlock;
import com.vepi.domain.PiCommodity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Load optimizer tests against synthetic production blocks — no SDE needed.
 * Covers the Phase 1 spec cases A–G plus capacity validation.
 *
 * <p>Type IDs: A=100, B=101, C=102, X=200, Y=300, P3s=2348/2366/9846, IRD=2868.
 */
class LoadOptimizerTest {

    private final LoadOptimizer optimizer = new LoadOptimizer();

    private static PiCommodity commodity(long typeId, String name, String volume) {
        return new PiCommodity(typeId, name, new BigDecimal(volume));
    }

    private static Function<Long, PiCommodity> resolver(PiCommodity... commodities) {
        Map<Long, PiCommodity> map = java.util.Arrays.stream(commodities)
                .collect(Collectors.toMap(PiCommodity::typeId, c -> c));
        return map::get;
    }

    /** IRD-shaped block: 3x48 P3 inputs (3 m3 each), 8 IRD output, base 3600s. */
    private static ProductionBlock irdBlock() {
        return new ProductionBlock(3600,
                Map.of(2868L, 8L),
                Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L));
    }

    // ------------------------------------------------------------- A: exact fit

    @Test
    void A_exactFitCapacity_tenBlocks_remainingZero() {
        // Block: A x2 (1.5 m3) + B x7 (1 m3) => blockVolume = 10 m3
        ProductionBlock block = new ProductionBlock(3600,
                Map.of(300L, 1L),
                Map.of(100L, 2L, 101L, 7L));
        Function<Long, PiCommodity> resolver = resolver(
                commodity(100L, "A", "1.5"),
                commodity(101L, "B", "1"),
                commodity(300L, "C", "2"));

        RecommendedLoadPlan plan = optimizer.optimize(block, new BigDecimal("100"), resolver);

        assertEquals(10L, plan.blockCount(), "100 / 10 = exactly 10 blocks");
        assertEquals(0, plan.remainingCapacity().compareTo(BigDecimal.ZERO), "remaining = 0");
        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("100")));

        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(20L, byType.get(100L).quantity(), "A: 2 x 10");
        assertEquals(0, byType.get(100L).volume().compareTo(new BigDecimal("30")));
        assertEquals(70L, byType.get(101L).quantity(), "B: 7 x 10");
        assertEquals(0, byType.get(101L).volume().compareTo(new BigDecimal("70")));
        assertEquals(36000L, plan.runtimeSeconds());
        assertEquals(10L, plan.expectedOutputs().get(0).quantity(), "C: 1 x 10");
    }

    // ----------------------------------------------- B: just under next block

    @Test
    void B_capacity863_with432Block_yieldsExactlyOneBlock() {
        Function<Long, PiCommodity> resolver = resolver(
                commodity(2348L, "Gel-Matrix Biopaste", "3"),
                commodity(2366L, "Hazmat Detection Systems", "3"),
                commodity(9846L, "Planetary Vehicles", "3"),
                commodity(2868L, "Integrity Response Drones", "50"));

        // 863 / 432 = 1.997... — must floor to 1, never 2 (no float drift).
        RecommendedLoadPlan p1 = optimizer.optimize(irdBlock(), new BigDecimal("863"), resolver);
        assertEquals(1L, p1.blockCount());
        assertEquals(0, p1.remainingCapacity().compareTo(new BigDecimal("431")));

        // 864 = 2 x 432 exactly — must be 2 blocks, remaining 0.
        RecommendedLoadPlan p2 = optimizer.optimize(irdBlock(), new BigDecimal("864"), resolver);
        assertEquals(2L, p2.blockCount());
        assertEquals(0, p2.remainingCapacity().compareTo(BigDecimal.ZERO));
    }

    // ---------------------------------------------------------- C: zero capacity

    @Test
    void C_zeroCapacity_allZeroIsLegal() {
        Function<Long, PiCommodity> resolver = resolver(
                commodity(2348L, "G", "3"), commodity(2366L, "H", "3"),
                commodity(9846L, "P", "3"), commodity(2868L, "IRD", "50"));

        RecommendedLoadPlan plan = optimizer.optimize(irdBlock(), BigDecimal.ZERO, resolver);

        assertEquals(0L, plan.blockCount());
        assertEquals(0L, plan.runtimeSeconds());
        assertEquals(0, plan.usedCapacity().compareTo(BigDecimal.ZERO));
        assertEquals(0, plan.remainingCapacity().compareTo(BigDecimal.ZERO));
        assertEquals(3, plan.materials().size());
        plan.materials().forEach(m -> assertEquals(0L, m.quantity(), "no material loaded"));
        plan.expectedOutputs().forEach(o -> assertEquals(0L, o.quantity()));
    }

    // --------------------------------------- D: capacity smaller than one block

    @Test
    void D_capacitySmallerThanOneBlock_zeroBlocks_zeroMaterials() {
        Function<Long, PiCommodity> resolver = resolver(
                commodity(2348L, "G", "3"), commodity(2366L, "H", "3"),
                commodity(9846L, "P", "3"), commodity(2868L, "IRD", "50"));

        RecommendedLoadPlan plan = optimizer.optimize(irdBlock(), new BigDecimal("100"), resolver);

        assertEquals(0L, plan.blockCount(), "100 m3 < 432 m3 block — 0 full blocks");
        assertEquals(0L, plan.runtimeSeconds());
        plan.materials().forEach(m -> assertEquals(0L, m.quantity(),
                "no partial-load suggestions"));
        assertEquals(0, plan.remainingCapacity().compareTo(new BigDecimal("100")));
    }

    // -------------------------------------------------- E: different volumes

    @Test
    void E_differentMaterialVolumes_spaceSplitByVolumeNotUnitCount() {
        // A x4 (1.5 m3) + B x2 (7 m3) => blockVolume = 6 + 14 = 20 m3
        ProductionBlock block = new ProductionBlock(3600,
                Map.of(300L, 1L),
                Map.of(100L, 4L, 101L, 2L));
        Function<Long, PiCommodity> resolver = resolver(
                commodity(100L, "A", "1.5"),
                commodity(101L, "B", "7"),
                commodity(300L, "C", "3"));

        // 39 m3 -> 1 block (20 m3), remaining 19 — NOT 2 blocks (which need 40).
        RecommendedLoadPlan p1 = optimizer.optimize(block, new BigDecimal("39"), resolver);
        assertEquals(1L, p1.blockCount());
        assertEquals(0, p1.usedCapacity().compareTo(new BigDecimal("20")));
        assertEquals(0, p1.remainingCapacity().compareTo(new BigDecimal("19")));

        // 59 m3 -> 2 blocks (40 m3), A=8 (12 m3), B=4 (28 m3).
        RecommendedLoadPlan p2 = optimizer.optimize(block, new BigDecimal("59"), resolver);
        assertEquals(2L, p2.blockCount());
        Map<Long, RecommendedMaterialLoad> byType = p2.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(8L, byType.get(100L).quantity());
        assertEquals(0, byType.get(100L).volume().compareTo(new BigDecimal("12")));
        assertEquals(4L, byType.get(101L).quantity());
        assertEquals(0, byType.get(101L).volume().compareTo(new BigDecimal("28")));
        assertEquals(0, p2.remainingCapacity().compareTo(new BigDecimal("19")));
    }

    // ------------------------------------------------ F: unequal recipe ratios

    @Test
    void F_unequalRatios_quantitiesStayStrictlyBalanced() {
        // A x2, B x7, C x1, all 1 m3 => blockVolume 10
        ProductionBlock block = new ProductionBlock(3600,
                Map.of(300L, 1L),
                Map.of(100L, 2L, 101L, 7L, 102L, 1L));
        Function<Long, PiCommodity> resolver = resolver(
                commodity(100L, "A", "1"), commodity(101L, "B", "1"),
                commodity(102L, "C", "1"), commodity(300L, "Y", "1"));

        RecommendedLoadPlan plan = optimizer.optimize(block, new BigDecimal("30"), resolver);

        assertEquals(3L, plan.blockCount());
        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(6L, byType.get(100L).quantity(), "A: 2 x 3");
        assertEquals(21L, byType.get(101L).quantity(), "B: 7 x 3");
        assertEquals(3L, byType.get(102L).quantity(), "C: 1 x 3");
        // Every material supports the same runtime — the plan is balanced.
        plan.materials().forEach(m -> assertEquals(3L * 3600L, m.supportedRuntimeSeconds()));
        assertEquals(3L, plan.expectedOutputs().get(0).quantity());
    }

    // ------------------------------------------------------ G: 1800s cycle

    @Test
    void G_halfHourCycle_runtimeUsesBlocksNotFlooredHours() {
        ProductionBlock block = new ProductionBlock(1800,
                Map.of(300L, 1L),
                Map.of(100L, 1L));
        Function<Long, PiCommodity> resolver = resolver(
                commodity(100L, "A", "1"), commodity(300L, "Y", "1"));

        RecommendedLoadPlan plan = optimizer.optimize(block, new BigDecimal("5"), resolver);

        assertEquals(5L, plan.blockCount());
        assertEquals(9000L, plan.runtimeSeconds(), "5 blocks x 1800s = 9000s (2.5h)");
        assertEquals(5L, plan.materials().get(0).quantity());
        assertEquals("2h 30m (9000 s)", RecommendedLoadPlan.formatRuntime(plan.runtimeSeconds()));
    }

    // ------------------------------------------- multi-stage plan (deficit case)

    @Test
    void multiStage_deficitPlan_importsIntermediateToo() {
        // produced: X x16, Y x5 | consumed: A x40, X x20, B x20
        // external: A x40, B x20, X x4  =>  blockVolume = 40x0.01 + 20x6 + 4x6 = 144.4 m3
        ProductionBlock block = new ProductionBlock(3600,
                Map.of(200L, 16L, 300L, 5L),
                Map.of(100L, 40L, 200L, 20L, 101L, 20L));
        Function<Long, PiCommodity> resolver = resolver(
                commodity(100L, "A", "0.01"),
                commodity(101L, "B", "6"),
                commodity(200L, "X", "6"),
                commodity(300L, "Y", "6"));

        // 288.8 = exactly 2 blocks — decimal volumes must stay exact.
        RecommendedLoadPlan plan = optimizer.optimize(block, new BigDecimal("288.8"), resolver);

        assertEquals(2L, plan.blockCount());
        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(80L, byType.get(100L).quantity());
        assertEquals(40L, byType.get(101L).quantity());
        assertEquals(8L, byType.get(200L).quantity(), "X deficit 4 x 2 blocks");
        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("288.8")));
        assertEquals(0, plan.remainingCapacity().compareTo(BigDecimal.ZERO));
        assertEquals(10L, plan.expectedOutputs().stream()
                .filter(o -> o.commodity().typeId() == 300L).findFirst().orElseThrow().quantity());
    }

    // --------------------------------------------------- no external input edge

    @Test
    void noExternalRequirements_throwsClearError() {
        ProductionBlock selfSufficient = new ProductionBlock(3600,
                Map.of(200L, 10L), Map.of(200L, 10L));   // X fully balanced, nothing else
        Function<Long, PiCommodity> resolver = resolver(commodity(200L, "X", "1"));

        LoadException ex = assertThrows(LoadException.class,
                () -> optimizer.optimize(selfSufficient, new BigDecimal("1000"), resolver));
        assertEquals(true, ex.getMessage().contains("no external inputs"));
    }

    // ---------------------------------------------------- capacity validation

    @Test
    void negativeCapacity_throwsInvalidCapacity() {
        assertThrows(LoadException.InvalidCapacity.class,
                () -> optimizer.optimize(irdBlock(), new BigDecimal("-1"),
                        resolver(commodity(2348L, "G", "3"))));
        assertThrows(LoadException.InvalidCapacity.class,
                () -> optimizer.optimize(irdBlock(), null,
                        resolver(commodity(2348L, "G", "3"))));
    }

    @Test
    void parseCapacity_rejectsInvalidInput() {
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity(null));
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity(""));
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity("   "));
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity("abc"));
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity("NaN"));
        assertThrows(LoadException.InvalidCapacity.class, () -> LoadOptimizer.parseCapacity("-5"));
        assertThrows(LoadException.InvalidCapacity.class,
                () -> LoadOptimizer.parseCapacity("1e400"));
    }

    @Test
    void parseCapacity_acceptsLegalValues() {
        assertEquals(0, LoadOptimizer.parseCapacity("0").compareTo(BigDecimal.ZERO));
        assertEquals(0, LoadOptimizer.parseCapacity("20000").compareTo(new BigDecimal("20000")));
        assertEquals(0, LoadOptimizer.parseCapacity(" 123.5 ")
                .compareTo(new BigDecimal("123.5")));
    }
}
