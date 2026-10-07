package com.vepi.ui;

import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.domain.PiCommodity;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless-safe smoke coverage for both balance sections and stale-state clearing. */
class BalanceInventoryPanelStateTest {

    @Test
    void rendersBothTiersAndEnablesCopyOnlyForAResult() throws Exception {
        AtomicReference<BalanceInventoryPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new BalanceInventoryPanel(null)));
        BalanceInventoryPanel panel = ref.get();

        SwingUtilities.invokeAndWait(() -> {
            assertFalse(panel.copyShoppingButton.isEnabled());
            assertFalse(panel.copyTargetButton.isEnabled());
            panel.showPlan(new BalanceInventoryPanel.BalanceRun(plan()));
            assertEquals(1, panel.p2Model.getRowCount());
            assertEquals(1, panel.p3Model.getRowCount());
            assertTrue(panel.copyShoppingButton.isEnabled());
            assertTrue(panel.copyTargetButton.isEnabled());
        });
    }

    @Test
    void failuresAndClearRemoveRowsAndDisableCopy() throws Exception {
        AtomicReference<BalanceInventoryPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new BalanceInventoryPanel(null)));
        BalanceInventoryPanel panel = ref.get();

        SwingUtilities.invokeAndWait(() -> {
            panel.showPlan(new BalanceInventoryPanel.BalanceRun(plan()));
            panel.inventoryFailed("bad inventory");
            assertEquals(0, panel.p2Model.getRowCount());
            assertEquals(0, panel.p3Model.getRowCount());
            assertFalse(panel.copyShoppingButton.isEnabled());
            assertFalse(panel.copyTargetButton.isEnabled());

            panel.showPlan(new BalanceInventoryPanel.BalanceRun(plan()));
            panel.showError("bad product recipe");
            assertEquals(0, panel.p2Model.getRowCount());
            assertEquals(0, panel.p3Model.getRowCount());
            assertFalse(panel.copyShoppingButton.isEnabled());

            panel.showPlan(new BalanceInventoryPanel.BalanceRun(plan()));
            panel.clearResults();
            assertFalse(panel.copyTargetButton.isEnabled());
        });
    }

    private static InventoryBalancePlan plan() {
        InventoryBalanceMaterial p2 = material(101, "Biocells", 10, 15, 20);
        InventoryBalanceMaterial p3 = material(201, "Robotics", 4, 5, 12);
        InventoryBalancePlan.TierBalance p3Balance = new InventoryBalancePlan.TierBalance(
                3, 3, 3600, List.of(p3), new BigDecimal("5"),
                new BigDecimal("12"), new BigDecimal("7"));
        return new InventoryBalancePlan(2, 3600, List.of(p2), List.of(), List.of(),
                new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5"), p3Balance);
    }

    private static InventoryBalanceMaterial material(long id, String name,
                                                       long perBlock, long current, long target) {
        return new InventoryBalanceMaterial(new PiCommodity(id, name, BigDecimal.ONE),
                perBlock, current, target, target - current);
    }
}
