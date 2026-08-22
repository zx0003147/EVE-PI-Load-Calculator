package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test of the two-tab main window: Load Allocation first, Balance
 * Inventory second, fully independent inputs (spec §1/§21/§23/§28). Drives
 * the sync render entry points — no visible window, skipped headless.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppFrameSmokeTest {

    private PiCalculatorController controller;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "no display available — GUI smoke test skipped");
        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
    }

    @AfterAll
    void tearDown() {
        if (controller != null) {
            controller.close();
        }
    }

    @Test
    void twoTabs_loadAllocationFirst_balanceSecond() throws Exception {
        AtomicReference<AppFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new AppFrame(controller)));
        AppFrame app = ref.get();

        SwingUtilities.invokeAndWait(() -> {
            // setContentPane(tabs) — the content pane IS the tabbed pane.
            var tabs = (javax.swing.JTabbedPane) app.getContentPane();
            assertEquals(2, tabs.getTabCount());
            assertEquals("Load Allocation", tabs.getTitleAt(0));
            assertEquals("Balance Inventory", tabs.getTitleAt(1));
            assertTrue(tabs.getComponentAt(0) instanceof AllocationFrame);
            assertTrue(tabs.getComponentAt(1) instanceof BalanceInventoryPanel);
        });
    }

    @Test
    void balancePanel_calculateGatesOnBothInputs() throws Exception {
        AtomicReference<BalanceInventoryPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                ref.set(new BalanceInventoryPanel(
                        new BalanceInventoryController(controller))));
        BalanceInventoryPanel panel = ref.get();

        SwingUtilities.invokeAndWait(() ->
                assertFalse(panel.calculateButton.isEnabled(), "nothing loaded yet"));

        // inventory alone is not enough
        SwingUtilities.invokeAndWait(() ->
                panel.inventoryLoaded(controller.parseInventory("Biocells 46080\n")));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(panel.calculateButton.isEnabled(), "template missing"));

        // template alone (fresh panel state) would not be enough either — but
        // with both set it must enable. Load the real full-chain template.
        String template = Files.readString(Path.of("data/templates/Pandogodzilla.json"));
        SwingUtilities.invokeAndWait(() ->
                panel.templateLoaded(controller.loadPlanetTemplate(template)));
        SwingUtilities.invokeAndWait(() ->
                assertTrue(panel.calculateButton.isEnabled(), "both inputs ready"));
    }

    @Test
    void balancePanel_rendersRealNumbersAndUnusedP3() throws Exception {
        AtomicReference<BalanceInventoryPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
                ref.set(new BalanceInventoryPanel(
                        new BalanceInventoryController(controller))));
        BalanceInventoryPanel panel = ref.get();

        String inventory = """
                Biocells 46080
                Oxides 74225
                Hazmat Detection Systems 41
                """;
        String template = Files.readString(Path.of("data/templates/Pandogodzilla.json"));

        SwingUtilities.invokeAndWait(() -> {
            panel.inventoryLoaded(controller.parseInventory(inventory));
            panel.templateLoaded(controller.loadPlanetTemplate(template));
            // the exact call the Calculate worker makes when done:
            panel.showPlan(new BalanceInventoryPanel.BalanceRun(
                    new BalanceInventoryController(controller).balance(
                            controller.parseInventory(inventory).snapshot(),
                            controller.loadPlanetTemplate(template).plan())));
        });

        SwingUtilities.invokeAndWait(() -> {
            // ALL 9 template P2 rows appear (current=0 for the 7 not owned);
            // the P3 stock shows as the single unused row instead.
            assertEquals(9, panel.balanceModel.getRowCount());
            assertEquals("Biocells", panel.balanceModel.getValueAt(0, 0));
            assertEquals("46,080", panel.balanceModel.getValueAt(0, 2), "current");
            assertEquals(1, panel.unusedModel.getRowCount());
            assertEquals("Hazmat Detection Systems", panel.unusedModel.getValueAt(0, 0));

            String summaryText = panel.summary.getText();
            // Oxides 74225 drives: ceil(74225/60) = 1238 blocks (spec §10)
            assertTrue(summaryText.contains("Target production blocks: 1,238"),
                    () -> summaryText);
            // expected output row: 3 IRD/block x 1238 blocks
            assertEquals(1, panel.outputModel.getRowCount());
            assertEquals("Integrity Response Drones", panel.outputModel.getValueAt(0, 0));
            assertEquals("3,714", panel.outputModel.getValueAt(0, 1));
        });
    }
}
