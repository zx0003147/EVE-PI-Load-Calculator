package com.vepi.allocation;

import com.vepi.flow.SustainableProductionPlan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The allocation result for one planet: how many complete sustainable
 * production blocks it received, the resulting continuous runtime, the
 * materials to load (P2 and higher-tier externals alike, each carrying its
 * tier), and capacity usage.
 */
public record PlanetAllocation(
        PlanetRequest request,
        long blockCount,
        long runtimeSeconds,
        BigDecimal usedCapacity,
        BigDecimal remainingCapacity,
        List<MaterialAllocation> materials,
        List<ExpectedOutput> expectedOutputs) {

    public SustainableProductionPlan plan() { return request.plan(); }

    public String name() { return request.name(); }

    /** Materials of exactly the given tier, sorted by name (e.g. tier 2 = P2 load). */
    public List<MaterialAllocation> materialsOfTier(int tier) {
        return materials.stream()
                .filter(m -> m.tier() == tier)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .collect(Collectors.toList());
    }

    /** Quantities by typeId — convenient for tests and assertions. */
    public Map<Long, Long> quantitiesByTypeId() {
        return materials.stream().collect(Collectors.toMap(
                MaterialAllocation::typeId, MaterialAllocation::quantity));
    }

    @Override
    public String toString() {
        return "PlanetAllocation[" + name() + ", blocks=" + blockCount
                + ", runtime=" + runtimeSeconds + "s, used="
                + usedCapacity.stripTrailingZeros().toPlainString() + " m3]";
    }
}
