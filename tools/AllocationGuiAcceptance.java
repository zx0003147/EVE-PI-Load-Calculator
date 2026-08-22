package com.vepi.ui;

import com.vepi.allocation.InventoryItemUsage;
import com.vepi.app.PiCalculatorController;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.ui.AllocationFrame;
import com.vepi.ui.AllocationResultPanel;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * P2-only sustainable-production GUI acceptance: drives the REAL
 * AllocationFrame through the exact render methods the SwingWorkers call —
 * the same pipeline a user reaches by pasting inventory, pasting templates,
 * entering capacities and clicking Calculate Allocation.
 *
 * Scenario 1 (the bug that started this revision): real 12-line inventory
 *   (Hazmat = 41) + the full P2->P3->P4 chain -> the planet now PRODUCES
 *   (3 blocks @ 50000 m3); the old model wrongly reported 0 blocks.
 * Scenario 2: one scarce P2 (Oxides 1000 < 2400/block) -> honest 0 blocks
 *   with Oxides as the limiting item (P3 scarcity can never do this).
 * Scenario 3: two planets sharing the real P2 stock.
 */
public class AllocationGuiAcceptance {

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

    private static PiCalculatorController controller;
    private static AllocationFrame frame;
    private static String fullChainJson;
    private static String hazmatBottleneckJson;

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();

        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        fullChainJson = Files.readString(Path.of("data/templates/FullChainIrd.json"));
        hazmatBottleneckJson = Files.readString(Path.of("data/templates/IrdHazmatBottleneck.json"));
        AtomicReference<AllocationFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new AllocationFrame(controller)));
        frame = ref.get();

        PiCalculatorController.InventoryStatus realStatus =
                controller.parseInventory(REAL_INVENTORY);

        // ==== Scenario 1: real inventory (Hazmat 41) — full chain now produces ====
        out.append("==============================================================\n");
        out.append("SCENARIO 1 — GUI ACCEPTANCE: real inventory (Hazmat = 41)\n");
        out.append("   Planet 1: FULL CHAIN P2->P3->P4 @ 50000 m3\n");
        out.append("   Old model: 0 blocks (dishonest). P2-only model: 3 blocks,\n");
        out.append("   external load = the 9 P2 inputs only, Hazmat is internal.\n");
        out.append("==============================================================\n");
        out.append("Inventory status: ").append(realStatus.itemCount()).append(" items, ")
                .append(realStatus.p2Count()).append(" P2, ")
                .append(realStatus.p3Count()).append(" P3, warnings=")
                .append(realStatus.warnings()).append("\n");
        out.append("UI status text:\n").append(indent(renderInventoryStatus(realStatus), "  "));

        AllocationFrame.AllocationRun run1 =
                setupAndCompute(realStatus.snapshot(), "50000", 1, fullChainJson);
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run1));
        out.append(renderPlan(run1));

        // ==== Scenario 2: one scarce P2 — honest zero blocks ====
        Map<Long, Long> scarce = new TreeMap<>(realStatus.snapshot().quantities());
        scarce.put(2317L, 1000L);   // Oxides < 2400 per sustainable block

        out.append("\n==============================================================\n");
        out.append("SCENARIO 2 — GUI ACCEPTANCE: Oxides cut to 1000 (< 2400/block)\n");
        out.append("   Same full-chain planet -> 0 blocks, Oxides is the limiting\n");
        out.append("   item. P3 stock amounts can never zero a planet anymore.\n");
        out.append("==============================================================\n");

        AllocationFrame.AllocationRun run2 =
                setupAndCompute(new InventorySnapshot(scarce), "50000", 1, fullChainJson);
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run2));
        out.append(renderPlan(run2));

        // ==== Scenario 3: two planets sharing the real P2 stock ====
        out.append("\n==============================================================\n");
        out.append("SCENARIO 3 — GUI ACCEPTANCE: two planets, real inventory as-is\n");
        out.append("   Planet 1: full chain @ 50000 m3\n");
        out.append("   Planet 2: hazmat-bottleneck chain @ 20000 m3\n");
        out.append("==============================================================\n");

        AllocationFrame.AllocationRun run3 = setupAndComputeTwoPlanets(
                realStatus.snapshot(), "50000", fullChainJson, "20000", hazmatBottleneckJson);
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run3));
        out.append(renderPlan(run3));

        // AllocationFrame is an embeddable JPanel now — nothing to dispose.
        controller.close();

        System.out.println(out);
        Files.writeString(Path.of("out/allocation-gui-acceptance.txt"), out.toString());
    }

    /** Exactly what the frame does on Calculate Allocation for one planet. */
    private static AllocationFrame.AllocationRun setupAndCompute(InventorySnapshot inventory,
                                                                 String capacity,
                                                                 int planetCount,
                                                                 String templateJson) throws Exception {
        SwingUtilities.invokeAndWait(frame::resetState);
        SwingUtilities.invokeAndWait(() -> frame.inventoryLoaded(
                new PiCalculatorController.InventoryStatus(inventory, List.of(),
                        inventory.quantities().size(), 0, 3)));
        for (int i = 0; i < planetCount; i++) {
            final int index = i;
            SwingUtilities.invokeAndWait(() -> frame.addPlanet());
            long id = frame.planetPanels.get(index).id();
            SwingUtilities.invokeAndWait(() -> {
                frame.planetTemplateLoaded(id, controller.loadPlanetTemplate(templateJson));
                frame.planetPanels.get(index).capacityPanel.setCapacityText(capacity);
            });
        }
        return frame.computeRun(inventory, frame.planetPanels);
    }

    /** Two planets with different templates and capacities. */
    private static AllocationFrame.AllocationRun setupAndComputeTwoPlanets(
            InventorySnapshot inventory, String capacity1, String template1,
            String capacity2, String template2) throws Exception {
        SwingUtilities.invokeAndWait(frame::resetState);
        SwingUtilities.invokeAndWait(() -> frame.inventoryLoaded(
                new PiCalculatorController.InventoryStatus(inventory, List.of(),
                        inventory.quantities().size(), 0, 3)));
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        long id1 = frame.planetPanels.get(0).id();
        SwingUtilities.invokeAndWait(() -> {
            frame.planetTemplateLoaded(id1, controller.loadPlanetTemplate(template1));
            frame.planetPanels.get(0).capacityPanel.setCapacityText(capacity1);
        });
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        long id2 = frame.planetPanels.get(1).id();
        SwingUtilities.invokeAndWait(() -> {
            frame.planetTemplateLoaded(id2, controller.loadPlanetTemplate(template2));
            frame.planetPanels.get(1).capacityPanel.setCapacityText(capacity2);
        });
        return frame.computeRun(inventory, frame.planetPanels);
    }

    private static String renderInventoryStatus(PiCalculatorController.InventoryStatus s) {
        return "Inventory loaded\n\n" + s.itemCount() + " items\n"
                + s.p2Count() + " P2\n" + s.p3Count() + " P3\n";
    }

    /** Reads back what the result panel actually rendered. */
    private static String renderPlan(AllocationFrame.AllocationRun run) throws Exception {
        StringBuilder sb = new StringBuilder();
        final List<AllocationResultPanel.PlanetSection> sections =
                List.copyOf(frame.resultPanel.sections);
        for (AllocationResultPanel.PlanetSection section : sections) {
            var alloc = section.view.allocation();
            sb.append("\n").append(alloc.name().toUpperCase()).append(" — ")
                    .append(section.view.templateDisplayName()).append('\n');
            sb.append("  Blocks: ").append(alloc.blockCount())
                    .append("   Runtime: ").append(Formats.runtime(alloc.runtimeSeconds()))
                    .append('\n');
            if (!section.notice.getText().isBlank()) {
                sb.append(indent(section.notice.getText(), "  ")).append('\n');
            }
            appendTable(sb, "  P2 TO LOAD:", section.p2Model);
            appendTable(sb, "  P3 TO LOAD:", section.p3Model);
            sb.append(indent(section.summary.getText(), "  ")).append('\n');
            sb.append("  Copy Planet Load ->\n")
                    .append(indent(CopyText.planetLoad(alloc), "    ")).append('\n');
        }
        sb.append("\n  REMAINING INVENTORY (Material | Original | Allocated | Remaining):\n");
        for (InventoryItemUsage u : run.plan().inventoryUsage()) {
            sb.append(String.format("    P%d %-28s %7d | %7d | %7d%n",
                    u.tier(), u.name(), u.original(), u.allocated(), u.remaining()));
        }
        return sb.toString();
    }

    private static void appendTable(StringBuilder sb, String title,
                                    AllocationResultPanel.MaterialTableModel model) {
        if (model.getRowCount() == 0) {
            return;
        }
        sb.append("  ").append(title).append('\n');
        for (int r = 0; r < model.getRowCount(); r++) {
            sb.append(String.format("    %-28s %10s  %12s%n",
                    model.getValueAt(r, 0), model.getValueAt(r, 1), model.getValueAt(r, 2)));
        }
    }

    private static String indent(String text, String pad) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(pad).append(line).append('\n');
        }
        return sb.toString();
    }
}
