package com.vepi.allocation;

import com.vepi.domain.PiCommodity;

/**
 * A final / surplus output commodity with the quantity this planet will produce
 * under its allocated block count.
 */
public record ExpectedOutput(PiCommodity commodity, long quantity) {

    @Override
    public String toString() {
        return commodity.name() + " (" + commodity.typeId() + "): " + quantity;
    }
}
