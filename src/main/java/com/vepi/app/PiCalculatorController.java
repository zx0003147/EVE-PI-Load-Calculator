package com.vepi.app;

import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.InventoryItemUsage;
import com.vepi.allocation.MultiPlanetAllocationPlanner;
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
import com.vepi.load.LoadException;
import com.vepi.load.LoadOptimizer;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import com.vepi.template.PiTemplate;
import com.vepi.template.TemplateParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Application state and the single orchestration seam between the UI and the
 * Phase 0/1 domain layers.
 *
 * <p>The UI never calls TemplateParser / SdeRepository / LoadOptimizer directly —
 * it collects input, calls this controller and renders the returned records.
 * The controller deliberately exposes only the Phase 1 formal models
 * ({@link ProductionBlock} + {@link RecommendedLoadPlan}); the Phase 0 hourly
 * set-difference result is NOT surfaced to the UI.
 *
 * <p>The primary template input is pasted TEXT ({@link #loadTemplateText(String)});
 * {@link #loadTemplate(Path)} is a thin wrapper that reads the file and delegates
 * to the same single parse-and-resolve pipeline. No file-system concept leaks
 * into the core calculation layer.
 *
 * <p>Methods are {@code synchronized} because template loading runs on a
 * SwingWorker thread while calculation runs on the EDT; the underlying
 * {@link SdeRepository} (JDBC connection + HashMap cache) is not itself
 * thread-safe for concurrent access.
 */
public final class PiCalculatorController implements AutoCloseable {

    /** Everything the UI shows right after a template is loaded (before any capacity). */
    public record TemplateSummary(
            String displayName,
            String fileName,
            int facilityCount,
            List<String> configurationLines,
            List<String> outputLines,
            long basePeriodSeconds,
            BigDecimal blockVolumeM3) {
    }

    /**
     * A planet template loaded from pasted text: the display summary plus the
     * resolved {@link SustainableProductionPlan} — the P2-only sustainable
     * production model the multi-planet allocator consumes. Stateless result —
     * the UI keeps one per planet; this method never touches the single-template
     * state used by the legacy {@link #calculate(String)} path.
     */
    public record PlanetTemplate(TemplateSummary summary, SustainableProductionPlan plan) {
    }

    /** Parsed inventory plus the tier counts the UI shows ("12 items, 9 P2, 3 P3"). */
    public record InventoryStatus(
            InventorySnapshot snapshot,
            List<String> warnings,
            int itemCount,
            int p2Count,
            int p3Count) {
    }

    /**
     * Why a planet received zero production blocks: which inventory items were
     * too scarce (even after the whole allocation run) and/or whether the planet's
     * capacity is below one block's volume. Pure query over the finished plan —
     * no bottleneck logic is reimplemented in the UI.
     */
    public record ZeroBlockExplanation(BigDecimal blockVolume, BigDecimal capacity,
                                        List<LimitingItem> limitingItems) {

        public ZeroBlockExplanation {
            limitingItems = limitingItems == null ? List.of() : List.copyOf(limitingItems);
        }

        /** One inventory item that could not cover a single block. */
        public record LimitingItem(PiCommodity commodity, long available, long requiredPerBlock) {
        }

        public boolean capacityTooSmall() {
            return capacity != null && capacity.compareTo(blockVolume) < 0;
        }
    }

    private static final String PASTED = "(pasted template)";

    private final SdeRepository sde;
    private final TemplateParser parser = new TemplateParser();
    private final InventoryTextParser inventoryParser;
    private final PiTierResolver tiers;
    private final MultiPlanetAllocationPlanner planner = new MultiPlanetAllocationPlanner();
    private final ProductionFlowSolver flowSolver = new ProductionFlowSolver();

    private PiTemplate currentTemplate;
    private String currentTemplateName;
    private ProductionBlock currentBlock;
    private TemplateSummary currentSummary;

    public PiCalculatorController(Path sdeDbPath) {
        this.sde = new SdeRepository(sdeDbPath.toString());
        this.inventoryParser = new InventoryTextParser(sde::findCommodityByName);
        this.tiers = new PiTierResolver(sde);
    }

    /**
     * Primary entry: parse + resolve + build the production block for template
     * JSON text exactly as pasted by the user (surrounding whitespace tolerated).
     */
    public synchronized TemplateSummary loadTemplateText(String templateText) {
        PiTemplate tpl = parser.parse(templateText);
        return doLoad(tpl, null);
    }

    /**
     * Convenience wrapper for file input: reads the file and runs the exact same
     * pipeline as {@link #loadTemplateText(String)}.
     */
    public synchronized TemplateSummary loadTemplate(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new com.vepi.template.TemplateException.InvalidTemplate(
                    "Cannot read template file " + file, e);
        }
        return doLoad(parser.parse(text), file.getFileName().toString());
    }

    /** Forgets the currently loaded template (UI "Clear"). */
    public synchronized void clearTemplate() {
        this.currentTemplate = null;
        this.currentTemplateName = null;
        this.currentBlock = null;
        this.currentSummary = null;
    }

    private TemplateSummary doLoad(PiTemplate tpl, String fileName) {
        Resolved resolved = resolve(tpl, fileName);
        this.currentTemplate = tpl;
        this.currentTemplateName = resolved.summary().displayName();
        this.currentBlock = resolved.block();
        this.currentSummary = resolved.summary();
        return currentSummary;
    }

    /** Parsed template -> facilities + production block + display summary (pure). */
    private record Resolved(TemplateSummary summary, ProductionBlock block) {
    }

    private Resolved resolve(PiTemplate tpl, String fileName) {
        List<TemplateProductionFacility> facilities =
                new ProductionCapacityExtractor(sde).extract(tpl);
        if (facilities.isEmpty()) {
            throw new com.vepi.template.TemplateException.UnsupportedTemplate(
                    "template contains no production facilities");
        }

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        String displayName = (tpl.Cmt != null && !tpl.Cmt.isBlank())
                ? tpl.Cmt.trim()
                : (fileName != null ? fileName : PASTED);

        List<String> configs = facilities.stream()
                .collect(Collectors.groupingBy(
                        f -> f.schematic().name() + " (cycle " + f.schematic().cycleTimeSeconds() + "s)",
                        Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey())
                .sorted()
                .toList();

        List<String> outputs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : block.netOutputs().entrySet()) {
            PiCommodity c = sde.getCommodity(e.getKey());
            outputs.add(c.name() + " x" + e.getValue() + " per production block");
        }

        TemplateSummary summary = new TemplateSummary(displayName,
                fileName != null ? fileName : PASTED, facilities.size(), configs, outputs,
                block.basePeriodSeconds(), blockVolume(block.externalRequirements()));
        return new Resolved(summary, block);
    }

    // ---- multi-planet allocation API (inventory-driven model, Phase 2 rev 2) ----

    /**
     * Parses pasted inventory text ("Name quantity" per line) against the SDE.
     * Pure call — the returned snapshot is handed back by the UI in
     * {@link #calculateAllocation}; no controller state is kept.
     */
    public synchronized InventoryStatus parseInventory(String inventoryText) {
        InventoryParseResult result = inventoryParser.parse(inventoryText);
        int p2 = 0;
        int p3 = 0;
        for (long typeId : result.snapshot().quantities().keySet()) {
            int tier = tiers.tierOf(typeId);
            if (tier == 2) p2++;
            else if (tier == 3) p3++;
        }
        return new InventoryStatus(result.snapshot(), result.warnings(),
                result.snapshot().quantities().size(), p2, p3);
    }

    /**
     * Loads one planet's template from pasted text and resolves its
     * <b>P2-only sustainable production plan</b> from the SDE: internal P3
     * intermediates are balanced internally (upstream shortfalls throttle the
     * chain instead of becoming external requirements), and the external load
     * is what the player actually imports (the P2 items for a full chain).
     * Does not touch the single-template legacy state.
     */
    public synchronized PlanetTemplate loadPlanetTemplate(String templateText) {
        PiTemplate tpl = parser.parse(templateText);
        List<TemplateProductionFacility> facilities =
                new ProductionCapacityExtractor(sde).extract(tpl);
        if (facilities.isEmpty()) {
            throw new com.vepi.template.TemplateException.UnsupportedTemplate(
                    "template contains no production facilities");
        }
        SustainableProductionPlan plan = flowSolver.solve(facilities);
        return new PlanetTemplate(planetSummary(tpl, null, facilities, plan), plan);
    }

    /** Display summary for a planet template, based on the sustainable plan. */
    private TemplateSummary planetSummary(PiTemplate tpl, String fileName,
                                          List<TemplateProductionFacility> facilities,
                                          SustainableProductionPlan plan) {
        String displayName = (tpl.Cmt != null && !tpl.Cmt.isBlank())
                ? tpl.Cmt.trim()
                : (fileName != null ? fileName : PASTED);

        List<String> configs = facilities.stream()
                .collect(Collectors.groupingBy(
                        f -> f.schematic().name() + " (cycle " + f.schematic().cycleTimeSeconds() + "s)",
                        Collectors.counting()))
                .entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey())
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));

        // Sustainable utilization per facility group — the honest "how hard can
        // this template actually run on internal P2" answer.
        for (SustainableProductionPlan.GroupUtilization g : plan.facilityUtilizations()) {
            configs.add(g.schematicName() + " x" + g.facilityCount()
                    + " runs at " + g.utilization().toPercent()
                    + " (" + g.cyclesPerFacilityPerBlock() + " cycles per "
                    + plan.blockDurationSeconds() + "s block)");
        }
        for (SustainableProductionPlan.Bottleneck b : plan.bottlenecks()) {
            configs.add("Bottleneck: " + sde.getCommodity(b.typeId()).name()
                    + " — internal capacity " + ratePerHour(b.capacityPerHour())
                    + "/h < chain demand " + ratePerHour(b.demandAtFullPerHour())
                    + "/h, chain throttled to " + b.throttle().toPercent());
        }

        List<String> outputs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : plan.finalOutputsPerBlock().entrySet()) {
            PiCommodity c = sde.getCommodity(e.getKey());
            outputs.add(c.name() + " x" + e.getValue() + " per sustainable block ("
                    + plan.blockDurationSeconds() + "s)");
        }

        return new TemplateSummary(displayName,
                fileName != null ? fileName : PASTED, facilities.size(), configs, outputs,
                plan.blockDurationSeconds(), blockVolume(plan.externalRequirementsPerBlock()));
    }

    /** Exact already-per-hour rate as a short decimal string (display only). */
    private static String ratePerHour(com.vepi.flow.Fraction ratePerHour) {
        return ratePerHour.toBigDecimal(2).stripTrailingZeros().toPlainString();
    }

    /**
     * Runs the deterministic fair multi-planet allocation over the user's real
     * inventory in <b>P2-only mode</b>: only tier-2 external inputs consume the
     * shared stock. The UI only collects input and renders the returned plan —
     * fairness, inventory subtraction and P2/P3 classification all live in the
     * {@link MultiPlanetAllocationPlanner}.
     */
    public synchronized AllocationPlan calculateAllocation(InventorySnapshot inventory,
                                                            List<PlanetRequest> planets) {
        return planner.plan(planets, inventory, sde::getCommodity, tiers::tierOf);
    }

    /**
     * Explains (as a pure query over the finished plan) why a planet got zero
     * blocks: <b>P2</b> inventory items that were too scarce even after
     * allocation, and/or a capacity below one block's volume. Higher-tier
     * stock never limits production in P2-only mode.
     */
    public synchronized ZeroBlockExplanation explainZeroBlocks(PlanetRequest request,
                                                                AllocationPlan plan) {
        Map<Long, Long> remaining = plan.inventoryUsage().stream()
                .collect(Collectors.toMap(InventoryItemUsage::typeId, InventoryItemUsage::remaining));

        List<ZeroBlockExplanation.LimitingItem> limiting = new ArrayList<>();
        for (Map.Entry<Long, Long> e : request.plan().externalRequirementsPerBlock().entrySet()) {
            if (tiers.tierOf(e.getKey()) != 2) {
                continue;   // P2-only: only P2 stock can be limiting
            }
            long available = remaining.getOrDefault(e.getKey(), 0L);
            if (available < e.getValue()) {
                limiting.add(new ZeroBlockExplanation.LimitingItem(
                        sde.getCommodity(e.getKey()), available, e.getValue()));
            }
        }
        limiting.sort(Comparator.comparing(l -> l.commodity().name(), String.CASE_INSENSITIVE_ORDER));
        return new ZeroBlockExplanation(
                blockVolume(request.plan().externalRequirementsPerBlock()),
                request.capacity(), limiting);
    }

    /**
     * Display-ready bottleneck notes for one sustainable plan (names resolved
     * via the SDE), e.g. "Hazmat Detection Systems — internal capacity 48/h,
     * chain throttled to 80%". The UI renders them verbatim; it never resolves
     * commodity names itself.
     */
    public synchronized List<String> bottleneckNotes(SustainableProductionPlan plan) {
        List<String> notes = new ArrayList<>();
        for (SustainableProductionPlan.Bottleneck b : plan.bottlenecks()) {
            notes.add(sde.getCommodity(b.typeId()).name()
                    + " — internal capacity " + ratePerHour(b.capacityPerHour())
                    + "/h, chain throttled to " + b.throttle().toPercent());
        }
        return notes;
    }

    public synchronized boolean isTemplateLoaded() {
        return currentBlock != null;
    }

    /**
     * SDE commodity lookup for sibling features (e.g. the Balance Inventory
     * controller) that render names/volumes without owning an SdeRepository.
     * Callers must synchronize externally or copy on a worker thread — the
     * same rule every UI seam already follows for this controller.
     */
    public synchronized PiCommodity commodityOf(long typeId) {
        return sde.getCommodity(typeId);
    }

    /** Tier lookup via the shared SDE (see {@link #commodityOf}). */
    public synchronized int tierOf(long typeId) {
        return tiers.tierOf(typeId);
    }

    /** The block volume (minimum capacity for one production block), if a template is loaded. */
    public synchronized Optional<BigDecimal> blockVolume() {
        return currentSummary == null
                ? Optional.empty()
                : Optional.of(currentSummary.blockVolumeM3());
    }

    /**
     * Compute the balanced load plan for the currently loaded template.
     *
     * @param capacityText user-typed capacity string in m3
     * @throws IllegalStateException      if no template is loaded
     * @throws LoadException.InvalidCapacity if the text is not a valid capacity
     */
    public synchronized RecommendedLoadPlan calculate(String capacityText) {
        if (currentBlock == null) {
            throw new IllegalStateException("no template loaded");
        }
        BigDecimal capacity = LoadOptimizer.parseCapacity(capacityText);
        return new LoadOptimizer().optimize(currentBlock, capacity, sde::getCommodity);
    }

    /**
     * UI-friendly capacity validation: returns an error message for invalid
     * input, or empty when the value can be used for {@link #calculate}.
     */
    public static Optional<String> validateCapacity(String text) {
        try {
            LoadOptimizer.parseCapacity(text);
            return Optional.empty();
        } catch (LoadException.InvalidCapacity e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Exact total volume of one production block's external requirements. */
    private BigDecimal blockVolume(Map<Long, Long> externalRequirements) {
        BigDecimal v = BigDecimal.ZERO;
        for (Map.Entry<Long, Long> e : externalRequirements.entrySet()) {
            v = v.add(sde.getCommodity(e.getKey()).volume()
                    .multiply(BigDecimal.valueOf(e.getValue())));
        }
        return v;
    }

    public synchronized String currentTemplateName() {
        return currentTemplateName;
    }

    @Override
    public synchronized void close() {
        sde.close();
    }
}
