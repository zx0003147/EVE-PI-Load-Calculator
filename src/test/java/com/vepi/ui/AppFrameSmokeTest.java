package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
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
                assertFalse(panel.calculateButton.isEnabled(), "P4 missing"));

        // Select by the combo's typeID-backed value, not by display text.
        SwingUtilities.invokeAndWait(() -> selectP4(panel, 2868L));
        SwingUtilities.invokeAndWait(() ->
                {
                    assertTrue(panel.calculateButton.isEnabled(), "both inputs ready");
                    String hierarchy = hierarchyText(panel.recipeHierarchyPanel);
                    assertTrue(hierarchy.contains("Integrity Response Drones × 1"));
                    assertTrue(hierarchy.contains("P3 Balance Block"));
                    assertTrue(hierarchy.contains("P2 Balance Block"));
                    assertTrue(hierarchy.contains("├─ Gel-Matrix Biopaste × 6"));
                    assertTrue(hierarchy.contains("Biocells × 20"));
                    assertFalse(hierarchy.contains("P4 recipe:"));
                });
    }

    @Test
    void changingP4ImmediatelyRefreshesRecipeAndInvalidatesResult() throws Exception {
        AtomicReference<BalanceInventoryPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new BalanceInventoryPanel(
                new BalanceInventoryController(controller))));
        BalanceInventoryPanel panel = ref.get();
        SwingUtilities.invokeAndWait(() -> {
            panel.inventoryLoaded(controller.parseInventory("Biocells 100"));
            selectP4(panel, 2868L);
            panel.showPlan(new BalanceInventoryPanel.BalanceRun(
                    new BalanceInventoryController(controller).balance(
                            controller.parseInventory("Biocells 100").snapshot(), 2868L)));
            assertTrue(panel.copyShoppingButton.isEnabled());
            String oldRecipe = hierarchyText(panel.recipeHierarchyPanel);
            int other = panel.p4Combo.getSelectedIndex() == 0 ? 1 : 0;
            panel.p4Combo.setSelectedIndex(other);
            assertFalse(panel.copyShoppingButton.isEnabled());
            assertEquals(0, panel.p2Model.getRowCount());
            String newRecipe = hierarchyText(panel.recipeHierarchyPanel);
            assertFalse(newRecipe.equals(oldRecipe));
            assertTrue(newRecipe.contains("P4 → P3 → P2") || newRecipe.contains("P2 Balance Block"));
        });
    }

    private static String hierarchyText(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component component : root.getComponents()) {
            if (component instanceof javax.swing.JLabel label) {
                text.append(label.getText()).append('\n');
            }
            if (component instanceof Container child) {
                text.append(hierarchyText(child));
            }
        }
        return text.toString();
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
        SwingUtilities.invokeAndWait(() -> {
            panel.inventoryLoaded(controller.parseInventory(inventory));
            selectP4(panel, 2868L);
            panel.showPlan(new BalanceInventoryPanel.BalanceRun(
                    new BalanceInventoryController(controller).balance(
                            controller.parseInventory(inventory).snapshot(), 2868L)));
        });

        SwingUtilities.invokeAndWait(() -> {
            // All recipe-related P2 and direct P3 rows are rendered separately.
            assertEquals(9, panel.balanceModel.getRowCount());
            assertEquals("Biocells", panel.balanceModel.getValueAt(0, 0));
            assertEquals("46,080", panel.balanceModel.getValueAt(0, 2), "current");
            assertEquals(3, panel.p3Model.getRowCount());
            assertEquals(0, panel.unusedModel.getRowCount());

            String summaryText = panel.summary.getText();
            assertTrue(summaryText.contains("P2 target blocks: 3,712"),
                    () -> summaryText);
            assertTrue(summaryText.contains("P3 target blocks: 7"), () -> summaryText);
        });
    }

    private static void selectP4(BalanceInventoryPanel panel, long typeId) {
        for (int i = 0; i < panel.p4Combo.getItemCount(); i++) {
            if (panel.p4Combo.getItemAt(i).typeId() == typeId) {
                panel.p4Combo.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError("P4 not found: " + typeId);
    }
}
