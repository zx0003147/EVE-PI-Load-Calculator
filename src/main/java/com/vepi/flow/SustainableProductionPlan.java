package com.vepi.flow;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The formal production model of one planet template under
 * <b>P2-only sustainable production</b>:
 *
 * <ul>
 *   <li>the user externally supplies <b>P2 only</b>;</li>
 *   <li>every commodity the template can produce itself is an <b>internal
 *       intermediate</b> (typically P3) — it is balanced internally and never
 *       becomes an external requirement, even when its internal capacity is
 *       below the theoretical downstream demand (that bottleneck throttles the
 *       downstream facilities instead);</li>
 *   <li>commodities the template consumes but cannot produce (no matching
 *       facility) remain external inputs — for a full P2→P3→P4 chain those are
 *       exactly the P2 items.</li>
 * </ul>
 *
 * <p>All quantities are exact integers over {@link #blockDurationSeconds()}:
 * the duration is chosen so that every facility completes a whole number of
 * cycles (at its sustainable utilization) and every commodity amount is an
 * exact long. The allocator hands out whole such sustainable blocks.
 *
 * <p>Replaces the old {@code ProductionBlock} net-flow model as the allocator
 * input; the old model stays available as a raw material-balance diagnostic.
 */
public final class SustainableProductionPlan {

    /** One internally produced + internally consumed commodity (an internal intermediate). */
    public record InternalFlow(
            long typeId,
            long producedPerBlock,
            long consumedPerBlock,
            Fraction utilization,
            Fraction capacityPerHour,
            Fraction sustainablePerHour) {
    }

    /** Utilization of one facility group (all facilities sharing a schematic). */
    public record GroupUtilization(
            long schematicId,
            String schematicName,
            int facilityCount,
            long cycleTimeSeconds,
            long cyclesPerFacilityPerBlock,
            Fraction utilization) {
    }

    /**
     * An internal intermediate whose internal production capacity is below the
     * theoretical downstream demand: the chain is sustainably throttled to
     * {@code capacityPerHour / demandAtFullPerHour} instead of importing it.
     */
    public record Bottleneck(
            long typeId,
            Fraction capacityPerHour,
            Fraction demandAtFullPerHour) {

        /** Sustainable throttle ratio of the chain (capacity / full demand), e.g. 4/5. */
        public Fraction throttle() {
            return demandAtFullPerHour.isZero() ? Fraction.ONE : capacityPerHour.divide(demandAtFullPerHour);
        }
    }

    private final long blockDurationSeconds;
    private final SortedMap<Long, Long> externalRequirementsPerBlock;
    private final SortedMap<Long, Long> finalOutputsPerBlock;
    private final List<InternalFlow> internalFlows;
    private final List<GroupUtilization> facilityUtilizations;
    private final List<Bottleneck> bottlenecks;

    public SustainableProductionPlan(long blockDurationSeconds,
                                     Map<Long, Long> externalRequirementsPerBlock,
                                     Map<Long, Long> finalOutputsPerBlock,
                                     List<InternalFlow> internalFlows,
                                     List<GroupUtilization> facilityUtilizations,
                                     List<Bottleneck> bottlenecks) {
        if (blockDurationSeconds <= 0) {
            throw new IllegalArgumentException("blockDurationSeconds must be positive, got " + blockDurationSeconds);
        }
        this.blockDurationSeconds = blockDurationSeconds;
        this.externalRequirementsPerBlock = immutableSorted(externalRequirementsPerBlock);
        this.finalOutputsPerBlock = immutableSorted(finalOutputsPerBlock);
        this.internalFlows = internalFlows == null ? List.of() : List.copyOf(internalFlows);
        this.facilityUtilizations = facilityUtilizations == null ? List.of() : List.copyOf(facilityUtilizations);
        this.bottlenecks = bottlenecks == null ? List.of() : List.copyOf(bottlenecks);
    }

    /**
     * Minimal factory for tests and diagnostics: a plan with externals and
     * outputs but no internal flow detail.
     */
    public static SustainableProductionPlan simple(long blockDurationSeconds,
                                                   Map<Long, Long> externalRequirementsPerBlock,
                                                   Map<Long, Long> finalOutputsPerBlock) {
        return new SustainableProductionPlan(blockDurationSeconds,
                externalRequirementsPerBlock, finalOutputsPerBlock,
                List.of(), List.of(), List.of());
    }

    private static SortedMap<Long, Long> immutableSorted(Map<Long, Long> map) {
        SortedMap<Long, Long> copy = new TreeMap<>();
        if (map != null) {
            copy.putAll(map);
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    /** Common time base of one sustainable block (all facilities finish whole cycles). */
    public long blockDurationSeconds() {
        return blockDurationSeconds;
    }

    /**
     * Commodities to import per block (typeId -> exact units). For a full
     * P2→P3→P4 chain these are exactly the P2 inputs; templates without
     * internal production for some input keep that input here.
     */
    public Map<Long, Long> externalRequirementsPerBlock() {
        return externalRequirementsPerBlock;
    }

    /** Final (exported) outputs per block: production not consumed internally. */
    public Map<Long, Long> finalOutputsPerBlock() {
        return finalOutputsPerBlock;
    }

    /** Internal intermediates: produced == consumed per block, with utilization. */
    public List<InternalFlow> internalFlows() {
        return internalFlows;
    }

    /** Per-facility-group sustainable utilization. */
    public List<GroupUtilization> facilityUtilizations() {
        return facilityUtilizations;
    }

    /** Internal intermediates that throttle the chain (capacity below full demand). */
    public List<Bottleneck> bottlenecks() {
        return bottlenecks;
    }

    @Override
    public String toString() {
        return "SustainableProductionPlan[block=" + blockDurationSeconds + "s, external="
                + externalRequirementsPerBlock + ", outputs=" + finalOutputsPerBlock
                + ", bottlenecks=" + bottlenecks.size() + "]";
    }
}
