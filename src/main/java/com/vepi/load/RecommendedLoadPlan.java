package com.vepi.load;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full recommended load plan for one template + one available capacity.
 *
 * <p>All numbers are exact: integer quantities ({@code long}) and exact
 * {@link BigDecimal} volumes. The plan never exceeds the requested capacity and
 * never breaks material balance — leftover capacity is deliberately left empty
 * when it cannot fund another whole production block.
 */
public record RecommendedLoadPlan(
        BigDecimal requestedCapacity,
        long blockCount,
        long basePeriodSeconds,
        long runtimeSeconds,
        BigDecimal usedCapacity,
        BigDecimal remainingCapacity,
        List<RecommendedMaterialLoad> materials,
        List<ExpectedOutput> expectedOutputs) {

    /** A final / surplus output commodity with the quantity producible within the plan. */
    public record ExpectedOutput(PiCommodity commodity, long quantity) {
        @Override
        public String toString() {
            return commodity.name() + " (" + commodity.typeId() + "): " + quantity;
        }
    }

    /** Human-readable runtime, e.g. "46h (165600 s)" or "2h 30m (9000 s)". */
    public static String formatRuntime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s ");
        return sb.toString().trim() + " (" + seconds + " s)";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available capacity:\n  ").append(plain(requestedCapacity)).append(" m3\n");
        sb.append("\nRecommended load:\n");
        if (materials.isEmpty()) {
            sb.append("  (none — no external inputs required)\n");
        }
        for (RecommendedMaterialLoad m : materials) {
            sb.append("  ").append(m.commodity().name())
                    .append(" (").append(m.typeId()).append(")\n");
            sb.append("    quantity: ").append(m.quantity()).append('\n');
            sb.append("    volume: ").append(plain(m.volume())).append(" m3\n");
        }
        sb.append("\nProduction blocks: ").append(blockCount)
                .append(" (base period ").append(basePeriodSeconds).append(" s)\n");
        sb.append("Runtime: ").append(formatRuntime(runtimeSeconds)).append('\n');
        sb.append("Used capacity: ").append(plain(usedCapacity)).append(" m3\n");
        sb.append("Remaining capacity: ").append(plain(remainingCapacity)).append(" m3\n");
        sb.append("\nExpected output:\n");
        for (ExpectedOutput o : expectedOutputs) {
            sb.append("  ").append(o.commodity().name())
                    .append(" (").append(o.commodity().typeId()).append("): ")
                    .append(o.quantity()).append('\n');
        }
        return sb.toString();
    }
}
