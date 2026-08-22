package com.vepi.allocation;

import java.util.List;

/**
 * The complete result of a multi-planet allocation run: one allocation per
 * planet (in request order) plus the global inventory usage report.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>for every planet: {@code usedCapacity <= planetCapacity};</li>
 *   <li>for every inventory item:
 *       {@code sum over planets of allocated quantity <= original inventory};</li>
 *   <li>every planet's material set is a whole number of production blocks —
 *       always balanced, never a partial cycle.</li>
 * </ul>
 */
public record AllocationPlan(List<PlanetAllocation> planets, List<InventoryItemUsage> inventoryUsage) {

    public AllocationPlan {
        planets = planets == null ? List.of() : List.copyOf(planets);
        inventoryUsage = inventoryUsage == null ? List.of() : List.copyOf(inventoryUsage);
    }

    @Override
    public String toString() {
        return "AllocationPlan[" + planets.size() + " planets, "
                + inventoryUsage.size() + " inventory items]";
    }
}
