package com.vepi.allocation;

import com.vepi.flow.SustainableProductionPlan;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One planet requesting an allocation: its template's
 * {@link SustainableProductionPlan} (P2-only sustainable production model
 * derived from the SDE) plus the storage capacity the player is willing to
 * spend on imported materials on this planet.
 *
 * <p>Capacity is per-planet — there is no global capacity. The same
 * {@link SustainableProductionPlan} may be shared by several planets.
 */
public record PlanetRequest(String name, SustainableProductionPlan plan, BigDecimal capacity) {

    public PlanetRequest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(capacity, "capacity");
        if (capacity.signum() < 0) {
            throw new IllegalArgumentException(
                    "planet '" + name + "' capacity must be >= 0 m3, got " + capacity.toPlainString());
        }
    }
}
