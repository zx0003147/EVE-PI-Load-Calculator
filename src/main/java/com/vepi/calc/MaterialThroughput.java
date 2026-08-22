package com.vepi.calc;

import com.vepi.domain.PiCommodity;

import java.math.BigDecimal;

/**
 * Throughput of one commodity per hour: exact {@link Rate} units and derived m³.
 */
public record MaterialThroughput(PiCommodity commodity, Rate unitsPerHour, BigDecimal m3PerHour) {

    public MaterialThroughput {
        if (m3PerHour == null) {
            m3PerHour = unitsPerHour.toBigDecimal().multiply(commodity.volume());
        }
    }

    public long typeId() { return commodity.typeId(); }

    @Override
    public String toString() {
        return commodity.name() + ": " + unitsPerHour.toDisplayString()
                + " units/h (" + m3PerHour.stripTrailingZeros().toPlainString() + " m3/h)";
    }
}
