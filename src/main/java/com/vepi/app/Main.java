package com.vepi.app;

import com.vepi.balance.ProductionBlock;
import com.vepi.balance.ProductionBlockCalculator;
import com.vepi.calc.MaterialThroughput;
import com.vepi.calc.ProductionCalculator;
import com.vepi.calc.ProductionResult;
import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.domain.PiCommodity;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.load.LoadOptimizer;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.sde.SdeRepository;
import com.vepi.template.PiTemplate;
import com.vepi.template.TemplateParser;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 1 CLI: template + available capacity -> balanced recommended load plan.
 *
 * <p>Usage: {@code Main <template.json> <capacity-m3> [pi-sde.db]}
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Main <template.json> <capacity-m3> [pi-sde.db]");
            System.exit(2);
        }
        Path templatePath = Path.of(args[0]);
        BigDecimal capacity = LoadOptimizer.parseCapacity(args[1]);
        String sdeDb = args.length > 2 ? args[2] : "data/sde/pi-sde.db";

        System.out.println("=== EVE PI Phase 1 - load balancing ===\n");

        TemplateParser parser = new TemplateParser();
        PiTemplate tpl = parser.parse(templatePath);

        System.out.println("Template:");
        System.out.println("  comment: " + (tpl.Cmt == null ? "(none)" : tpl.Cmt));
        System.out.println("  total pins: " + tpl.P.size());

        try (SdeRepository sde = new SdeRepository(sdeDb)) {
            var extractor = new ProductionCapacityExtractor(sde);
            List<TemplateProductionFacility> facilities = extractor.extract(tpl);

            System.out.println("\nTemplate parse:");
            System.out.println("  Production facilities: " + facilities.size());
            Map<String, Long> byConfig = facilities.stream()
                    .collect(Collectors.groupingBy(
                            f -> f.schematic().name() + " (type " + f.facilityTypeId()
                                    + ", cycle " + f.schematic().cycleTimeSeconds() + "s)",
                            Collectors.counting()));
            System.out.println("  Detected production configuration:");
            byConfig.forEach((cfg, cnt) -> System.out.println("    " + cfg + " x" + cnt));

            // Phase 0 path (kept for cross-checking): exact per-hour full-load rates.
            ProductionCalculator calc = new ProductionCalculator(sde);
            ProductionResult hourly = calc.compute(facilities);

            System.out.println("\nFull-load consumption (per hour):");
            for (MaterialThroughput m : hourly.externalInputs()) {
                System.out.println("  " + m.commodity().name() + ": "
                        + m.unitsPerHour().toDisplayString() + " units/h, "
                        + m.m3PerHour().stripTrailingZeros().toPlainString() + " m3/h");
            }
            System.out.println("  Total external input: "
                    + hourly.totalExternalM3PerHour().stripTrailingZeros().toPlainString()
                    + " m3/h");

            // Phase 1 path: integer production block + balanced load plan.
            ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

            System.out.println("\nProduction block:");
            System.out.println("  Base period: " + block.basePeriodSeconds()
                    + " s (LCM of facility cycle times)");
            System.out.println("  External requirements per production block:");
            for (Map.Entry<Long, Long> e : block.externalRequirements().entrySet()) {
                PiCommodity c = sde.getCommodity(e.getKey());
                BigDecimal vol = c.volume().multiply(BigDecimal.valueOf(e.getValue()));
                System.out.println("    " + c.name() + " (" + c.typeId() + "): "
                        + e.getValue() + " units / "
                        + vol.stripTrailingZeros().toPlainString() + " m3");
            }
            BigDecimal blockVolume = BigDecimal.ZERO;
            for (Map.Entry<Long, Long> e : block.externalRequirements().entrySet()) {
                blockVolume = blockVolume.add(
                        sde.getCommodity(e.getKey()).volume()
                                .multiply(BigDecimal.valueOf(e.getValue())));
            }
            System.out.println("  Block volume: "
                    + blockVolume.stripTrailingZeros().toPlainString() + " m3");
            System.out.println("  Net output per production block:");
            for (Map.Entry<Long, Long> e : block.netOutputs().entrySet()) {
                PiCommodity c = sde.getCommodity(e.getKey());
                System.out.println("    " + c.name() + " (" + c.typeId() + "): "
                        + e.getValue() + " units");
            }

            LoadOptimizer optimizer = new LoadOptimizer();
            RecommendedLoadPlan plan = optimizer.optimize(block, capacity, sde::getCommodity);

            System.out.println();
            System.out.print(plan);
        }
    }
}
