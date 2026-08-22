package com.vepi.balance;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Integer production flows of a whole template over one common base period.
 *
 * <p>The base period is the LCM of every facility's cycle time, so that within
 * exactly one base period <b>every</b> facility completes an integer number of
 * cycles and every commodity quantity is an exact integer. This is the unit of
 * production used by the load optimizer ("production block").
 *
 * <p>Net flow model per commodity (units per block, exact longs):
 * <pre>
 *   external requirement = max(consumed - produced, 0)   // must be imported
 *   net output           = max(produced - consumed, 0)   // final / surplus output
 * </pre>
 *
 * <p>This replaces Phase 0's set-difference external-input detection with a true
 * quantity-based flow balance, which correctly handles multi-stage templates
 * where an intermediate commodity is both produced and consumed internally.
 */
public final class ProductionBlock {

    private final long basePeriodSeconds;
    private final Map<Long, Long> producedPerBlock;   // typeId -> units produced per block
    private final Map<Long, Long> consumedPerBlock;   // typeId -> units consumed per block
    private final Map<Long, Long> externalRequirements;
    private final Map<Long, Long> netOutputs;

    public ProductionBlock(long basePeriodSeconds,
                           Map<Long, Long> producedPerBlock,
                           Map<Long, Long> consumedPerBlock) {
        if (basePeriodSeconds <= 0) {
            throw new IllegalArgumentException("basePeriodSeconds must be positive, got " + basePeriodSeconds);
        }
        this.basePeriodSeconds = basePeriodSeconds;
        this.producedPerBlock = Collections.unmodifiableSortedMap(new TreeMap<>(producedPerBlock));
        this.consumedPerBlock = Collections.unmodifiableSortedMap(new TreeMap<>(consumedPerBlock));

        SortedMap<Long, Long> external = new TreeMap<>();
        SortedMap<Long, Long> outputs = new TreeMap<>();
        for (Map.Entry<Long, Long> e : consumedPerBlock.entrySet()) {
            long produced = producedPerBlock.getOrDefault(e.getKey(), 0L);
            long deficit = e.getValue() - produced;
            if (deficit > 0) external.put(e.getKey(), deficit);
        }
        for (Map.Entry<Long, Long> e : producedPerBlock.entrySet()) {
            long consumed = consumedPerBlock.getOrDefault(e.getKey(), 0L);
            long surplus = e.getValue() - consumed;
            if (surplus > 0) outputs.put(e.getKey(), surplus);
        }
        this.externalRequirements = Collections.unmodifiableSortedMap(external);
        this.netOutputs = Collections.unmodifiableSortedMap(outputs);
    }

    /** Common time base of the block: LCM of all facility cycle times, in seconds. */
    public long basePeriodSeconds() { return basePeriodSeconds; }

    /** Units produced per block by internal facilities (typeId -> units). */
    public Map<Long, Long> producedPerBlock() { return producedPerBlock; }

    /** Units consumed per block by internal facilities (typeId -> units). */
    public Map<Long, Long> consumedPerBlock() { return consumedPerBlock; }

    /** Commodities that must be imported: consumption not covered by internal production. */
    public Map<Long, Long> externalRequirements() { return externalRequirements; }

    /** Final / surplus output per block: production not consumed internally. */
    public Map<Long, Long> netOutputs() { return netOutputs; }

    @Override
    public String toString() {
        return "ProductionBlock[base=" + basePeriodSeconds + "s, produced=" + producedPerBlock
                + ", consumed=" + consumedPerBlock + "]";
    }
}
