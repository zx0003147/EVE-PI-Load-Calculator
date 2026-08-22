import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.InventoryItemUsage;
import com.vepi.allocation.MaterialAllocation;
import com.vepi.allocation.MultiPlanetAllocationPlanner;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.allocation.PlanetRequest;
import com.vepi.balance.ProductionBlock;
import com.vepi.balance.ProductionBlockCalculator;
import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.domain.PiCommodity;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.flow.ProductionFlowSolver;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventoryParseResult;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.inventory.InventoryTextParser;
import com.vepi.load.LoadOptimizer;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import com.vepi.template.TemplateParser;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P2-only sustainable-production acceptance run (no GUI): the acceptance
 * scenarios A–D of the core-need revision, the real-inventory regression
 * (Hazmat 41 must no longer zero a full-chain planet), a two-planet shared-P2
 * run, and the Phase 1 single-planet regression.
 */
public class AllocationAcceptance {

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

    // The 9 P2 inputs of the full IRD chain (SDE-verified typeIDs).
    private static final long[] FULL_CHAIN_P2 = {
            2329L, 2317L, 9838L,      // Biocells, Oxides, Superconductors -> GMB
            3695L, 9840L, 3775L,      // Polytextiles, Transmitter, Viral Agent -> Hazmat
            3689L, 9842L, 2312L       // Mech Parts, Mini Electronics, Superplastics -> PV
    };
    private static final long OXIDES = 2317L;

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        SdeRepository sde = new SdeRepository("data/sde/pi-sde.db");
        PiTierResolver tiers = new PiTierResolver(sde);
        InventoryTextParser inventoryParser = new InventoryTextParser(sde::findCommodityByName);
        TemplateParser templateParser = new TemplateParser();
        ProductionCapacityExtractor extractor = new ProductionCapacityExtractor(sde);
        ProductionFlowSolver flowSolver = new ProductionFlowSolver();
        MultiPlanetAllocationPlanner planner = new MultiPlanetAllocationPlanner();

        // ---------- 1. Inventory parser: the real pasted text ----------
        out.append("==============================================================\n");
        out.append("1. INVENTORY PARSER — real pasted inventory text\n");
        out.append("==============================================================\n");
        InventoryParseResult parsed = inventoryParser.parse(REAL_INVENTORY);
        out.append("Parsed ").append(parsed.snapshot().quantities().size())
                .append(" items, warnings: ").append(parsed.warnings()).append("\n");
        for (Map.Entry<Long, Long> e : parsed.snapshot().quantities().entrySet()) {
            PiCommodity c = sde.getCommodity(e.getKey());
            out.append(String.format("  P%d %-28s %6d%n", tiers.tierOf(e.getKey()), c.name(), e.getValue()));
        }
        InventorySnapshot realInventory = parsed.snapshot();

        // ---------- Sustainable plans from real templates ----------
        SustainableProductionPlan fullChain = planOf(flowSolver, templateParser, extractor,
                "data/templates/FullChainIrd.json");
        SustainableProductionPlan hazmatBottleneck = planOf(flowSolver, templateParser, extractor,
                "data/templates/IrdHazmatBottleneck.json");
        ProductionBlock irdLegacyBlock = blockOf(templateParser, extractor,
                "data/templates/IntegrityResponseDrones.json");
        SustainableProductionPlan irdPlan = planOf(flowSolver, templateParser, extractor,
                "data/templates/IntegrityResponseDrones.json");

        out.append("\n==============================================================\n");
        out.append("2. SUSTAINABLE PLAN — FullChainIrd (P2->P3->P4, spec §8 example)\n");
        out.append("==============================================================\n");
        out.append("  Block duration: ").append(fullChain.blockDurationSeconds()).append("s\n");
        for (SustainableProductionPlan.GroupUtilization g : fullChain.facilityUtilizations()) {
            out.append(String.format("  %-28s x%-2d u=%-6s cycles/block=%d%n",
                    g.schematicName(), g.facilityCount(), g.utilization(),
                    g.cyclesPerFacilityPerBlock()));
        }
        for (SustainableProductionPlan.Bottleneck b : fullChain.bottlenecks()) {
            out.append(String.format("  BOTTLENECK %-24s capacity=%s/h demand=%s/h throttle=%s%n",
                    sde.getCommodity(b.typeId()).name(),
                    b.capacityPerHour(), b.demandAtFullPerHour(), b.throttle()));
        }
        out.append("  External per block (all P2): ");
        for (Map.Entry<Long, Long> e : fullChain.externalRequirementsPerBlock().entrySet()) {
            out.append(sde.getCommodity(e.getKey()).name()).append(" x").append(e.getValue()).append("  ");
        }
        out.append("\n  Final output per block: ");
        for (Map.Entry<Long, Long> e : fullChain.finalOutputsPerBlock().entrySet()) {
            out.append(sde.getCommodity(e.getKey()).name()).append(" x").append(e.getValue()).append("\n");
        }

        // ---------- 3. Scenario A: P3 = 0, P2 plenty ----------
        out.append("\n==============================================================\n");
        out.append("3. SCENARIO A — FullChainIrd, NO P3 stock, 20000 of each P2, 50000 m3\n");
        out.append("   Expected: 3 blocks x 54000s, 7200 of each P2, 360 IRD.\n");
        out.append("==============================================================\n");
        AllocationPlan planA = planner.plan(
                List.of(new PlanetRequest("Planet 1 (full chain)", fullChain, new BigDecimal("50000"))),
                p2Stock(20000L), sde::getCommodity, tiers::tierOf);
        appendPlan(out, planA);

        // ---------- 4. Scenario B: P3 stock changes nothing ----------
        out.append("\n==============================================================\n");
        out.append("4. SCENARIO B — same run + real P3 stock (GMB 1527/Hazmat 41/PV 688)\n");
        out.append("   Expected: identical result; P3 allocated 0, untouched.\n");
        out.append("==============================================================\n");
        Map<Long, Long> withP3 = new TreeMap<>(p2Stock(20000L).quantities());
        withP3.put(2348L, 1527L);
        withP3.put(2366L, 41L);
        withP3.put(9846L, 688L);
        AllocationPlan planB = planner.plan(
                List.of(new PlanetRequest("Planet 1 (full chain)", fullChain, new BigDecimal("50000"))),
                new InventorySnapshot(withP3), sde::getCommodity, tiers::tierOf);
        appendPlan(out, planB);

        // ---------- 5. Scenario C: one scarce P2 -> zero blocks ----------
        out.append("\n==============================================================\n");
        out.append("5. SCENARIO C — Oxides cut to 1000 (< 2400/block), rest plenty\n");
        out.append("   Expected: 0 blocks, Oxides is the limiting item.\n");
        out.append("==============================================================\n");
        Map<Long, Long> scarce = new TreeMap<>(p2Stock(20000L).quantities());
        scarce.put(OXIDES, 1000L);
        AllocationPlan planC = planner.plan(
                List.of(new PlanetRequest("Planet 1 (full chain)", fullChain, new BigDecimal("50000"))),
                new InventorySnapshot(scarce), sde::getCommodity, tiers::tierOf);
        appendPlan(out, planC);

        // ---------- 6. Scenario D: internal shortfall throttles ----------
        out.append("\n==============================================================\n");
        out.append("6. SCENARIO D — IrdHazmatBottleneck (Hazmat 48/h < 60/h demand),\n");
        out.append("   zero P3 stock, only the 3 Hazmat P2 stocked at 10000, 20000 m3\n");
        out.append("   Expected: 6 blocks, NO external Hazmat demand, 240 IRD.\n");
        out.append("==============================================================\n");
        InventorySnapshot hazmatP2Only = new InventorySnapshot(Map.of(
                3695L, 10000L, 9840L, 10000L, 3775L, 10000L));
        AllocationPlan planD = planner.plan(
                List.of(new PlanetRequest("Planet 1 (hazmat bottleneck)", hazmatBottleneck,
                        new BigDecimal("20000"))),
                hazmatP2Only, sde::getCommodity, tiers::tierOf);
        appendPlan(out, planD);

        // ---------- 7. Real-inventory regression: the old bug ----------
        out.append("\n==============================================================\n");
        out.append("7. REAL INVENTORY REGRESSION — full chain + 2 planets, AS-IS stock\n");
        out.append("   The old model zeroed planets on 'Hazmat 41 < 60'; in P2-only\n");
        out.append("   mode the plentiful P2 drives production instead.\n");
        out.append("==============================================================\n");
        AllocationPlan planReal = planner.plan(List.of(
                new PlanetRequest("Planet 1 (full chain)", fullChain, new BigDecimal("50000")),
                new PlanetRequest("Planet 2 (hazmat bottleneck)", hazmatBottleneck,
                        new BigDecimal("20000"))),
                realInventory, sde::getCommodity, tiers::tierOf);
        appendPlan(out, planReal);

        // ---------- 8. Single-planet regression vs Phase 1 LoadOptimizer ----------
        out.append("\n==============================================================\n");
        out.append("8. SINGLE-PLANET REGRESSION — pure P4 IRD template vs Phase 1\n");
        out.append("   IRD template @ 20000 m3, big P3 stock (P3 no longer constrains).\n");
        out.append("==============================================================\n");
        InventorySnapshot plenty = new InventorySnapshot(Map.of(
                2348L, 1_000_000_000L, 2366L, 1_000_000_000L, 9846L, 1_000_000_000L));
        RecommendedLoadPlan phase1 = new LoadOptimizer().optimize(
                irdLegacyBlock, new BigDecimal("20000"), sde::getCommodity);
        AllocationPlan single = planner.plan(
                List.of(new PlanetRequest("Planet 1", irdPlan, new BigDecimal("20000"))),
                plenty, sde::getCommodity, tiers::tierOf);
        PlanetAllocation p1 = single.planets().get(0);
        out.append("Phase 1 LoadOptimizer : blocks=").append(phase1.blockCount())
                .append(" runtime=").append(phase1.runtimeSeconds()).append("s used=")
                .append(phase1.usedCapacity().stripTrailingZeros().toPlainString()).append(" m3\n");
        out.append("MultiPlanetPlanner    : blocks=").append(p1.blockCount())
                .append(" runtime=").append(p1.runtimeSeconds()).append("s used=")
                .append(p1.usedCapacity().stripTrailingZeros().toPlainString()).append(" m3\n");
        out.append("Quantities identical  : ").append(phase1.materials().stream()
                .allMatch(m -> p1.quantitiesByTypeId().get(m.typeId()) == m.quantity())).append("\n");

        System.out.println(out);
        Files.writeString(Path.of("out/allocation-acceptance.txt"), out.toString());
        sde.close();
    }

    private static SustainableProductionPlan planOf(ProductionFlowSolver solver,
                                                    TemplateParser parser,
                                                    ProductionCapacityExtractor extractor,
                                                    String path) throws Exception {
        List<TemplateProductionFacility> facilities =
                extractor.extract(parser.parse(Files.readString(Path.of(path))));
        return solver.solve(facilities);
    }

    private static ProductionBlock blockOf(TemplateParser parser, ProductionCapacityExtractor extractor,
                                           String path) throws Exception {
        var template = parser.parse(Files.readString(Path.of(path)));
        return ProductionBlockCalculator.calculate(extractor.extract(template));
    }

    private static InventorySnapshot p2Stock(long each) {
        Map<Long, Long> stock = new TreeMap<>();
        for (long typeId : FULL_CHAIN_P2) {
            stock.put(typeId, each);
        }
        return new InventorySnapshot(stock);
    }

    private static void appendPlan(StringBuilder out, AllocationPlan plan) {
        for (PlanetAllocation p : plan.planets()) {
            out.append("\n--- ").append(p.name()).append(" ---\n");
            out.append("  Blocks: ").append(p.blockCount())
                    .append("  Runtime: ").append(RecommendedLoadPlan.formatRuntime(p.runtimeSeconds())).append("\n");
            List<MaterialAllocation> p2 = p.materialsOfTier(2);
            if (!p2.isEmpty()) {
                out.append("  P2 TO LOAD:\n");
                for (MaterialAllocation m : p2) {
                    out.append(String.format("    %-28s %6d  (%s m3)%n", m.name(), m.quantity(),
                            m.volume().stripTrailingZeros().toPlainString()));
                }
            }
            List<MaterialAllocation> p3 = p.materialsOfTier(3);
            if (!p3.isEmpty()) {
                out.append("  P3 TO LOAD (hauled, not stock-constrained):\n");
                for (MaterialAllocation m : p3) {
                    out.append(String.format("    %-28s %6d  (%s m3)%n", m.name(), m.quantity(),
                            m.volume().stripTrailingZeros().toPlainString()));
                }
            }
            if (p.materials().stream().allMatch(m -> m.quantity() == 0)) {
                out.append("  (no complete block could be allocated — P2 stock/capacity insufficient)\n");
            }
            out.append("  Used capacity: ").append(p.usedCapacity().stripTrailingZeros().toPlainString())
                    .append(" / ").append(p.request().capacity().stripTrailingZeros().toPlainString())
                    .append(" m3\n");
            if (!p.expectedOutputs().isEmpty()) {
                out.append("  Expected output: ");
                for (var o : p.expectedOutputs()) {
                    out.append(o.quantity()).append("x ").append(o.commodity().name()).append("; ");
                }
                out.append("\n");
            }
        }
        out.append("\n  GLOBAL INVENTORY REPORT (original -> allocated, remaining):\n");
        for (InventoryItemUsage u : plan.inventoryUsage()) {
            out.append(String.format("    P%d %-28s %6d -> %-6d, remaining %d%n",
                    u.tier(), u.name(), u.original(), u.allocated(), u.remaining()));
        }
    }
}
