import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.allocation.PlanetRequest;
import com.vepi.allocation.MultiPlanetAllocationPlanner;
import com.vepi.app.PiCalculatorController;
import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.flow.ProductionFlowSolver;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import com.vepi.template.TemplateParser;
import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.ui.CopyShoppingListFormatter;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Balance Inventory acceptance (no GUI): drives the REAL controller pipeline
 * (InventoryTextParser + TemplateParser + ProductionCapacityExtractor +
 * ProductionFlowSolver + InventoryBalanceCalculator) with the real 12-line
 * inventory and the Pandogodzilla template, then re-runs Load Allocation on
 * the same inputs as a regression.
 *
 * Expected (spec §10 / §18 / §27):
 *   Balance   : targetBlocks=1238, every P2 target=74280, Oxides 74225→74280
 *               (add 55), production time 51d 14h, output 3714 IRD,
 *               P3 stock (GMB/Hazmat/PV) unused.
 *   Load Alloc: 54000 m3 -> 133 blocks, 7980 of each P2, used 53865,
 *               remaining 135 m3, 399 IRD.
 */
public class BalanceAcceptance {

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

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();

        try (SdeRepository sde = new SdeRepository("data/sde/pi-sde.db")) {
            PiTierResolver tiers = new PiTierResolver(sde);
            TemplateParser templateParser = new TemplateParser();
            ProductionCapacityExtractor extractor = new ProductionCapacityExtractor(sde);
            ProductionFlowSolver flowSolver = new ProductionFlowSolver();
            MultiPlanetAllocationPlanner planner = new MultiPlanetAllocationPlanner();
            PiCalculatorController core =
                    new PiCalculatorController(Path.of("data/sde/pi-sde.db"));

            String templateJson = Files.readString(Path.of("data/templates/Pandogodzilla.json"));

            // ---------- 1. Balance Inventory: the full real pipeline ----------
            out.append("==============================================================\n");
            out.append("1. BALANCE INVENTORY — real inventory + Pandogodzilla\n");
            out.append("==============================================================\n");

            var status = core.parseInventory(REAL_INVENTORY);
            out.append("Parsed inventory: ").append(status.itemCount()).append(" items (")
                    .append(status.p2Count()).append(" P2, ").append(status.p3Count())
                    .append(" P3), warnings=").append(status.warnings()).append("\n");

            var template = core.loadPlanetTemplate(templateJson);
            SustainableProductionPlan plan = template.plan();
            out.append("Template plan: block=").append(plan.blockDurationSeconds())
                    .append("s, external/block:");
            for (Map.Entry<Long, Long> e : plan.externalRequirementsPerBlock().entrySet()) {
                out.append(' ').append(sde.getCommodity(e.getKey()).name()).append(" x")
                        .append(e.getValue());
            }
            out.append("\n\n");

            InventoryBalancePlan balance =
                    new com.vepi.balancing.InventoryBalanceCalculator()
                            .calculate(plan, status.snapshot(), core::tierOf, core::commodityOf);

            out.append("Target production blocks : ").append(balance.targetBlocks()).append("\n");
            out.append("Block duration           : ").append(balance.blockDurationSeconds()).append("s\n");
            out.append("Production time          : ")
                    .append(RecommendedLoadPlan.formatRuntime(balance.productionTimeSeconds()))
                    .append(" (").append(balance.productionTimeSeconds()).append("s)\n");
            out.append("Total current volume     : ").append(balance.totalCurrentVolume()).append(" m3\n");
            out.append("Total target volume      : ").append(balance.totalTargetVolume()).append(" m3\n");
            out.append("Total additional volume  : ").append(balance.totalAdditionalVolume()).append(" m3\n");
            out.append("Expected final output    : ");
            for (var o : balance.expectedFinalOutputs()) {
                out.append(o.quantity()).append("x ").append(o.commodity().name()).append("; ");
            }
            out.append("\n\n");

            out.append(String.format("  %-28s %9s %9s %9s %9s%n",
                    "Material", "PerBlock", "Current", "Target", "Add"));
            for (InventoryBalanceMaterial m : balance.materials()) {
                out.append(String.format("  %-28s %9d %9d %9d %9d%n",
                        m.commodity().name(), m.requiredPerBlock(), m.currentQuantity(),
                        m.targetQuantity(), m.addQuantity()));
            }
            out.append("\n  UNUSED INVENTORY (not part of this balance):\n");
            for (var u : balance.unusedInventory()) {
                out.append(String.format("    P%d %-28s %d%n", u.tier(), u.commodity().name(),
                        u.quantity()));
            }
            out.append("\n  COPY SHOPPING LIST (add > 0 only):\n")
                    .append(indent(CopyShoppingListFormatter.shoppingList(balance)));
            out.append("\n  COPY TARGET INVENTORY (all targets):\n")
                    .append(indent(CopyShoppingListFormatter.targetInventory(balance)));

            // ---------- assertions ----------
            check(out, "balance.targetBlocks == 1238", balance.targetBlocks() == 1238);
            check(out, "all 9 P2 targets == 74280",
                    balance.materials().size() == 9 && balance.materials().stream()
                            .allMatch(m -> m.targetQuantity() == 74280));
            check(out, "Oxides 74225 -> 74280 (add 55)",
                    balance.materials().stream()
                            .filter(m -> m.commodity().name().equals("Oxides"))
                            .allMatch(m -> m.currentQuantity() == 74225
                                    && m.targetQuantity() == 74280 && m.addQuantity() == 55));
            check(out, "production time == 4456800s (51d 14h)",
                    balance.productionTimeSeconds() == 1238L * 3600);
            check(out, "final output == 3714 IRD",
                    balance.expectedFinalOutputs().size() == 1
                            && balance.expectedFinalOutputs().get(0).quantity() == 3714
                            && balance.expectedFinalOutputs().get(0).commodity().name()
                            .equals("Integrity Response Drones"));
            check(out, "P3 stock unused (GMB 1527, Hazmat 41, PV 688)",
                    balance.unusedInventory().size() == 3
                            && balance.unusedInventory().stream().allMatch(u -> u.tier() == 3));
            BigDecimal expectedAddVolume = BigDecimal.valueOf(154309)
                    .multiply(new BigDecimal("0.75"));
            check(out, "totalAdditionalVolume == 154309 x 0.75 = 115731.75",
                    balance.totalAdditionalVolume().compareTo(expectedAddVolume) == 0);

            // ---------- 2. Load Allocation regression on the same inputs ----------
            out.append("\n==============================================================\n");
            out.append("2. LOAD ALLOCATION REGRESSION — same inventory + template\n");
            out.append("   Pandogodzilla @ 54000 m3 (capacity-limited)\n");
            out.append("   Expected: 133 blocks, 7980 of each P2, used 53865,\n");
            out.append("             remaining 135 m3, output 399 IRD.\n");
            out.append("==============================================================\n");

            AllocationPlan alloc = planner.plan(
                    List.of(new PlanetRequest("Planet 1 (Pandogodzilla)", plan,
                            new BigDecimal("54000"))),
                    status.snapshot(), sde::getCommodity, tiers::tierOf);
            PlanetAllocation p1 = alloc.planets().get(0);
            out.append("Blocks: ").append(p1.blockCount())
                    .append("  Runtime: ")
                    .append(RecommendedLoadPlan.formatRuntime(p1.runtimeSeconds())).append("\n");
            p1.quantitiesByTypeId().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.append(String.format("  %-28s %6d%n",
                            sde.getCommodity(e.getKey()).name(), e.getValue())));
            out.append("Used capacity : ").append(p1.usedCapacity()).append(" m3\n");
            out.append("Expected output: ");
            for (var o : p1.expectedOutputs()) {
                out.append(o.quantity()).append("x ").append(o.commodity().name()).append("; ");
            }
            out.append("\n");
            out.append("REMAINING INVENTORY:\n");
            for (var u : alloc.inventoryUsage()) {
                out.append(String.format("  P%d %-28s %7d -> allocated %-6d remaining %d%n",
                        u.tier(), u.name(), u.original(), u.allocated(), u.remaining()));
            }

            check(out, "allocation blocks == 133", p1.blockCount() == 133);
            check(out, "each P2 loaded == 7980",
                    p1.quantitiesByTypeId().values().stream().allMatch(q -> q == 7980));
            check(out, "used capacity == 53865",
                    p1.usedCapacity().compareTo(new BigDecimal("53865")) == 0);
            check(out, "remaining capacity == 135",
                    p1.request().capacity().subtract(p1.usedCapacity())
                            .compareTo(new BigDecimal("135")) == 0);
            check(out, "output == 399 IRD",
                    p1.expectedOutputs().stream()
                            .allMatch(o -> o.quantity() == 399
                                    && o.commodity().name().equals("Integrity Response Drones")));

            core.close();
        }

        out.append("\n==============================================================\n");
        out.append(failures == 0 ? "ALL ACCEPTANCE CHECKS PASSED\n"
                : failures + " ACCEPTANCE CHECK(S) FAILED\n");
        out.append("==============================================================\n");

        System.out.println(out);
        Files.writeString(Path.of("out/balance-acceptance.txt"), out.toString());
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(StringBuilder out, String what, boolean ok) {
        out.append("  [").append(ok ? "PASS" : "FAIL").append("] ").append(what).append("\n");
        if (!ok) {
            failures++;
        }
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("    ").append(line).append('\n');
        }
        return sb.toString();
    }
}
