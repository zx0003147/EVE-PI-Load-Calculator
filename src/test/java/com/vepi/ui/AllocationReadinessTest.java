package com.vepi.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec F: the Calculate Allocation enablement rule — pure logic, no Swing.
 * Empty inventory / invalid capacity / template not loaded / no planets must
 * each keep Calculate disabled.
 */
class AllocationReadinessTest {

    private static final AllocationReadiness.PlanetInput READY_PLANET =
            new AllocationReadiness.PlanetInput(true, true);

    @Test
    void emptyInventory_disablesEverything() {
        assertFalse(AllocationReadiness.ready(false, List.of(READY_PLANET)));
    }

    @Test
    void noPlanets_disablesCalculation() {
        assertFalse(AllocationReadiness.ready(true, List.of()));
        assertFalse(AllocationReadiness.ready(true, null));
    }

    @Test
    void templateNotLoaded_disablesCalculation() {
        assertFalse(AllocationReadiness.ready(true,
                List.of(new AllocationReadiness.PlanetInput(false, true))));
    }

    @Test
    void invalidCapacity_disablesCalculation() {
        assertFalse(AllocationReadiness.ready(true,
                List.of(new AllocationReadiness.PlanetInput(true, false))));
    }

    @Test
    void oneBadPlanetAmongMany_disablesCalculation() {
        assertFalse(AllocationReadiness.ready(true,
                List.of(READY_PLANET, new AllocationReadiness.PlanetInput(false, true))));
    }

    @Test
    void happyPath_allConditionsMet_enablesCalculation() {
        assertTrue(AllocationReadiness.ready(true,
                List.of(READY_PLANET, READY_PLANET, READY_PLANET)));
    }
}
