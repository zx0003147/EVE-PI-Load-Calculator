package com.vepi.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable result of a full-load calculation over a template.
 */
public record ProductionResult(
        int facilityCount,
        List<MaterialThroughput> inputs,
        List<MaterialThroughput> outputs,
        List<MaterialThroughput> externalInputs,
        BigDecimal totalExternalM3PerHour
) {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Production facilities: ").append(facilityCount).append('\n');
        sb.append("\nInputs (per hour):\n");
        for (MaterialThroughput m : inputs) sb.append("  ").append(m).append('\n');
        sb.append("\nOutputs (per hour):\n");
        for (MaterialThroughput m : outputs) sb.append("  ").append(m).append('\n');
        sb.append("\nExternal inputs (per hour):\n");
        for (MaterialThroughput m : externalInputs) sb.append("  ").append(m).append('\n');
        sb.append("\nTotal external input: ")
                .append(totalExternalM3PerHour.stripTrailingZeros().toPlainString())
                .append(" m3/hour\n");
        return sb.toString();
    }
}
