package com.vepi.app;

import com.vepi.balancing.InventoryBalanceCalculator;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.balancing.P4BalanceRecipe;
import com.vepi.inventory.InventorySnapshot;

import java.util.List;

/**
 * Seam of the <b>Balance Inventory</b> feature — deliberately independent of
 * the Load Allocation workflow (no planet capacity, no multi-planet split,
 * no shared inventory state).
 *
 * <p>Thin composition: inventory parsing, P4 discovery and recipe lookup reuse
 * the shared SDE, while the balance arithmetic lives in the standalone
 * {@link InventoryBalanceCalculator}. No template state enters this workflow.
 */
public final class BalanceInventoryController {

    /** Combo-box value: display name is presentation; typeID is the identity. */
    public record P4Product(long typeId, String name) {
        @Override public String toString() { return name; }
    }

    private final PiCalculatorController core;
    private final InventoryBalanceCalculator calculator = new InventoryBalanceCalculator();

    public BalanceInventoryController(PiCalculatorController core) {
        this.core = core;
    }

    /** Parses pasted inventory text against the SDE (same parser as Load Allocation). */
    public PiCalculatorController.InventoryStatus parseInventory(String inventoryText) {
        return core.parseInventory(inventoryText);
    }

    public List<P4Product> p4Products() {
        return core.p4Products().stream()
                .map(c -> new P4Product(c.typeId(), c.name()))
                .toList();
    }

    public InventoryBalancePlan balance(InventorySnapshot snapshot, long p4TypeId) {
        return balance(snapshot, recipe(p4TypeId));
    }

    public P4BalanceRecipe recipe(long p4TypeId) {
        return core.p4BalanceRecipe(p4TypeId);
    }

    public InventoryBalancePlan balance(InventorySnapshot snapshot, P4BalanceRecipe recipe) {
        return calculator.calculate(recipe, snapshot,
                core::tierOf, core::commodityOf);
    }

}
