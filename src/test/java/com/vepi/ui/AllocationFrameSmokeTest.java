package com.vepi.ui;

import com.vepi.allocation.PlanetAllocation;
import com.vepi.app.PiCalculatorController;
import com.vepi.inventory.InventorySnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI smoke test for the allocation workflow: builds the real frame and drives
 * the exact render methods the SwingWorkers call (no window shown, same code
 * path as the visible GUI). Skipped on headless environments.
 *
 * <p>Rewritten for P2-only sustainable production: the full-chain fixture
 * (P2 -> P3 -> P4) renders 9 P2 rows plus a bottleneck note, and scarce P2 —
 * never scarce P3 — produces the zero-block notice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllocationFrameSmokeTest {

    private static PiCalculatorController controller;
    private static AllocationFrame frame;
    private static String irdJson;
    private static String fullChainJson;
    private static String multiJson;

    // GMB 2348 / Hazmat 2366 / PV 9846; the 9 P2 inputs of the full chain.
    private static final long GMB = 2348L, HAZMAT = 2366L, PV = 9846L;
    private static final long BIOCELLS = 2329L, OXIDES = 2317L, SUPERCONDUCTORS = 9838L;
    private static final long POLYTEXTILES = 3695L, TRANSMITTER = 9840L, VIRAL_AGENT = 3775L;
    private static final long MECH_PARTS = 3689L, MINI_ELECTRONICS = 9842L, SUPERPLASTICS = 2312L;

    private static final InventorySnapshot BIG_P3 = new InventorySnapshot(Map.of(
            GMB, 100_000L, HAZMAT, 100_000L, PV, 100_000L));

    /** 20000 of each of the 9 P2 inputs (full-chain scenario A stock). */
    private static InventorySnapshot p2Stock(long each) {
        return new InventorySnapshot(Map.of(
                BIOCELLS, each, OXIDES, each, SUPERCONDUCTORS, each,
                POLYTEXTILES, each, TRANSMITTER, each, VIRAL_AGENT, each,
                MECH_PARTS, each, MINI_ELECTRONICS, each, SUPERPLASTICS, each));
    }

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "no display available — GUI smoke test skipped");
        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        irdJson = java.nio.file.Files.readString(
                Path.of("data/templates/IntegrityResponseDrones.json"));
        fullChainJson = java.nio.file.Files.readString(
                Path.of("data/templates/FullChainIrd.json"));
        multiJson = java.nio.file.Files.readString(
                Path.of("data/templates/MultiStageGmbDeficit.json"));
        AtomicReference<AllocationFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new AllocationFrame(controller)));
        frame = ref.get();
    }

    @org.junit.jupiter.api.BeforeEach
    void resetFrameState() throws Exception {
        // One shared frame across tests (PER_CLASS) — start every test clean.
        SwingUtilities.invokeAndWait(frame::resetState);
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(), "reset must disable Calculate"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        // AllocationFrame is now an embeddable JPanel (AppFrame tab 1) —
        // nothing to dispose; the controller's SDE handle is the only resource.
        if (controller != null) {
            controller.close();
        }
    }

    // ---- enablement state machine (spec F) ----

    @Test
    void calculateDisabledInitially_noInventoryNoPlanets() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled()));
    }

    @Test
    void inventoryAlone_isNotEnough_withoutPlanets() throws Exception {
        SwingUtilities.invokeAndWait(() ->
                frame.inventoryLoaded(status(BIG_P3)));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(), "no planets yet"));
    }

    @Test
    void readinessRequires_inventory_templateLoaded_and_validCapacity() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.inventoryLoaded(status(BIG_P3)));
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(), "template + capacity missing"));

        PlanetPanel planet = frame.planetPanels.get(0);
        SwingUtilities.invokeAndWait(() ->
                frame.planetTemplateLoaded(planet.id(), controller.loadPlanetTemplate(irdJson)));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(), "capacity still invalid"));

        SwingUtilities.invokeAndWait(() -> planet.capacityPanel.setCapacityText("abc"));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(), "'abc' is not a capacity"));

        SwingUtilities.invokeAndWait(() -> planet.capacityPanel.setCapacityText("20000"));
        SwingUtilities.invokeAndWait(() ->
                assertTrue(frame.calculateButton.isEnabled(), "all conditions met"));
    }

    // ---- rendering: pure P4 template keeps the old P3 grouping + numbers ----

    @Test
    void singlePlanetIrdRendersP3GroupingAndPhase1Numbers() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        PlanetPanel planet = frame.planetPanels.get(0);
        SwingUtilities.invokeAndWait(() -> {
            planet.capacityPanel.setCapacityText("20000");
            frame.planetTemplateLoaded(planet.id(), controller.loadPlanetTemplate(irdJson));
        });

        AllocationFrame.AllocationRun run = frame.computeRun(BIG_P3, frame.planetPanels);
        assertEquals(46L, run.plan().planets().get(0).blockCount());
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, frame.resultPanel.sections.size());
            AllocationResultPanel.PlanetSection section = frame.resultPanel.sections.get(0);

            // IRD's external inputs are all P3: P2 section empty, P3 section has 3 rows.
            assertEquals(0, section.p2Model.getRowCount());
            assertEquals(3, section.p3Model.getRowCount());
            assertEquals("Gel-Matrix Biopaste", section.p3Model.getValueAt(0, 0));
            assertEquals("2,208", section.p3Model.getValueAt(0, 1));
            assertEquals("6,624 m3", section.p3Model.getValueAt(0, 2));

            assertTrue(section.summary.getText().contains("Runtime: 1d 22h"));
            assertTrue(section.summary.getText().contains("Production blocks: 46"));
            assertTrue(section.summary.getText().contains("Used capacity: 19,872 m3"));
            assertTrue(section.summary.getText().contains("Remaining capacity: 128 m3"));
            assertTrue(section.summary.getText().contains("Expected output: Integrity Response Drones x368"));
            assertTrue(section.notice.getText().isBlank(), "46 blocks is not a zero-block case");
        });
    }

    // ---- rendering: full-chain template shows 9 P2 rows + bottleneck note ----

    @Test
    void fullChainPlanetRendersP2GroupAndBottleneckNote() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        PlanetPanel planet = frame.planetPanels.get(0);
        SwingUtilities.invokeAndWait(() -> {
            planet.capacityPanel.setCapacityText("50000");
            frame.planetTemplateLoaded(planet.id(), controller.loadPlanetTemplate(fullChainJson));
        });

        // Scenario A: P3 stock absent entirely, P2 plentiful -> 3 blocks.
        AllocationFrame.AllocationRun run = frame.computeRun(p2Stock(20000L), frame.planetPanels);
        assertEquals(3L, run.plan().planets().get(0).blockCount());
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));

        SwingUtilities.invokeAndWait(() -> {
            AllocationResultPanel.PlanetSection section = frame.resultPanel.sections.get(0);

            // The external load is exactly the 9 P2 inputs (name-sorted) — no P3 row.
            assertEquals(9, section.p2Model.getRowCount(), "9 P2 inputs of the full chain");
            assertEquals(0, section.p3Model.getRowCount(), "P3 is internal");
            assertEquals("Biocells", section.p2Model.getValueAt(0, 0));
            assertEquals("7,200", section.p2Model.getValueAt(0, 1), "3 blocks x 2400");
            for (int row = 0; row < section.p2Model.getRowCount(); row++) {
                if ("Oxides".equals(section.p2Model.getValueAt(row, 0))) {
                    assertEquals("7,200", section.p2Model.getValueAt(row, 1),
                            "3 blocks x 2400 for Oxides");
                }
            }

            assertTrue(section.notice.getText().isBlank(), "plenty of P2 -> no notice");

            String summary = section.summary.getText();
            assertTrue(summary.contains("Runtime: 1d 21h"), summary);      // 162000s
            assertTrue(summary.contains("Production blocks: 3"), summary);
            assertTrue(summary.contains("Used capacity: 48,600 m3"), summary);
            assertTrue(summary.contains("Remaining capacity: 1,400 m3"), summary);
            assertTrue(summary.contains("Expected output: Integrity Response Drones x360"), summary);
            // The internal Hazmat bottleneck is surfaced as a note.
            assertTrue(summary.contains("Bottleneck: Hazmat Detection Systems"), summary);
            assertTrue(summary.contains("80%"), summary);
        });
    }

    @Test
    void multiStagePlanetRendersP2AndP3GroupsSeparately() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        PlanetPanel planet = frame.planetPanels.get(0);

        // Derive the stock from the template's own external requirements
        // (100 blocks' worth) — no hand-copied typeIDs. In P2-only mode the
        // plan's externals are the 3 P2 inputs plus the 2 P3 items the
        // template cannot produce itself.
        PiCalculatorController.PlanetTemplate multi = controller.loadPlanetTemplate(multiJson);
        Map<Long, Long> stock = new TreeMap<>();
        multi.plan().externalRequirementsPerBlock().forEach((typeId, perBlock) ->
                stock.put(typeId, perBlock * 100));
        InventorySnapshot stockOf100Blocks = new InventorySnapshot(stock);

        SwingUtilities.invokeAndWait(() -> {
            planet.capacityPanel.setCapacityText("18000");
            frame.planetTemplateLoaded(planet.id(), multi);
        });

        AllocationFrame.AllocationRun run = frame.computeRun(stockOf100Blocks, frame.planetPanels);
        PlanetAllocation allocation = run.plan().planets().get(0);
        assertTrue(allocation.blockCount() > 0);
        long p2Count = allocation.materials().stream().filter(m -> m.tier() == 2).count();
        long p3Count = allocation.materials().stream().filter(m -> m.tier() == 3).count();
        assertEquals(3, p2Count, "multi-stage template needs 3 P2 inputs");
        assertEquals(2, p3Count, "GMB deficit + one more P3 stay external (displayed, not stocked)");

        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));

        SwingUtilities.invokeAndWait(() -> {
            AllocationResultPanel.PlanetSection section = frame.resultPanel.sections.get(0);
            assertEquals(p2Count, section.p2Model.getRowCount(), "P2 group separate from P3");
            assertEquals(p3Count, section.p3Model.getRowCount());
        });
    }

    // ---- zero blocks are a normal result (scarce P2, not scarce P3) ----

    @Test
    void zeroBlockPlanetRendersExplanatoryNotice_forScarceP2() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        PlanetPanel planet = frame.planetPanels.get(0);
        SwingUtilities.invokeAndWait(() -> {
            planet.capacityPanel.setCapacityText("50000");
            frame.planetTemplateLoaded(planet.id(), controller.loadPlanetTemplate(fullChainJson));
        });

        // Scenario C: Oxides cut to 100 (< 2400 per block) -> zero blocks with
        // Oxides as the limiting item. P3 scarcity can never do this anymore.
        Map<Long, Long> stock = new TreeMap<>(p2Stock(20000L).quantities());
        stock.put(OXIDES, 100L);
        AllocationFrame.AllocationRun run =
                frame.computeRun(new InventorySnapshot(stock), frame.planetPanels);
        assertEquals(0L, run.plan().planets().get(0).blockCount());
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));

        SwingUtilities.invokeAndWait(() -> {
            AllocationResultPanel.PlanetSection section = frame.resultPanel.sections.get(0);
            String notice = section.notice.getText();
            assertTrue(notice.contains("No complete production block"),
                    () -> "notice was: " + notice);
            assertTrue(notice.contains("Oxides"), () -> notice);
            assertTrue(notice.contains("available: 100"), () -> notice);
            assertTrue(notice.contains("required for one block: 2,400"), () -> notice);
        });
    }

    @Test
    void zeroP3Stock_fullChainPlanetStillProduces() throws Exception {
        // The regression the UI acceptance caught: the real inventory's
        // "Hazmat 41" used to zero the full-chain planet. Now P3 stock simply
        // does not participate — the planet produces off its P2 supply.
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        PlanetPanel planet = frame.planetPanels.get(0);
        SwingUtilities.invokeAndWait(() -> {
            planet.capacityPanel.setCapacityText("50000");
            frame.planetTemplateLoaded(planet.id(), controller.loadPlanetTemplate(fullChainJson));
        });

        Map<Long, Long> realLike = new TreeMap<>(p2Stock(20000L).quantities());
        realLike.put(GMB, 1527L);
        realLike.put(HAZMAT, 41L);      // the exact value that used to break it
        realLike.put(PV, 688L);

        AllocationFrame.AllocationRun run =
                frame.computeRun(new InventorySnapshot(realLike), frame.planetPanels);
        assertEquals(3L, run.plan().planets().get(0).blockCount(),
                "Hazmat 41 must not zero the planet — P3 is internal in P2-only mode");
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));
        SwingUtilities.invokeAndWait(() ->
                assertTrue(frame.resultPanel.sections.get(0).notice.getText().isBlank()));
    }

    // ---- planet removal renumbers and keeps order stable ----

    @Test
    void removePlanet_renumbersTheRemainingOne() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        SwingUtilities.invokeAndWait(() -> frame.addPlanet());
        assertEquals(2, frame.planetPanels.size());

        long firstId = frame.planetPanels.get(0).id();
        long secondId = frame.planetPanels.get(1).id();
        SwingUtilities.invokeAndWait(() -> {
            frame.planetTemplateLoaded(firstId, controller.loadPlanetTemplate(irdJson));
            frame.planetTemplateLoaded(secondId, controller.loadPlanetTemplate(multiJson));
            frame.planetPanels.get(0).capacityPanel.setCapacityText("20000");
            frame.planetPanels.get(1).capacityPanel.setCapacityText("18000");
            frame.inventoryLoaded(status(BIG_P3));
        });
        SwingUtilities.invokeAndWait(() ->
                assertTrue(frame.calculateButton.isEnabled()));

        SwingUtilities.invokeAndWait(() -> frame.removePlanet(firstId));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, frame.planetPanels.size());
            assertEquals(secondId, frame.planetPanels.get(0).id());
        });

        // The survivor is renumbered to "Planet 1" — the request name proves it.
        AllocationFrame.AllocationRun run = frame.computeRun(BIG_P3, frame.planetPanels);
        PlanetAllocation survivor = run.plan().planets().get(0);
        assertEquals("Planet 1", survivor.name());
        SwingUtilities.invokeAndWait(() -> frame.showPlan(run));
        SwingUtilities.invokeAndWait(() ->
                assertTrue(frame.calculateButton.isEnabled(),
                        "survivor is still fully configured"));
    }

    // ---- helpers ----

    private static PiCalculatorController.InventoryStatus status(InventorySnapshot snapshot) {
        return new PiCalculatorController.InventoryStatus(snapshot, List.of(),
                snapshot.quantities().size(), 0, 3);
    }
}
