package com.vepi.sde;

import com.vepi.domain.PiSchematic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Derives PI commodity tiers (P0 raw / P1 / P2 / P3 / P4) <b>from the SDE recipe
 * graph itself</b> — never from hand-written name lists.
 *
 * <p>Rules (recursive over schematics):
 * <ul>
 *   <li>a commodity that is not produced by any PI schematic is a raw planetary
 *       resource: tier 0;</li>
 *   <li>otherwise {@code tier(X) = 1 + max(tier of every input of X's schematic)}.</li>
 * </ul>
 *
 * <p>This yields the canonical tiers: P1 from raws, P2 from P1, P3 from P2,
 * P4 from P3. A depth guard protects against corrupted data cycles.
 */
public final class PiTierResolver {

    private static final int MAX_DEPTH = 8;

    private final SdeRepository sde;
    private final Map<Long, Integer> cache = new HashMap<>();

    public PiTierResolver(SdeRepository sde) {
        this.sde = sde;
    }

    /**
     * @param typeId commodity typeID
     * @return 0 for raw resources, 1..4 for P1..P4; -1 if the type is not a PI
     *         commodity at all (neither produced nor consumed by any schematic)
     */
    public int tierOf(long typeId) {
        Integer cached = cache.get(typeId);
        if (cached != null) return cached;
        int tier = compute(typeId, 0);
        cache.put(typeId, tier);
        return tier;
    }

    private int compute(long typeId, int depth) {
        if (depth > MAX_DEPTH) {
            throw new SdeException("PI recipe graph is cyclic or too deep at typeID " + typeId);
        }
        Optional<Long> schematicId = sde.findSchematicByOutput(typeId);
        if (schematicId.isEmpty()) {
            // Not produced: raw resource (0) if a schematic consumes it, else not PI.
            return sde.isConsumedCommodity(typeId) ? 0 : -1;
        }
        PiSchematic schematic = sde.getSchematic(schematicId.get());
        int maxInputTier = 0;
        for (var m : schematic.materials()) {
            if (!m.isInput()) continue;
            int t = tierOf(m.typeId());
            if (t < 0) t = 0;   // defensive: non-PI input counts as raw
            maxInputTier = Math.max(maxInputTier, t);
        }
        return maxInputTier + 1;
    }
}
