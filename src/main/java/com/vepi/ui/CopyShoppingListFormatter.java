package com.vepi.ui;

import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;

import java.util.stream.Collectors;

/**
 * Clipboard formatters of the Balance Inventory page.
 *
 * <p>Same shopping-list discipline as {@link CopyText}: "Name quantity" per
 * line, name-sorted, no volumes, no runtime — the text pastes cleanly into
 * chat, notes or procurement tools.
 *
 * <ul>
 *   <li>{@link #shoppingList} — only items that still must be ADDED
 *       (addQuantity &gt; 0); this is the buy/produce list.</li>
 *   <li>{@link #targetInventory} — every balanced P2/P3 at its final target
 *       level (what the stock should read when the plan is fully loaded).</li>
 * </ul>
 */
public final class CopyShoppingListFormatter {

    private CopyShoppingListFormatter() {}

    /** "Biocells 28200" per line for items with Need to Add > 0; empty when nothing to add. */
    public static String shoppingList(InventoryBalancePlan plan) {
        return java.util.stream.Stream.concat(
                        plan.p2Balance().materials().stream(),
                        plan.p3Balance().materials().stream())
                .filter(m -> m.addQuantity() > 0)
                .map(m -> m.commodity().name() + " " + m.addQuantity())
                .collect(Collectors.joining("\n"));
    }

    /**
     * "Biocells 74280" per line for ALL balanced P2/P3 items — the final level
     * every stock should read once the plan is fully loaded.
     */
    public static String targetInventory(InventoryBalancePlan plan) {
        StringBuilder sb = new StringBuilder();
        for (InventoryBalanceMaterial m : java.util.stream.Stream.concat(
                plan.p2Balance().materials().stream(),
                plan.p3Balance().materials().stream()).toList()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.commodity().name()).append(' ').append(m.targetQuantity());
        }
        return sb.toString();
    }
}
