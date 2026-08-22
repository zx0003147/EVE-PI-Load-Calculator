package com.vepi.app;

import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.InventoryItemUsage;
import com.vepi.allocation.PlanetRequest;
import com.vepi.flow.Fraction;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controller tests for the inventory-driven multi-planet model in
 * <b>P2-only sustainable production</b> mode — the same pipeline the GUI
 * drives, no Swing. Covers the acceptance scenarios:
 *
 * <ul>
 *   <li><b>A</b>: full-chain template (P2 -> P3 -> P4), P3 stock = 0, P2 plenty
 *       -> production runs, external load is exactly the 9 P2 inputs;</li>
 *   <li><b>B</b>: adding P3 stock changes nothing (P3 never consumed);</li>
 *   <li><b>C</b>: one scarce P2 -> zero blocks with that P2 as limiting item;</li>
 *   <li><b>D</b>: internal P3 shortfall (Hazmat 48/h vs 60/h) throttles the
 *       chain instead of demanding external P3.</li>
 * </ul>
 */
class PiCalculatorControllerAllocationTest {

    private static final String REAL_INVENTORY = """
            Biocells\t46080
            Mechanical Parts\t40594
            Miniature Electronics\t61830
            Oxides\t74225
            Polytextiles\t70909
            Superconductors\t51110
            Supertensile Plastics\t65939
            Transmitter\t31390
            Viral Agent\t72134
            Gel-Matrix Biopaste\t1527
            Hazmat Detection Systems\t41
            Planetary Vehicles\t688
            """;

    // Known typeIDs (verified against the SDE).
    private static final long GMB = 2348L;
    private static final long HAZMAT = 2366L;
    private static final long PV = 9846L;
    private static final long IRD = 2868L;
    // The 9 P2 inputs of the full IRD chain (verified against the SDE):
    // GMB <- Biocells/Oxides/Superconductors; Hazmat <- Polytextiles/
    // Transmitter/Viral Agent; PV <- Mechanical Parts/Miniature
    // Electronics/Supertensile Plastics.
    private static final long BIOCELLS = 2329L;
    private static final long OXIDES = 2317L;
    private static final long SUPERCONDUCTORS = 9838L;
    private static final long POLYTEXTILES = 3695L;
    private static final long TRANSMITTER = 9840L;
    private static final long VIRAL_AGENT = 3775L;
    private static final long MECH_PARTS = 3689L;
    private static final long MINI_ELECTRONICS = 9842L;
    private static final long SUPERPLASTICS = 2312L;

    private static PiCalculatorController controller;
    private static String irdJson;
    private static String fullChainJson;
    private static String hazmatBottleneckJson;

    @BeforeAll
    static void setUp() throws Exception {
        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        irdJson = java.nio.file.Files.readString(
                Path.of("data/templates/IntegrityResponseDrones.json"));
        fullChainJson = java.nio.file.Files.readString(
                Path.of("data/templates/FullChainIrd.json"));
        hazmatBottleneckJson = java.nio.file.Files.readString(
                Path.of("data/templates/IrdHazmatBottleneck.json"));
    }

    @AfterAll
    static void tearDown() {
        controller.close();
    }

    // ------------------------------------------------------- helpers

    /** 20000 of each of the 9 P2 inputs — plenty for a 3-block run. */
    private static InventorySnapshot p2Stock(long each) {
        return new InventorySnapshot(Map.of(
                BIOCELLS, each, OXIDES, each, SUPERCONDUCTORS, each,
                POLYTEXTILES, each, TRANSMITTER, each, VIRAL_AGENT, each,
                MECH_PARTS, each, MINI_ELECTRONICS, each, SUPERPLASTICS, each));
    }

    private static PiCalculatorController.PlanetTemplate fullChain() {
        return controller.loadPlanetTemplate(fullChainJson);
    }

    private static InventoryItemUsage usageOf(AllocationPlan plan, long typeId) {
        return plan.inventoryUsage().stream()
                .filter(u -> u.typeId() == typeId)
                .findFirst().orElseThrow();
    }

    // ---- A. inventory text load ----

    @Test
    void parseInventory_realText_12Items_9P2_3P3() {
        PiCalculatorController.InventoryStatus status = controller.parseInventory(REAL_INVENTORY);
        assertEquals(12, status.itemCount());
        assertEquals(9, status.p2Count());
        assertEquals(3, status.p3Count());
        assertTrue(status.warnings().isEmpty(), () -> "warnings: " + status.warnings());
        assertEquals(12, status.snapshot().quantities().size());
    }

    @Test
    void parseInventory_duplicateAndUnknownItems_warnWithoutFailing() {
        PiCalculatorController.InventoryStatus status =
                controller.parseInventory("Oxides 1000\nOxides 2000\nNot A Real Item 5");
        assertEquals(1, status.itemCount());
        assertEquals(3000L, status.snapshot().quantities().values().iterator().next(),
                "duplicate lines must be combined, never overwritten");
        assertTrue(status.warnings().stream()
                        .anyMatch(w -> w.contains("Duplicate item lines were combined.")),
                () -> "warnings: " + status.warnings());
        assertTrue(status.warnings().stream()
                        .anyMatch(w -> w.contains("Not A Real Item")),
                () -> "warnings: " + status.warnings());
    }

    // ---- B. planet template load (new sustainable-plan API) ----

    @Test
    void loadPlanetTemplate_realIrdJson_resolvesSustainablePlanWithoutLegacyState() {
        PiCalculatorController.PlanetTemplate template = controller.loadPlanetTemplate(irdJson);
        assertEquals(8, template.summary().facilityCount());
        assertTrue(template.summary().displayName().contains("Integrity Response Drones"));
        assertEquals(3600L, template.plan().blockDurationSeconds());
        assertEquals(3, template.plan().externalRequirementsPerBlock().size());
        assertEquals(8L, template.plan().finalOutputsPerBlock().get(IRD));
        // The legacy single-template state must stay untouched (old tests rely on it).
        assertFalse(controller.isTemplateLoaded());
    }

    @Test
    void loadPlanetTemplate_surroundingWhitespaceTolerated() {
        PiCalculatorController.PlanetTemplate template =
                controller.loadPlanetTemplate("\n\n  " + irdJson + "  \n");
        assertEquals(8, template.summary().facilityCount());
    }

    @Test
    void loadPlanetTemplate_fullChain_externalIsExactlyThe9P2Inputs() {
        PiCalculatorController.PlanetTemplate template = fullChain();
        SustainableProductionPlan plan = template.plan();

        // P3 is internal even though Hazmat capacity (48/h) < demand (60/h).
        assertEquals(9, plan.externalRequirementsPerBlock().size());
        assertFalse(plan.externalRequirementsPerBlock().containsKey(HAZMAT));
        assertFalse(plan.externalRequirementsPerBlock().containsKey(GMB));
        assertFalse(plan.externalRequirementsPerBlock().containsKey(PV));
        // Block: 54000s, 2400 of each P2 (1800 m3 each) = 16200 m3.
        assertEquals(54000L, plan.blockDurationSeconds());
        assertEquals(2400L, plan.externalRequirementsPerBlock().get(OXIDES));
        assertEquals(0, template.summary().blockVolumeM3().compareTo(new BigDecimal("16200")));
        assertEquals(120L, plan.finalOutputsPerBlock().get(IRD));
    }

    // ---- acceptance scenario A: P3 = 0 + P2 plenty -> production runs ----

    @Test
    void scenarioA_fullChain_noP3Stock_p2Plenty_produces() {
        PiCalculatorController.PlanetTemplate template = fullChain();
        PlanetRequest request = new PlanetRequest("Planet 1", template.plan(),
                new BigDecimal("50000"));

        AllocationPlan plan = controller.calculateAllocation(p2Stock(20000L), List.of(request));

        assertEquals(3L, plan.planets().get(0).blockCount(),
                "floor(50000 / 16200) = 3 sustainable blocks");
        assertEquals(162000L, plan.planets().get(0).runtimeSeconds(), "3 x 54000s");
        assertEquals(0, plan.planets().get(0).usedCapacity().compareTo(new BigDecimal("48600")));
        assertEquals(0, plan.planets().get(0).remainingCapacity().compareTo(new BigDecimal("1400")));
        assertEquals(360L, plan.planets().get(0).expectedOutputs().get(0).quantity(),
                "3 x 120 IRD");
        for (long p2 : List.of(BIOCELLS, OXIDES, SUPERCONDUCTORS, POLYTEXTILES, TRANSMITTER,
                VIRAL_AGENT, MECH_PARTS, MINI_ELECTRONICS, SUPERPLASTICS)) {
            assertEquals(7200L, plan.planets().get(0).quantitiesByTypeId().get(p2),
                    "3 x 2400 per P2");
            assertEquals(12800L, usageOf(plan, p2).remaining());
        }
    }

    // ---- acceptance scenario B: P3 stock changes nothing ----

    @Test
    void scenarioB_p3Stock_doesNotChangeTheP2OnlyResult() {
        PiCalculatorController.PlanetTemplate template = fullChain();
        PlanetRequest request = new PlanetRequest("Planet 1", template.plan(),
                new BigDecimal("50000"));

        InventorySnapshot withoutP3 = p2Stock(20000L);
        Map<Long, Long> withP3 = new TreeMap<>(p2Stock(20000L).quantities());
        withP3.put(GMB, 1527L);
        withP3.put(HAZMAT, 41L);
        withP3.put(PV, 688L);

        AllocationPlan planA = controller.calculateAllocation(withoutP3, List.of(request));
        AllocationPlan planB = controller.calculateAllocation(
                new InventorySnapshot(withP3), List.of(request));

        assertEquals(planA.planets().get(0).blockCount(), planB.planets().get(0).blockCount());
        assertEquals(planA.planets().get(0).quantitiesByTypeId(),
                planB.planets().get(0).quantitiesByTypeId());

        // The P3 stock is displayed but never consumed — the REAL inventory's
        // "Hazmat 41" can no longer zero this planet.
        assertEquals(0L, usageOf(planB, GMB).allocated());
        assertEquals(1527L, usageOf(planB, GMB).remaining());
        assertEquals(0L, usageOf(planB, HAZMAT).allocated());
        assertEquals(41L, usageOf(planB, HAZMAT).remaining());
        assertEquals(0L, usageOf(planB, PV).allocated());
        assertEquals(688L, usageOf(planB, PV).remaining());
    }

    // ---- acceptance scenario C: one scarce P2 is the limiting item ----

    @Test
    void scenarioC_scarceP2_zeroBlocksWithThatP2AsLimiting() {
        PiCalculatorController.PlanetTemplate template = fullChain();
        PlanetRequest request = new PlanetRequest("Planet 1", template.plan(),
                new BigDecimal("50000"));

        Map<Long, Long> stock = new TreeMap<>(p2Stock(20000L).quantities());
        stock.put(OXIDES, 1000L);   // < 2400 per block

        AllocationPlan plan = controller.calculateAllocation(
                new InventorySnapshot(stock), List.of(request));
        assertEquals(0L, plan.planets().get(0).blockCount());

        PiCalculatorController.ZeroBlockExplanation explanation =
                controller.explainZeroBlocks(request, plan);
        assertFalse(explanation.capacityTooSmall());
        assertEquals(1, explanation.limitingItems().size(),
                () -> "limiting: " + explanation.limitingItems());
        PiCalculatorController.ZeroBlockExplanation.LimitingItem item =
                explanation.limitingItems().get(0);
        assertEquals("Oxides", item.commodity().name());
        assertEquals(1000L, item.available());
        assertEquals(2400L, item.requiredPerBlock());
    }

    // ---- acceptance scenario D: internal shortfall throttles, never imports P3 ----

    @Test
    void scenarioD_internalP3Shortfall_throttlesInsteadOfExternalP3() {
        // IrdHazmatBottleneck: 10 IRD + 16 Hazmat factories (48/h < 60/h demand).
        // Zero P3 stock anywhere; only the 3 Hazmat P2 inputs are stocked.
        PiCalculatorController.PlanetTemplate template =
                controller.loadPlanetTemplate(hazmatBottleneckJson);
        assertFalse(template.plan().externalRequirementsPerBlock().containsKey(HAZMAT),
                "the shortfall must be internal (throttle), never an external P3 demand");
        assertEquals(Fraction.of(4, 5), template.plan().bottlenecks().get(0).throttle());

        InventorySnapshot stock = new InventorySnapshot(Map.of(
                POLYTEXTILES, 10000L, TRANSMITTER, 10000L, VIRAL_AGENT, 10000L));
        PlanetRequest request = new PlanetRequest("Planet 1", template.plan(),
                new BigDecimal("20000"));

        AllocationPlan plan = controller.calculateAllocation(stock, List.of(request));
        assertTrue(plan.planets().get(0).blockCount() > 0,
                "P3 stock = 0 must not stop production when the template makes its own");
        assertEquals(6L, plan.planets().get(0).blockCount(),
                "floor(20000 / 3240) = 6 blocks (block = 3x800 P2 + 240 GMB + 240 PV)");
        assertEquals(108000L, plan.planets().get(0).runtimeSeconds(), "6 x 18000s");
        assertEquals(240L, plan.planets().get(0).expectedOutputs().get(0).quantity(),
                "6 x 40 IRD");
        // The hauled GMB/PV (tier 3) appear on the load list but consume nothing.
        assertEquals(1440L, plan.planets().get(0).quantitiesByTypeId().get(GMB));
        assertEquals(1440L, plan.planets().get(0).quantitiesByTypeId().get(PV));
        assertEquals(4800L, usageOf(plan, POLYTEXTILES).allocated());
    }

    // ---- regression: the real 12-line inventory no longer zeroes IRD ----

    @Test
    void realInventory_noLongerZeroesTheFullChainPlanet() {
        // The exact bug from the UI acceptance: real inventory has Hazmat 41,
        // which used to make the full-chain planet produce 0 blocks. In
        // P2-only mode the 9 plentiful P2 items drive production instead.
        InventorySnapshot real = controller.parseInventory(REAL_INVENTORY).snapshot();
        PiCalculatorController.PlanetTemplate template = fullChain();
        PlanetRequest request = new PlanetRequest("Planet 1", template.plan(),
                new BigDecimal("50000"));

        AllocationPlan plan = controller.calculateAllocation(real, List.of(request));
        assertEquals(3L, plan.planets().get(0).blockCount(),
                "Hazmat 41 must not matter: P2 is plentiful, P3 is internal");
        assertEquals(360L, plan.planets().get(0).expectedOutputs().get(0).quantity());
    }

    // ---- pure P4 template regression (external P3, capacity-limited) ----

    @Test
    void calculateAllocation_pureP4Template_plentyP3_matchesPhase1Numbers() {
        InventorySnapshot plenty = new InventorySnapshot(Map.of(
                GMB, 1_000_000L, HAZMAT, 1_000_000L, PV, 1_000_000L));
        PiCalculatorController.PlanetTemplate ird = controller.loadPlanetTemplate(irdJson);

        AllocationPlan plan = controller.calculateAllocation(plenty, List.of(
                new PlanetRequest("Planet 1", ird.plan(), new BigDecimal("20000"))));

        assertEquals(46L, plan.planets().get(0).blockCount());
        assertEquals(165600L, plan.planets().get(0).runtimeSeconds());
        assertEquals(0, plan.planets().get(0).usedCapacity().compareTo(new BigDecimal("19872")));
        assertEquals(0, plan.planets().get(0).remainingCapacity().compareTo(new BigDecimal("128")));
        assertEquals(2208L, plan.planets().get(0).quantitiesByTypeId().get(GMB));
        assertEquals(2208L, plan.planets().get(0).quantitiesByTypeId().get(HAZMAT));
        assertEquals(2208L, plan.planets().get(0).quantitiesByTypeId().get(PV));
        assertEquals(368L, plan.planets().get(0).expectedOutputs().get(0).quantity());
    }

    // ---- zero-block explanation (capacity case) ----

    @Test
    void explainZeroBlocks_capacityTooSmall_reportsBlockVolume() {
        InventorySnapshot plenty = new InventorySnapshot(Map.of(
                GMB, 1_000_000L, HAZMAT, 1_000_000L, PV, 1_000_000L));
        PiCalculatorController.PlanetTemplate ird = controller.loadPlanetTemplate(irdJson);
        PlanetRequest request = new PlanetRequest("Planet 1", ird.plan(),
                new BigDecimal("100"));

        AllocationPlan plan = controller.calculateAllocation(plenty, List.of(request));
        assertEquals(0L, plan.planets().get(0).blockCount());

        PiCalculatorController.ZeroBlockExplanation explanation =
                controller.explainZeroBlocks(request, plan);
        assertTrue(explanation.capacityTooSmall());
        assertEquals(0, explanation.blockVolume().compareTo(new BigDecimal("432")));
        assertTrue(explanation.limitingItems().isEmpty());
    }

    // ---- bottleneck notes for the UI ----

    @Test
    void bottleneckNotes_fullChain_hazmatThrottleRendered() {
        List<String> notes = controller.bottleneckNotes(fullChain().plan());
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("Hazmat Detection Systems"), notes.get(0));
        assertTrue(notes.get(0).contains("48/h"), notes.get(0));
        assertTrue(notes.get(0).contains("80%"), notes.get(0));
    }

    @Test
    void bottleneckNotes_noBottleneck_emptyList() {
        assertTrue(controller.bottleneckNotes(
                controller.loadPlanetTemplate(irdJson).plan()).isEmpty());
    }
}
