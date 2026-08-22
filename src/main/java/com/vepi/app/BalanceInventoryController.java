package com.vepi.app;

import com.vepi.balancing.InventoryBalanceCalculator;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;

/**
 * Seam of the <b>Balance Inventory</b> feature — deliberately independent of
 * the Load Allocation workflow (no planet capacity, no multi-planet split,
 * no shared inventory state).
 *
 * <p>Thin composition: inventory parsing and template loading reuse the same
 * {@link PiCalculatorController} SDE pipeline as Load Allocation, while the
 * balance arithmetic itself lives in the standalone
 * {@link InventoryBalanceCalculator} (pure domain logic, tested without the
 * SDE). This class exists so the UI and its tests have ONE entry point for
 * "inventory text + template text → InventoryBalancePlan".
 */
public final class BalanceInventoryController {

    private final PiCalculatorController core;
    private final InventoryBalanceCalculator calculator = new InventoryBalanceCalculator();

    public BalanceInventoryController(PiCalculatorController core) {
        this.core = core;
    }

    /** Parses pasted inventory text against the SDE (same parser as Load Allocation). */
    public PiCalculatorController.InventoryStatus parseInventory(String inventoryText) {
        return core.parseInventory(inventoryText);
    }

    /** Loads a pasted template JSON into summary + sustainable plan (P2-only model). */
    public PiCalculatorController.PlanetTemplate loadTemplate(String templateText) {
        return core.loadPlanetTemplate(templateText);
    }

    /**
     * The full balance computation.
     *
     * @throws com.vepi.balancing.BalanceException.NoP2Requirements
     *         when the template is not a P2 → P4 chain (e.g. pure P3 → P4)
     */
    public InventoryBalancePlan balance(InventorySnapshot snapshot,
                                        SustainableProductionPlan plan) {
        return calculator.calculate(plan, snapshot, core::tierOf, core::commodityOf);
    }

    /** Convenience overload straight from raw paste text (controller tests). */
    public InventoryBalancePlan balance(String inventoryText, String templateText) {
        var status = parseInventory(inventoryText);
        var template = loadTemplate(templateText);
        return balance(status.snapshot(), template.plan());
    }

    /**
     * Whether this template's external requirements contain any tier-2 input
     * — false means Balance cannot run for it and the UI should show the
     * "not a P2 → P4 chain" error instead of enabling Calculate.
     */
    public boolean hasP2Chain(SustainableProductionPlan plan) {
        return plan.externalRequirementsPerBlock().entrySet().stream()
                .anyMatch(e -> e.getValue() != null && e.getValue() > 0
                        && core.tierOf(e.getKey()) == 2);
    }
}
