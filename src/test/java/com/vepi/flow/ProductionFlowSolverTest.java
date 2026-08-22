package com.vepi.flow;

import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import com.vepi.template.TemplateException;
import com.vepi.template.TemplateParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProductionFlowSolver tests on fixture templates built from the REAL SDE
 * recipes (P2 = 3x10 -> 3 P3 per 3600s; IRD = 3x6 -> 1 per 3600s).
 *
 * <p>Expected numbers are hand-derived from the spec's canonical example:
 * IRD demand 60/h of each P3 vs internal capacities GMB 72/h, Hazmat 48/h,
 * PV 60/h (FullChainIrd: 24/16/20 P3 factories + 10 IRD factories) — the
 * Hazmat shortfall must THROTTLE the chain to 4/5, never become an external
 * requirement; the GMB surplus must be DERATED so no P2 is wasted.
 */
class ProductionFlowSolverTest {

    // Schematics (SDE): 118=IRD, 95=GMB, 110=Hazmat, 103=PV.
    private static final long SCH_IRD = 118L;
    private static final long SCH_GMB = 95L;
    private static final long SCH_HAZMAT = 110L;
    private static final long SCH_PV = 103L;

    // Real typeIDs (verified against the SDE).
    private static final long IRD = 2868L, GMB = 2348L, HAZMAT = 2366L, PV = 9846L;
    private static final long BIOCELLS = 2329L, OXIDES = 2317L, SUPERCONDUCTORS = 9838L;
    private static final long POLYTEXTILES = 3695L, TRANSMITTER = 9840L, VIRAL_AGENT = 3775L;
    private static final long MECH_PARTS = 3689L, MINI_ELECTRONICS = 9842L, SUPERPLASTICS = 2312L;

    private static SdeRepository sde;
    private static PiTierResolver tiers;
    private static final ProductionFlowSolver solver = new ProductionFlowSolver();

    @BeforeAll
    static void setUp() {
        sde = new SdeRepository("data/sde/pi-sde.db");
        tiers = new PiTierResolver(sde);
    }

    @AfterAll
    static void tearDown() {
        sde.close();
    }

    // ------------------------------------------------------------- helpers

    private static SustainableProductionPlan solve(String fixture) throws Exception {
        var template = new TemplateParser().parse(
                Files.readString(Path.of("data/templates/" + fixture + ".json")));
        List<TemplateProductionFacility> facilities =
                new ProductionCapacityExtractor(sde).extract(template);
        return solver.solve(facilities);
    }

    private static SustainableProductionPlan.GroupUtilization group(
            SustainableProductionPlan plan, long schematicId) {
        return plan.facilityUtilizations().stream()
                .filter(g -> g.schematicId() == schematicId)
                .findFirst().orElseThrow(() -> new AssertionError("no group " + schematicId));
    }

    private static SustainableProductionPlan.InternalFlow flow(
            SustainableProductionPlan plan, long typeId) {
        return plan.internalFlows().stream()
                .filter(f -> f.typeId() == typeId)
                .findFirst().orElseThrow(() -> new AssertionError("no internal flow " + typeId));
    }

    // ------------------------------- spec §8 example: 60/60/60 vs 48/72/60

    @Test
    void fullChainIrd_specExample_hazmatThrottlesChainToFourFifths() throws Exception {
        SustainableProductionPlan plan = solve("FullChainIrd");

        // 24 GMB(u 2/3, 10 cycles) + 16 Hazmat(u 1, 15) + 20 PV(u 4/5, 12)
        // + 10 IRD(u 4/5, 12) at a 54000s block.
        assertEquals(54000L, plan.blockDurationSeconds());
        assertEquals(Fraction.of(4, 5), group(plan, SCH_IRD).utilization());
        assertEquals(Fraction.of(2, 3), group(plan, SCH_GMB).utilization());
        assertEquals(Fraction.ONE, group(plan, SCH_HAZMAT).utilization());
        assertEquals(Fraction.of(4, 5), group(plan, SCH_PV).utilization());
        assertEquals(12L, group(plan, SCH_IRD).cyclesPerFacilityPerBlock());
        assertEquals(10L, group(plan, SCH_GMB).cyclesPerFacilityPerBlock());
        assertEquals(15L, group(plan, SCH_HAZMAT).cyclesPerFacilityPerBlock());
        assertEquals(12L, group(plan, SCH_PV).cyclesPerFacilityPerBlock());

        // Internal P3 flows are exactly balanced per block.
        for (long x : List.of(GMB, HAZMAT, PV)) {
            SustainableProductionPlan.InternalFlow f = flow(plan, x);
            assertEquals(720L, f.producedPerBlock(), "produced " + x);
            assertEquals(720L, f.consumedPerBlock(), "consumed " + x);
        }
        assertEquals(Fraction.ONE, flow(plan, HAZMAT).utilization());
        assertEquals(Fraction.of(2, 3), flow(plan, GMB).utilization());
        assertEquals(Fraction.of(4, 5), flow(plan, PV).utilization());

        // Exactly one bottleneck: Hazmat 48/h capacity vs 60/h chain demand.
        assertEquals(1, plan.bottlenecks().size());
        SustainableProductionPlan.Bottleneck b = plan.bottlenecks().get(0);
        assertEquals(HAZMAT, b.typeId());
        assertEquals(Fraction.of(48, 1), b.capacityPerHour());
        assertEquals(Fraction.of(60, 1), b.demandAtFullPerHour());
        assertEquals(Fraction.of(4, 5), b.throttle());

        // External load is exactly the 9 P2 inputs, 2400 each (1800 m3 per item).
        Map<Long, Long> external = plan.externalRequirementsPerBlock();
        assertEquals(9, external.size());
        for (long p2 : List.of(BIOCELLS, OXIDES, SUPERCONDUCTORS, POLYTEXTILES, TRANSMITTER,
                VIRAL_AGENT, MECH_PARTS, MINI_ELECTRONICS, SUPERPLASTICS)) {
            assertEquals(2400L, external.get(p2), "external P2 " + p2);
            assertEquals(2, tiers.tierOf(p2));
        }

        // Final output: 120 IRD per 54000s block.
        assertEquals(Map.of(IRD, 120L), plan.finalOutputsPerBlock());
    }

    // --------------------------------------- spec §24: multiple P3 bottlenecks

    @Test
    void fullChainMultiBottleneck_weakestP3ThrottlesToThreeFifths() throws Exception {
        // Capacities GMB 60/h, Hazmat 48/h, PV 36/h -> weakest (PV) wins: 36/60 = 3/5.
        SustainableProductionPlan plan = solve("FullChainMultiBottleneck");

        assertEquals(72000L, plan.blockDurationSeconds());
        assertEquals(Fraction.of(3, 5), group(plan, SCH_IRD).utilization());
        assertEquals(Fraction.of(3, 5), group(plan, SCH_GMB).utilization());
        assertEquals(Fraction.of(3, 4), group(plan, SCH_HAZMAT).utilization());
        assertEquals(Fraction.ONE, group(plan, SCH_PV).utilization());

        assertEquals(1, plan.bottlenecks().size());
        SustainableProductionPlan.Bottleneck b = plan.bottlenecks().get(0);
        assertEquals(PV, b.typeId());
        assertEquals(Fraction.of(36, 1), b.capacityPerHour());
        assertEquals(Fraction.of(60, 1), b.demandAtFullPerHour());
        assertEquals(Fraction.of(3, 5), b.throttle());

        assertEquals(9, plan.externalRequirementsPerBlock().size());
        assertTrue(plan.externalRequirementsPerBlock().values().stream()
                .allMatch(v -> v == 2400L), "every P2 exactly 2400/block");
        assertEquals(Map.of(IRD, 120L), plan.finalOutputsPerBlock());
    }

    // ---------------------------- spec §22: single upstream bottleneck (no import)

    @Test
    void irdHazmatBottleneck_shortfallThrottlesInsteadOfImporting() throws Exception {
        SustainableProductionPlan plan = solve("IrdHazmatBottleneck");

        assertEquals(18000L, plan.blockDurationSeconds());
        assertEquals(Fraction.of(4, 5), group(plan, SCH_IRD).utilization());
        assertEquals(Fraction.ONE, group(plan, SCH_HAZMAT).utilization());

        // Hazmat is INTERNAL: never an external requirement, even though the
        // template cannot fully feed the IRD factories (48/h vs 60/h).
        assertFalse(plan.externalRequirementsPerBlock().containsKey(HAZMAT),
                "internal shortfall must throttle, never import");
        assertEquals(Fraction.of(4, 5), plan.bottlenecks().get(0).throttle());

        // GMB and PV have no producing facility -> they stay external (tier 3,
        // the allocator displays them but does not consume P3 stock).
        assertEquals(240L, plan.externalRequirementsPerBlock().get(GMB));
        assertEquals(240L, plan.externalRequirementsPerBlock().get(PV));
        assertEquals(800L, plan.externalRequirementsPerBlock().get(POLYTEXTILES));
        assertEquals(800L, plan.externalRequirementsPerBlock().get(TRANSMITTER));
        assertEquals(800L, plan.externalRequirementsPerBlock().get(VIRAL_AGENT));
        assertEquals(5, plan.externalRequirementsPerBlock().size());
        assertEquals(Map.of(IRD, 40L), plan.finalOutputsPerBlock());
    }

    // --------------------------------- spec §23: upstream surplus is derated

    @Test
    void irdGmbSurplus_producersDerated_noP2Wasted() throws Exception {
        SustainableProductionPlan plan = solve("IrdGmbSurplus");

        assertEquals(10800L, plan.blockDurationSeconds());
        assertEquals(Fraction.ONE, group(plan, SCH_IRD).utilization());
        assertEquals(Fraction.of(2, 3), group(plan, SCH_GMB).utilization(),
                "90/h capacity derated to the 60/h actually needed");

        // P2 demand follows the DERATED use (600 each), not full capacity (900).
        assertEquals(600L, plan.externalRequirementsPerBlock().get(BIOCELLS), "not 900");
        assertEquals(600L, plan.externalRequirementsPerBlock().get(OXIDES));
        assertEquals(600L, plan.externalRequirementsPerBlock().get(SUPERCONDUCTORS));
        assertEquals(180L, plan.externalRequirementsPerBlock().get(HAZMAT));
        assertEquals(180L, plan.externalRequirementsPerBlock().get(PV));

        // A derated surplus is NOT a bottleneck.
        assertTrue(plan.bottlenecks().isEmpty());
        assertEquals(Map.of(IRD, 30L), plan.finalOutputsPerBlock());
    }

    // ------------------ pure P4 template (no internal production): unchanged

    @Test
    void integrityResponseDrones_pureP4_behavesLikeTheOldModel() throws Exception {
        SustainableProductionPlan plan = solve("IntegrityResponseDrones");

        assertEquals(3600L, plan.blockDurationSeconds());
        assertTrue(plan.internalFlows().isEmpty());
        assertTrue(plan.bottlenecks().isEmpty());
        assertEquals(Map.of(GMB, 48L, HAZMAT, 48L, PV, 48L),
                plan.externalRequirementsPerBlock());
        assertEquals(Map.of(IRD, 8L), plan.finalOutputsPerBlock());
        assertEquals(Fraction.ONE, group(plan, SCH_IRD).utilization());
        assertEquals(1L, group(plan, SCH_IRD).cyclesPerFacilityPerBlock());
    }

    // ----------------------------------------------------------- edge cases

    @Test
    void noProductionFacilities_throws() {
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> solver.solve(List.of()));
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> solver.solve(null));
    }

    @Test
    void everyPerBlockQuantityIsAnExactInteger_byConstruction() throws Exception {
        // Whatever the utilizations, the solver must pick a block duration at
        // which all counts are whole — spot-check the two hardest fixtures.
        for (String fixture : List.of("FullChainIrd", "FullChainMultiBottleneck",
                "IrdGmbSurplus", "IrdHazmatBottleneck")) {
            SustainableProductionPlan plan = solve(fixture);
            for (var g : plan.facilityUtilizations()) {
                long block = plan.blockDurationSeconds();
                long expected = block * g.utilization().numerator().longValueExact()
                        / (g.utilization().denominator().longValueExact() * g.cycleTimeSeconds());
                assertEquals(expected, g.cyclesPerFacilityPerBlock(),
                        fixture + " cycles for schematic " + g.schematicId());
            }
            for (var f : plan.internalFlows()) {
                assertEquals(f.producedPerBlock(), f.consumedPerBlock(),
                        fixture + " internal balance for " + f.typeId());
            }
        }
    }
}
