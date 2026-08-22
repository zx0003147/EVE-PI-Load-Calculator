package com.vepi.load;

import com.vepi.balance.ProductionBlock;
import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Computes the load plan that maximizes continuous full-load runtime.
 *
 * <p>Objective: find the largest integer number of production blocks whose total
 * external-input volume fits into the available capacity:
 * <pre>
 *   blockCount = floor(capacity / blockVolume)
 * </pre>
 * All materials are then loaded at exactly {@code blockCount x perBlockQuantity}
 * units, so every external input supports the <b>same</b> runtime — the plan is
 * balanced by construction. Leftover capacity smaller than one block is reported
 * as remaining and is NOT filled with unbalanced extra material.
 *
 * <p>Precision contract:
 * <ul>
 *   <li>volumes and capacities are exact {@link BigDecimal}s;</li>
 *   <li>the block count uses {@code divide(..., 0, RoundingMode.FLOOR)} — exact
 *       floor, no floating point, so a capacity of 863 m3 against a 432 m3 block
 *       yields 1 block, never 2;</li>
 *   <li>all quantities are exact integers ({@code long}).</li>
 * </ul>
 */
public final class LoadOptimizer {

    /** Capacity magnitudes above 10^18 m3 are rejected as input errors. */
    private static final int MAX_CAPACITY_INTEGER_DIGITS = 18;

    /**
     * @param block             the template's production block (LCM base period + net flows)
     * @param capacity          available storage capacity in m3 (>= 0, exact)
     * @param commodityResolver resolves typeID -> commodity (name + volume); typically the SDE
     * @return the balanced recommended load plan
     * @throws LoadException.InvalidCapacity for null / negative / oversized capacity
     * @throws LoadException                  if the template needs no external inputs at all
     */
    public RecommendedLoadPlan optimize(ProductionBlock block,
                                        BigDecimal capacity,
                                        Function<Long, PiCommodity> commodityResolver) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(commodityResolver, "commodityResolver");
        if (capacity == null) {
            throw new LoadException.InvalidCapacity("capacity must not be null");
        }
        if (capacity.signum() < 0) {
            throw new LoadException.InvalidCapacity(
                    "capacity must be >= 0 m3, got " + capacity.toPlainString());
        }

        Map<Long, Long> external = block.externalRequirements();
        Map<Long, Long> outputs = block.netOutputs();
        if (external.isEmpty()) {
            throw new LoadException(
                    "template requires no external inputs — runtime is unbounded and "
                            + "no load plan is needed");
        }

        // Resolve every external commodity once and compute the exact block volume.
        Map<Long, PiCommodity> commodities = new LinkedHashMap<>();
        BigDecimal blockVolume = BigDecimal.ZERO;
        for (Map.Entry<Long, Long> e : external.entrySet()) {
            PiCommodity c = commodityResolver.apply(e.getKey());
            commodities.put(e.getKey(), c);
            blockVolume = blockVolume.add(c.volume().multiply(BigDecimal.valueOf(e.getValue())));
        }
        if (blockVolume.signum() <= 0) {
            throw new LoadException(
                    "external commodities have zero total volume — cannot compute a load plan");
        }

        // Exact floor division: how many whole production blocks fit?
        long blockCount;
        try {
            blockCount = capacity.divide(blockVolume, 0, RoundingMode.FLOOR).longValueExact();
        } catch (ArithmeticException ex) {
            throw new LoadException.InvalidCapacity(
                    "capacity too large: " + capacity.toPlainString());
        }

        long runtimeSeconds = Math.multiplyExact(blockCount, block.basePeriodSeconds());

        // Integer quantities per material, exact volumes, exact used/remaining capacity.
        List<RecommendedMaterialLoad> materials = new ArrayList<>();
        BigDecimal used = BigDecimal.ZERO;
        for (Map.Entry<Long, Long> e : external.entrySet()) {
            PiCommodity c = commodities.get(e.getKey());
            long quantity = Math.multiplyExact(blockCount, e.getValue());
            BigDecimal volume = c.volume().multiply(BigDecimal.valueOf(quantity));
            used = used.add(volume);
            materials.add(new RecommendedMaterialLoad(c, quantity, volume, runtimeSeconds));
        }
        BigDecimal remaining = capacity.subtract(used);
        if (remaining.signum() < 0) {
            throw new IllegalStateException(
                    "internal error: load plan exceeds capacity (used=" + used
                            + ", capacity=" + capacity + ")");
        }

        List<RecommendedLoadPlan.ExpectedOutput> expectedOutputs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : outputs.entrySet()) {
            PiCommodity c = commodityResolver.apply(e.getKey());
            long quantity = Math.multiplyExact(blockCount, e.getValue());
            expectedOutputs.add(new RecommendedLoadPlan.ExpectedOutput(c, quantity));
        }

        return new RecommendedLoadPlan(capacity, blockCount, block.basePeriodSeconds(),
                runtimeSeconds, used, remaining, List.copyOf(materials),
                List.copyOf(expectedOutputs));
    }

    /**
     * Parse and validate a user-supplied capacity string (m3).
     *
     * @throws LoadException.InvalidCapacity for blank / non-numeric / NaN / negative /
     *                                       oversized input
     */
    public static BigDecimal parseCapacity(String text) {
        if (text == null || text.isBlank()) {
            throw new LoadException.InvalidCapacity("capacity must not be empty (expected a number in m3)");
        }
        String trimmed = text.trim();
        BigDecimal value;
        try {
            value = new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            throw new LoadException.InvalidCapacity(
                    "invalid capacity value: '" + trimmed + "' (expected a number in m3)");
        }
        if (value.signum() < 0) {
            throw new LoadException.InvalidCapacity(
                    "capacity must be >= 0 m3, got " + trimmed);
        }
        // precision - scale = number of integer digits (works for "1e400" style input too).
        if (value.precision() - value.scale() > MAX_CAPACITY_INTEGER_DIGITS) {
            throw new LoadException.InvalidCapacity(
                    "capacity too large: " + trimmed + " (max "
                            + MAX_CAPACITY_INTEGER_DIGITS + " integer digits)");
        }
        return value;
    }
}
