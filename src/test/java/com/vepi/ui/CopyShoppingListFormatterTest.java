package com.vepi.ui;

import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copy-button output discipline of the Balance Inventory page: "Name quantity"
 * lines, add-only for the shopping list, all-targets for the target inventory.
 */
class CopyShoppingListFormatterTest {

    private static com.vepi.domain.PiCommodity commodity(long id, String name) {
        return new com.vepi.domain.PiCommodity(id, name, BigDecimal.ONE);
    }

    private static InventoryBalanceMaterial material(long id, String name,
            long perBlock, long current, long target) {
        return new InventoryBalanceMaterial(commodity(id, name),
                perBlock, current, target, target - current);
    }

    /** Spec §10's Oxides + two friends as a tiny plan. */
    private static InventoryBalancePlan plan() {
        return new InventoryBalancePlan(
                1238, 3600,
                List.of(material(2329L, "Biocells", 60, 46080, 74280),
                        material(2317L, "Oxides", 60, 74225, 74280)),
                List.of(),
                List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void shoppingList_containsOnlyPositiveAdds_nameSorted() {
        // Biocells add=28200, Oxides add=55 — both present; a zero-add row
        // would be absent (covered by the empty case below).
        String text = CopyShoppingListFormatter.shoppingList(plan());
        assertEquals("""
                Biocells 28200
                Oxides 55""", text);
    }

    @Test
    void shoppingList_empty_whenNothingToAdd() {
        InventoryBalancePlan balanced = new InventoryBalancePlan(
                100, 3600,
                List.of(material(101L, "ItemA", 2, 200, 200)),
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertTrue(CopyShoppingListFormatter.shoppingList(balanced).isEmpty());
    }

    @Test
    void targetInventory_listsEveryBalancedP2AtTarget() {
        assertEquals("""
                Biocells 74280
                Oxides 74280""", CopyShoppingListFormatter.targetInventory(plan()));
    }

    @Test
    void copyOutputs_mergeIndependentP2AndP3Sections() {
        InventoryBalancePlan.TierBalance p3 = new InventoryBalancePlan.TierBalance(
                3, 3, 3600,
                List.of(material(201L, "Robotics", 4, 5, 12)),
                new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("7"));
        InventoryBalancePlan mixed = new InventoryBalancePlan(
                2, 3600,
                List.of(material(101L, "Biocells", 10, 15, 20)),
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, p3);

        assertEquals("Biocells 5\nRobotics 7",
                CopyShoppingListFormatter.shoppingList(mixed));
        assertEquals("Biocells 20\nRobotics 12",
                CopyShoppingListFormatter.targetInventory(mixed));
    }
}
