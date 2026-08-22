import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.domain.PiCommodity;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.flow.ProductionFlowSolver;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import com.vepi.template.TemplateParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Probe: dump the sustainable plan of the fixture templates. Default package, tools-only. */
public final class FlowProbe {

    public static void main(String[] args) throws Exception {
        SdeRepository sde = new SdeRepository("data/sde/pi-sde.db");
        PiTierResolver tiers = new PiTierResolver(sde);
        ProductionFlowSolver solver = new ProductionFlowSolver();
        for (String name : List.of("FullChainIrd", "FullChainMultiBottleneck",
                "IrdHazmatBottleneck", "IrdGmbSurplus", "MultiStageGmbDeficit",
                "IntegrityResponseDrones", "Pandogodzilla")) {
            String text = Files.readString(Path.of("data/templates/" + name + ".json"));
            var template = new TemplateParser().parse(text);
            List<TemplateProductionFacility> facilities =
                    new ProductionCapacityExtractor(sde).extract(template);
            SustainableProductionPlan plan = solver.solve(facilities);
            System.out.println("==== " + name + " ====");
            System.out.println("  blockDuration = " + plan.blockDurationSeconds() + "s");
            for (var g : plan.facilityUtilizations()) {
                System.out.println("  util: " + g.schematicName() + " x" + g.facilityCount()
                        + " u=" + g.utilization() + " cycles/facility/block="
                        + g.cyclesPerFacilityPerBlock());
            }
            for (var f : plan.internalFlows()) {
                PiCommodity c = sde.getCommodity(f.typeId());
                System.out.println("  internal: " + c.name() + " prod/block=" + f.producedPerBlock()
                        + " cons/block=" + f.consumedPerBlock() + " util=" + f.utilization()
                        + " cap/h=" + f.capacityPerHour() + " sust/h=" + f.sustainablePerHour());
            }
            for (var b : plan.bottlenecks()) {
                PiCommodity c = sde.getCommodity(b.typeId());
                System.out.println("  bottleneck: " + c.name() + " cap/h=" + b.capacityPerHour()
                        + " demand/h=" + b.demandAtFullPerHour() + " throttle=" + b.throttle());
            }
            System.out.println("  external per block:");
            for (var e : plan.externalRequirementsPerBlock().entrySet()) {
                PiCommodity c = sde.getCommodity(e.getKey());
                System.out.println("    P" + tiers.tierOf(e.getKey()) + " " + c.name()
                        + " " + e.getValue() + " (" + c.volume().multiply(java.math.BigDecimal.valueOf(e.getValue())) + " m3)");
            }
            System.out.println("  outputs per block:");
            for (var e : plan.finalOutputsPerBlock().entrySet()) {
                PiCommodity c = sde.getCommodity(e.getKey());
                System.out.println("    P" + tiers.tierOf(e.getKey()) + " " + c.name() + " " + e.getValue());
            }
        }
        sde.close();
    }
}
