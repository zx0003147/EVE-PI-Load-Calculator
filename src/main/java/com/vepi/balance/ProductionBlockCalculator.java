package com.vepi.balance;

import com.vepi.domain.PiSchematic;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.template.TemplateException;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the {@link ProductionBlock} of a template from its SDE-resolved facilities.
 *
 * <p>Algorithm (pure integer arithmetic, no floating point anywhere):
 * <ol>
 *   <li>Base period = LCM of all facility cycle times. Each facility then runs
 *       {@code basePeriod / cycleTime} whole cycles per block.</li>
 *   <li>For every facility, add {@code recipeQuantity x cyclesInBase} to the
 *       produced / consumed counters of the block.</li>
 * </ol>
 *
 * <p>Recipe data comes exclusively from the {@link PiSchematic} (SDE); template
 * route quantities are never consulted.
 */
public final class ProductionBlockCalculator {

    /** Defensive bound: PI cycle times are 1800s/3600s, so real LCMs are tiny. */
    private static final long MAX_BASE_PERIOD_SECONDS = 1_000_000_000L;

    private ProductionBlockCalculator() { }

    /**
     * @param facilities SDE-resolved production facilities of one template
     * @return the integer production block over the LCM base period
     * @throws TemplateException.UnsupportedTemplate if there are no production facilities
     */
    public static ProductionBlock calculate(List<TemplateProductionFacility> facilities) {
        if (facilities == null || facilities.isEmpty()) {
            throw new TemplateException.UnsupportedTemplate(
                    "template contains no production facilities — nothing to balance");
        }

        long base = 1;
        for (TemplateProductionFacility f : facilities) {
            base = lcm(base, f.schematic().cycleTimeSeconds());
            if (base > MAX_BASE_PERIOD_SECONDS) {
                throw new TemplateException.UnsupportedTemplate(
                        "cycle-time LCM of template facilities is " + base
                                + "s — exceeds supported bound " + MAX_BASE_PERIOD_SECONDS + "s");
            }
        }

        Map<Long, Long> produced = new TreeMap<>();
        Map<Long, Long> consumed = new TreeMap<>();
        for (TemplateProductionFacility f : facilities) {
            PiSchematic s = f.schematic();
            long cyclesInBase = base / s.cycleTimeSeconds();   // exact: base is the LCM
            for (var m : s.materials()) {
                long amount = Math.multiplyExact(m.quantity(), cyclesInBase);
                Map<Long, Long> target = m.isInput() ? consumed : produced;
                target.merge(m.typeId(), amount, Long::sum);
            }
        }
        return new ProductionBlock(base, produced, consumed);
    }

    private static long lcm(long a, long b) {
        return a / gcd(a, b) * b;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
