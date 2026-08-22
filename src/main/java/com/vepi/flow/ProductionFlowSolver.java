package com.vepi.flow;

import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.template.TemplateException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic topological-flow solver for <b>P2-only sustainable
 * production</b>: given a template's SDE-resolved facilities it computes the
 * maximum production state that can run indefinitely on internal balances.
 *
 * <p>Model: every group of facilities sharing a schematic has a utilization
 * {@code u ∈ [0, 1]} (exact {@link Fraction}). For every internally produced
 * commodity {@code X} the stable state requires
 * {@code production(X) == consumption(X)}:
 * <ul>
 *   <li>if full demand exceeds internal capacity, the <b>consumers of X are
 *       throttled</b> proportionally (never: import X from outside);</li>
 *   <li>if internal capacity exceeds demand, the <b>producers of X are
 *       derated</b> (no useless surplus production, no wasted P2).</li>
 * </ul>
 * Repeated passes converge monotonically (utilizations only ever decrease);
 * PI recipe graphs are shallow chains, so convergence takes a few passes.
 *
 * <p>Everything is exact rational arithmetic; the final per-block quantities
 * are integers by construction: the block duration is the smallest common
 * multiple at which every facility completes a whole number of cycles at its
 * sustainable utilization.
 */
public final class ProductionFlowSolver {

    private static final int MAX_PASSES = 10_000;
    private static final long MAX_BLOCK_DURATION_SECONDS = 1_000_000_000L;

    /** All template facilities sharing one schematic (identical recipe + cycle). */
    private record Group(PiSchematic schematic, int count) {
    }

    public SustainableProductionPlan solve(List<TemplateProductionFacility> facilities) {
        if (facilities == null || facilities.isEmpty()) {
            throw new TemplateException.UnsupportedTemplate(
                    "template contains no production facilities — nothing to solve");
        }

        // ---- 1. Group facilities by schematic (deterministic order). ----
        Map<Long, Group> groups = new LinkedHashMap<>();
        for (TemplateProductionFacility f : facilities) {
            groups.merge(f.schematic().schematicId(),
                    new Group(f.schematic(), 1),
                    (a, b) -> new Group(a.schematic(), a.count() + b.count()));
        }

        // ---- 2. Full-utilization rates per second, group- and template-wide. ----
        // groupProduce.get(schematicId).get(typeId) = count * qty / cycleTime
        Map<Long, Map<Long, Fraction>> groupProduce = new HashMap<>();
        Map<Long, Map<Long, Fraction>> groupConsume = new HashMap<>();
        Map<Long, Fraction> produceFullRate = new TreeMap<>();   // typeId -> units/s at u=1
        Map<Long, Fraction> consumeFullRate = new TreeMap<>();
        for (Group g : groups.values()) {
            Fraction cyclesPerSecond = Fraction.of(1, g.schematic().cycleTimeSeconds());
            for (PiSchematicMaterial m : g.schematic().materials()) {
                Fraction rate = cyclesPerSecond
                        .multiply(Fraction.of((long) g.count() * m.quantity(), 1));
                Map<Long, Map<Long, Fraction>> target = m.isInput() ? groupConsume : groupProduce;
                Map<Long, Fraction> aggregate = m.isInput() ? consumeFullRate : produceFullRate;
                target.computeIfAbsent(g.schematic().schematicId(), k -> new TreeMap<>())
                        .merge(m.typeId(), rate, Fraction::add);
                aggregate.merge(m.typeId(), rate, Fraction::add);
            }
        }

        // Internal intermediates: produced AND consumed inside the template.
        Set<Long> internal = new java.util.TreeSet<>(produceFullRate.keySet());
        internal.retainAll(consumeFullRate.keySet());
        List<Long> internalSorted = new ArrayList<>(internal);

        // ---- 3. Solve sustainable utilizations (proportional scaling to fixpoint). ----
        Map<Long, Fraction> u = new TreeMap<>();   // schematicId -> utilization
        for (Long id : groups.keySet()) {
            u.put(id, Fraction.ONE);
        }
        Set<Long> throttledEver = new HashSet<>();  // commodities that ever hit capacity
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (long x : internalSorted) {
                Fraction prod = rateOf(u, groupProduce, x);
                Fraction cons = rateOf(u, groupConsume, x);
                int cmp = prod.compareTo(cons);
                if (cmp < 0) {
                    // Capacity short: throttle every consumer of x (never import x).
                    throttledEver.add(x);
                    Fraction factor = prod.divide(cons);
                    scaleConsumers(u, groupConsume, x, factor);
                    changed = true;
                } else if (cmp > 0) {
                    // Capacity surplus: derate producers to actual demand
                    // (never produce intermediate surplus / burn extra P2).
                    Fraction factor = cons.divide(prod);
                    scaleProducers(u, groupProduce, x, factor);
                    changed = true;
                }
            }
            if (!changed) {
                return assemble(groups, groupProduce, groupConsume,
                        produceFullRate, consumeFullRate, internalSorted, u, throttledEver);
            }
        }
        throw new IllegalStateException("flow solver did not converge within "
                + MAX_PASSES + " passes — template structure looks cyclic");
    }

    private static Fraction rateOf(Map<Long, Fraction> u,
                                   Map<Long, Map<Long, Fraction>> groupRates, long typeId) {
        Fraction total = Fraction.ZERO;
        for (Map.Entry<Long, Map<Long, Fraction>> e : groupRates.entrySet()) {
            Fraction rate = e.getValue().get(typeId);
            if (rate != null) {
                total = total.add(u.get(e.getKey()).multiply(rate));
            }
        }
        return total;
    }

    private static void scaleConsumers(Map<Long, Fraction> u,
                                       Map<Long, Map<Long, Fraction>> groupConsume,
                                       long typeId, Fraction factor) {
        for (Map.Entry<Long, Map<Long, Fraction>> e : groupConsume.entrySet()) {
            if (e.getValue().containsKey(typeId)) {
                u.put(e.getKey(), u.get(e.getKey()).multiply(factor));
            }
        }
    }

    private static void scaleProducers(Map<Long, Fraction> u,
                                       Map<Long, Map<Long, Fraction>> groupProduce,
                                       long typeId, Fraction factor) {
        for (Map.Entry<Long, Map<Long, Fraction>> e : groupProduce.entrySet()) {
            if (e.getValue().containsKey(typeId)) {
                u.put(e.getKey(), u.get(e.getKey()).multiply(factor));
            }
        }
    }

    // ---- 4. Quantize to an integer sustainable block and assemble the plan. ----

    private SustainableProductionPlan assemble(Map<Long, Group> groups,
                                               Map<Long, Map<Long, Fraction>> groupProduce,
                                               Map<Long, Map<Long, Fraction>> groupConsume,
                                               Map<Long, Fraction> produceFullRate,
                                               Map<Long, Fraction> consumeFullRate,
                                               List<Long> internalSorted,
                                               Map<Long, Fraction> u,
                                               Set<Long> throttledEver) {
        long blockDuration = blockDuration(groups, u);

        // Per-block integer flows, accumulated per group.
        Map<Long, Long> producedPerBlock = new TreeMap<>();
        Map<Long, Long> consumedPerBlock = new TreeMap<>();
        Map<Long, Long> cyclesPerFacility = new TreeMap<>();
        for (Map.Entry<Long, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            Fraction util = u.get(e.getKey());
            long cycles = blockDuration
                    * util.numerator().longValueExact()
                    / (util.denominator().longValueExact() * g.schematic().cycleTimeSeconds());
            if (blockDuration * util.numerator().longValueExact()
                    % (util.denominator().longValueExact() * g.schematic().cycleTimeSeconds()) != 0) {
                throw new IllegalStateException("block quantization failed for schematic "
                        + e.getKey() + " — not an integer cycle count");
            }
            cyclesPerFacility.put(e.getKey(), cycles);
            for (PiSchematicMaterial m : g.schematic().materials()) {
                long amount = Math.multiplyExact(
                        Math.multiplyExact((long) g.count() * m.quantity(), cycles), 1L);
                Map<Long, Long> target = m.isInput() ? consumedPerBlock : producedPerBlock;
                target.merge(m.typeId(), amount, Long::sum);
            }
        }

        // Internal flows must be exactly balanced at this point.
        for (long x : internalSorted) {
            long prod = producedPerBlock.getOrDefault(x, 0L);
            long cons = consumedPerBlock.getOrDefault(x, 0L);
            if (prod != cons) {
                throw new IllegalStateException("internal commodity " + x
                        + " not balanced per block: produced " + prod + ", consumed " + cons);
            }
        }

        Map<Long, Long> external = new TreeMap<>();
        for (Map.Entry<Long, Long> e : consumedPerBlock.entrySet()) {
            long deficit = e.getValue() - producedPerBlock.getOrDefault(e.getKey(), 0L);
            if (deficit > 0) {
                external.put(e.getKey(), deficit);
            }
        }
        Map<Long, Long> outputs = new TreeMap<>();
        for (Map.Entry<Long, Long> e : producedPerBlock.entrySet()) {
            long surplus = e.getValue() - consumedPerBlock.getOrDefault(e.getKey(), 0L);
            if (surplus > 0) {
                outputs.put(e.getKey(), surplus);
            }
        }

        // Internal flow details with utilization.
        Fraction perHour = Fraction.of(3600, 1);
        Fraction blockFraction = Fraction.of(1, blockDuration);
        List<SustainableProductionPlan.InternalFlow> flows = new ArrayList<>();
        for (long x : internalSorted) {
            Fraction capacity = produceFullRate.get(x).multiply(perHour);
            Fraction sustainable = Fraction.of(producedPerBlock.getOrDefault(x, 0L), 1)
                    .multiply(blockFraction).multiply(perHour);
            Fraction utilization = sustainable.divide(capacity);
            flows.add(new SustainableProductionPlan.InternalFlow(
                    x,
                    producedPerBlock.getOrDefault(x, 0L),
                    consumedPerBlock.getOrDefault(x, 0L),
                    utilization, capacity, sustainable));
        }

        // Facility group utilizations.
        List<SustainableProductionPlan.GroupUtilization> utils = new ArrayList<>();
        for (Map.Entry<Long, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            utils.add(new SustainableProductionPlan.GroupUtilization(
                    e.getKey(), g.schematic().name(), g.count(),
                    g.schematic().cycleTimeSeconds(),
                    cyclesPerFacility.get(e.getKey()), u.get(e.getKey())));
        }

        // Bottlenecks: commodities that throttled consumers AND whose producers
        // are still running at 100% at the fixpoint (capacity genuinely binding).
        List<SustainableProductionPlan.Bottleneck> bottlenecks = new ArrayList<>();
        for (long x : internalSorted) {
            if (!throttledEver.contains(x)) {
                continue;
            }
            boolean producersSaturated = true;
            for (Map.Entry<Long, Map<Long, Fraction>> e : groupProduce.entrySet()) {
                if (e.getValue().containsKey(x) && !u.get(e.getKey()).isOne()) {
                    producersSaturated = false;
                    break;
                }
            }
            if (producersSaturated) {
                bottlenecks.add(new SustainableProductionPlan.Bottleneck(
                        x,
                        produceFullRate.get(x).multiply(perHour),
                        consumeFullRate.get(x).multiply(perHour)));
            }
        }

        return new SustainableProductionPlan(blockDuration, external, outputs,
                flows, utils, bottlenecks);
    }

    /**
     * Smallest positive duration at which every facility completes a whole
     * number of cycles at its utilization: for u = n/d (reduced) and cycle
     * time c, the duration must be a multiple of {@code (d*c)/gcd(d*c, n)}.
     */
    private static long blockDuration(Map<Long, Group> groups, Map<Long, Fraction> u) {
        BigInteger duration = BigInteger.ONE;
        for (Map.Entry<Long, Group> e : groups.entrySet()) {
            Fraction util = u.get(e.getKey());
            long cycle = e.getValue().schematic().cycleTimeSeconds();
            BigInteger dc = util.denominator().multiply(BigInteger.valueOf(cycle));
            BigInteger need = dc.divide(dc.gcd(util.numerator()));
            duration = duration.multiply(need).divide(duration.gcd(need));
        }
        if (duration.compareTo(BigInteger.valueOf(MAX_BLOCK_DURATION_SECONDS)) > 0) {
            throw new TemplateException.UnsupportedTemplate(
                    "sustainable block duration " + duration + "s exceeds supported bound "
                            + MAX_BLOCK_DURATION_SECONDS + "s");
        }
        return duration.longValueExact();
    }
}
