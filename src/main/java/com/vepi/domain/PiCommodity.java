package com.vepi.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A PI commodity resolved from the SDE invTypes table.
 *
 * <p>Immutable. {@code volume} is kept as a {@link BigDecimal} so m³ calculations stay exact
 * (PI commodity volumes are exact decimals such as 0.01, 3.0, 50.0).
 */
public final class PiCommodity {
    private final long typeId;
    private final String name;
    private final BigDecimal volume;

    public PiCommodity(long typeId, String name, BigDecimal volume) {
        this.typeId = typeId;
        this.name = Objects.requireNonNull(name, "name");
        this.volume = volume == null ? BigDecimal.ZERO : volume;
    }

    public long typeId() { return typeId; }
    public String name() { return name; }
    public BigDecimal volume() { return volume; }

    @Override
    public String toString() {
        return name + " (" + typeId + ", " + volume.stripTrailingZeros().toPlainString() + " m³)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PiCommodity c)) return false;
        return typeId == c.typeId;
    }

    @Override
    public int hashCode() { return Long.hashCode(typeId); }
}
