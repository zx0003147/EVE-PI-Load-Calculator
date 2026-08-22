package com.vepi.calc;

import com.vepi.balance.ProductionBlock;
import com.vepi.balance.ProductionBlockCalculator;
import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.load.LoadOptimizer;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;
import com.vepi.sde.SdeRepository;
import com.vepi.template.PiTemplate;
import com.vepi.template.TemplateParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Multi-stage (P2 -> P3 -> P4) acceptance tests using REAL SDE schematics via
 * synthetic template fixtures.
 *
 * <p>Real SDE recipes involved (Fuzzwork SDE 3475087, post-2025 PI rework):
 * <ul>
 *   <li>schematic 95 "Gel-Matrix Biopaste" (P3), cycle 3600s:
 *       10x Oxides(2317) + 10x Biocells(2329) + 10x Superconductors(9838) -> 3x GMB(2348)</li>
 *   <li>schematic 118 "Integrity Response Drones" (P4), cycle 3600s:
 *       6x GMB + 6x Hazmat(2366) + 6x Planetary Vehicles(9846) -> 1x IRD(2868)</li>
 * </ul>
 */
class MultiStageAcceptanceTest {

    private static final String DB = "data/sde/pi-sde.db";
    private static SdeRepository sde;

    @BeforeAll
    static void setUp() { sde = new SdeRepository(DB); }

    @AfterAll
    static void tearDown() { sde.close(); }

    private List<TemplateProductionFacility> facilities(String template) {
        PiTemplate tpl = new TemplateParser().parse(Path.of(template));
        return new ProductionCapacityExtractor(sde).extract(tpl);
    }

    /**
     * Deficit case: 10 GMB factories produce 30 GMB/block but 8 IRD factories
     * consume 48/block -> GMB external deficit = 18 units/block.
     */
    @Test
    void deficitTemplate_gmbRequiresExternalSupply() {
        List<TemplateProductionFacility> facilities =
                facilities("data/templates/MultiStageGmbDeficit.json");
        assertEquals(18, facilities.size(), "10 GMB + 8 IRD factories (launchpad skipped)");

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(3600L, block.basePeriodSeconds(), "both schematics cycle at 3600s");
        assertEquals(
                Map.of(2317L, 100L, 2329L, 100L, 9838L, 100L,
                        2348L, 18L, 2366L, 48L, 9846L, 48L),
                block.externalRequirements(),
                "GMB deficit 48-30=18 must be an external input alongside the P2s");
        assertEquals(Map.of(2868L, 8L), block.netOutputs());

        // Block volume: 3x(100x0.75) + 18x3 + 2x(48x3) = 225 + 54 + 288 = 567 m3.
        // Capacity 1701 = exactly 3 blocks.
        RecommendedLoadPlan plan = new LoadOptimizer().optimize(
                block, new BigDecimal("1701"), sde::getCommodity);

        assertEquals(3L, plan.blockCount());
        assertEquals(10800L, plan.runtimeSeconds());
        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("1701")));
        assertEquals(0, plan.remainingCapacity().compareTo(BigDecimal.ZERO));

        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(6, byType.size());
        assertEquals(300L, byType.get(2317L).quantity(), "Oxides: 100 x 3");
        assertEquals(300L, byType.get(2329L).quantity(), "Biocells: 100 x 3");
        assertEquals(300L, byType.get(9838L).quantity(), "Superconductors: 100 x 3");
        assertEquals(54L, byType.get(2348L).quantity(), "GMB deficit: 18 x 3");
        assertEquals(144L, byType.get(2366L).quantity());
        assertEquals(144L, byType.get(9846L).quantity());
        assertEquals(0, byType.get(2348L).volume().compareTo(new BigDecimal("162")));

        assertEquals(24L, plan.expectedOutputs().get(0).quantity(), "8 IRD x 3 blocks");
    }

    /**
     * Balanced case: 16 GMB factories produce 48 GMB/block = exactly what the
     * 8 IRD factories consume -> GMB is NOT an external input.
     */
    @Test
    void balancedTemplate_intermediateIsInternal() {
        List<TemplateProductionFacility> facilities =
                facilities("data/templates/MultiStageGmbBalanced.json");
        assertEquals(24, facilities.size(), "16 GMB + 8 IRD factories");

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(
                Map.of(2317L, 160L, 2329L, 160L, 9838L, 160L, 2366L, 48L, 9846L, 48L),
                block.externalRequirements(),
                "balanced GMB is fully internal");
        assertFalse(block.externalRequirements().containsKey(2348L),
                "GMB must NOT appear as an external requirement");
        assertEquals(Map.of(2868L, 8L), block.netOutputs());

        // Block volume: 3x(160x0.75) + 2x(48x3) = 360 + 288 = 648 m3.
        // Capacity 1296 = exactly 2 blocks; 1300 -> still 2 blocks (remaining 4).
        LoadOptimizer optimizer = new LoadOptimizer();
        RecommendedLoadPlan exact = optimizer.optimize(block, new BigDecimal("1296"),
                sde::getCommodity);
        assertEquals(2L, exact.blockCount());
        assertEquals(0, exact.remainingCapacity().compareTo(BigDecimal.ZERO));
        assertEquals(16L, exact.expectedOutputs().get(0).quantity());

        RecommendedLoadPlan over = optimizer.optimize(block, new BigDecimal("1300"),
                sde::getCommodity);
        assertEquals(2L, over.blockCount());
        assertEquals(0, over.remainingCapacity().compareTo(new BigDecimal("4")));
    }
}
