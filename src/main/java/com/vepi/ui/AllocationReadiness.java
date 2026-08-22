package com.vepi.ui;

import java.util.List;

/**
 * Pure "can Calculate Allocation run?" evaluation — the enablement rule of the
 * main window, unit-testable without Swing.
 *
 * <p>Calculate is enabled only when ALL of the following hold:
 * <ul>
 *   <li>the inventory has been parsed (non-empty {@code InventorySnapshot});</li>
 *   <li>at least one planet is configured;</li>
 *   <li>every planet has a loaded template AND a valid capacity.</li>
 * </ul>
 */
public final class AllocationReadiness {

    private AllocationReadiness() {}

    /** One planet's readiness inputs (template loaded? capacity valid?). */
    public record PlanetInput(boolean templateLoaded, boolean capacityValid) {
        public boolean ready() {
            return templateLoaded && capacityValid;
        }
    }

    public static boolean ready(boolean inventoryLoaded, List<PlanetInput> planets) {
        if (!inventoryLoaded || planets == null || planets.isEmpty()) {
            return false;
        }
        return planets.stream().allMatch(PlanetInput::ready);
    }
}
