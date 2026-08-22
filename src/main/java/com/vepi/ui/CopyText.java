package com.vepi.ui;

import com.vepi.allocation.MaterialAllocation;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Builds the clipboard text for "Copy Material List".
 *
 * <p>Shopping-list format: one line per material, "Name quantity" only —
 * deliberately no volumes, no runtime, so the text pastes cleanly into chat,
 * notes or procurement tools. Order is stable: material name (alphabetical).
 */
public final class CopyText {

    private CopyText() {}

    /** "Gel-Matrix Biopaste 2208" per line, newline-separated, name-sorted. */
    public static String materialList(RecommendedLoadPlan plan) {
        return plan.materials().stream()
                .sorted(Comparator.comparing(m -> m.commodity().name(), String.CASE_INSENSITIVE_ORDER))
                .map(m -> m.commodity().name() + " " + m.quantity())
                .collect(Collectors.joining("\n"));
    }

    /**
     * "Copy Planet Load": the actual haul list for one planet. P2 first, P3
     * second (each name-sorted, the same grouping the UI renders), one
     * "Name quantity" line per material — no volumes, no runtime, no outputs.
     * Zero-quantity rows (a 0-block planet) are skipped.
     */
    public static String planetLoad(PlanetAllocation planet) {
        StringBuilder sb = new StringBuilder();
        appendTier(sb, planet.materialsOfTier(2));
        appendTier(sb, planet.materialsOfTier(3));
        // Odd-tier inputs (e.g. P1 into a P2 factory) still belong on the haul list.
        appendTier(sb, planet.materials().stream()
                .filter(m -> m.tier() != 2 && m.tier() != 3)
                .sorted(Comparator.comparing(MaterialAllocation::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
        return sb.toString();
    }

    private static void appendTier(StringBuilder sb, java.util.List<MaterialAllocation> materials) {
        for (MaterialAllocation m : materials) {
            if (m.quantity() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.name()).append(' ').append(m.quantity());
        }
    }

    /** Optional fuller variant (not bound to a button in Phase 2). */
    public static String fullReport(RecommendedLoadPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (RecommendedMaterialLoad m : plan.materials()) {
            sb.append(m.commodity().name()).append("  ")
                    .append(m.quantity()).append("  ")
                    .append(Formats.volume(m.volume())).append('\n');
        }
        sb.append('\n');
        sb.append("Runtime: ").append(Formats.runtimeExact(plan.runtimeSeconds())).append('\n');
        sb.append("Used capacity: ").append(Formats.volume(plan.usedCapacity())).append('\n');
        sb.append("Remaining capacity: ").append(Formats.volume(plan.remainingCapacity())).append('\n');
        for (RecommendedLoadPlan.ExpectedOutput o : plan.expectedOutputs()) {
            sb.append("Expected output: ").append(o.commodity().name())
                    .append(" x").append(o.quantity()).append('\n');
        }
        return sb.toString();
    }
}
