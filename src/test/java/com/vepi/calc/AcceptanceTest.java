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

/**
 * End-to-end acceptance test: real template + real SDE + calculator.
 *
 * Integrity Response Drones P4 factory (8 High-Tech plants):
 *   SDE schematic 118, cycle 3600s, 1 cycle/hour.
 *   inputs per cycle: 6 x Gel-Matrix Biopaste(2348, 3 m³),
 *                     6 x Hazmat Detection Systems(2366, 3 m³),
 *                     6 x Planetary Vehicles(9846, 3 m³)
 *   output per cycle: 1 x Integrity Response Drones(2868, 50 m³)
 *
 * Expected full-load (8 factories, 1 cycle/h):
 *   each input = 8 * 6 * 1 = 48 units/h -> 144 m³/h
 *   total external input = 432 m³/h
 *   output = 8 * 1 * 1 = 8 units/h -> 400 m³/h
 */
class AcceptanceTest {

    private static final String TEMPLATE = "data/templates/IntegrityResponseDrones.json";
    private static final String DB = "data/sde/pi-sde.db";
    private static SdeRepository sde;

    @BeforeAll
    static void setUp() { sde = new SdeRepository(DB); }

    @AfterAll
    static void tearDown() { sde.close(); }

    @Test
    void realTemplate_fullLoadConsumption() {
        PiTemplate tpl = new TemplateParser().parse(Path.of(TEMPLATE));
        var extractor = new ProductionCapacityExtractor(sde);
        List<TemplateProductionFacility> facilities = extractor.extract(tpl);

        assertEquals(8, facilities.size(), "8 production facilities");

        ProductionCalculator calc = new ProductionCalculator(sde);
        ProductionResult r = calc.compute(facilities);

        Map<Long, String> external = r.externalInputs().stream()
                .collect(Collectors.toMap(MaterialThroughput::typeId,
                        m -> m.unitsPerHour().toDisplayString()));
        assertEquals("48", external.get(2348L), "Gel-Matrix Biopaste 48/h");
        assertEquals("48", external.get(2366L), "Hazmat Detection Systems 48/h");
        assertEquals("48", external.get(9846L), "Planetary Vehicles 48/h");
        assertEquals(3, external.size(), "only 3 external inputs (all P3)");

        Map<Long, String> outputs = r.outputs().stream()
                .collect(Collectors.toMap(MaterialThroughput::typeId,
                        m -> m.unitsPerHour().toDisplayString()));
        assertEquals("8", outputs.get(2868L), "8 Integrity Response Drones/h");

        assertEquals("432", r.totalExternalM3PerHour()
                .stripTrailingZeros().toPlainString(), "432 m³/h external input");
    }

    /**
     * Phase 1 acceptance: real template + real SDE + 20000 m3 capacity.
     *
     * Block = 3600s, external per block = 3x48 P3 (432 m3), output 8 IRD.
     * floor(20000/432) = 46 blocks (46x432=19872; 47x432=20304 > 20000).
     */
    @Test
    void realTemplate_loadPlan_20000m3() {
        PiTemplate tpl = new TemplateParser().parse(Path.of(TEMPLATE));
        var extractor = new ProductionCapacityExtractor(sde);
        List<TemplateProductionFacility> facilities = extractor.extract(tpl);

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        // --- block model ---
        assertEquals(3600L, block.basePeriodSeconds());
        assertEquals(Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L),
                block.externalRequirements(), "48 of each P3 per block");
        assertEquals(Map.of(2868L, 8L), block.netOutputs(), "8 IRD per block");

        // --- load plan at 20000 m3 ---
        LoadOptimizer optimizer = new LoadOptimizer();
        RecommendedLoadPlan plan = optimizer.optimize(block, new BigDecimal("20000"),
                sde::getCommodity);

        assertEquals(46L, plan.blockCount(), "floor(20000/432) = 46 blocks");
        assertEquals(3600L, plan.basePeriodSeconds());
        assertEquals(165600L, plan.runtimeSeconds(), "46 x 3600s = 46h");

        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(3, byType.size());
        for (long typeId : new long[]{2348L, 2366L, 9846L}) {
            RecommendedMaterialLoad m = byType.get(typeId);
            assertEquals(2208L, m.quantity(), "48 x 46 = 2208 units");
            assertEquals(0, m.volume().compareTo(new BigDecimal("6624")),
                    "2208 x 3 m3 = 6624 m3");
            assertEquals(165600L, m.supportedRuntimeSeconds(),
                    "all materials balanced to the full runtime");
        }

        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("19872")),
                "3 x 6624 = 19872 m3");
        assertEquals(0, plan.remainingCapacity().compareTo(new BigDecimal("128")),
                "20000 - 19872 = 128 m3");

        assertEquals(1, plan.expectedOutputs().size());
        assertEquals(2868L, plan.expectedOutputs().get(0).commodity().typeId());
        assertEquals(368L, plan.expectedOutputs().get(0).quantity(), "8 x 46 = 368 IRD");
    }
}
