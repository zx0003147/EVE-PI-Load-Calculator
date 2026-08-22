package com.vepi.calc;

import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure calculator tests with synthetic schematics (no SDE / database required).
 * Covers the exact fixtures from the Phase 0 spec, including a 1800s cycle time
 * to prove cyclesPerHour math is exact (no 59.9999999 drift).
 */
class ProductionCalculatorTest {

    /** Build a synthetic schematic. typeID 1=A, 2=B, 3=C unless overridden. */
    private static PiSchematic schematic(long cycleTime, int a, int b, int c) {
        return new PiSchematic(999, "Test", cycleTime,
                List.of(
                        new PiSchematicMaterial(1L, a, true),
                        new PiSchematicMaterial(2L, b, true),
                        new PiSchematicMaterial(3L, c, false)),
                List.of(1L));   // facility type not relevant to the pure calc
    }

    @Test
    void twoFactories_cycle3600_A4_B2_outC1() {
        PiSchematic s = schematic(3600, 4, 2, 1);
        // 2 factories: A=2*4*1=8, B=2*2*1=4, C=2*1*1=2
        Map<Long, Rate> in = ProductionCalculator.ratesFor(2, s, true);
        Map<Long, Rate> out = ProductionCalculator.ratesFor(2, s, false);
        assertEquals("8", in.get(1L).toDisplayString());
        assertEquals("4", in.get(2L).toDisplayString());
        assertEquals("2", out.get(3L).toDisplayString());
    }

    @Test
    void twoFactories_cycle1800_doublesThroughput() {
        PiSchematic s = schematic(1800, 4, 2, 1);
        // cyclesPerHour = 3600/1800 = 2  ->  A=2*4*2=16, B=2*2*2=8, C=2*1*2=4
        Map<Long, Rate> in = ProductionCalculator.ratesFor(2, s, true);
        Map<Long, Rate> out = ProductionCalculator.ratesFor(2, s, false);
        assertEquals("16", in.get(1L).toDisplayString());
        assertEquals("8", in.get(2L).toDisplayString());
        assertEquals("4", out.get(3L).toDisplayString());
        // sanity: the rate is an exact integer
        assertTrue(out.get(3L).isInteger(), "C rate must be exact integer");
    }

    @Test
    void tenFactories_specExample_A6_B6_outC3_cycle3600() {
        // The illustrative example from the spec: inputs A x6, B x6; output C x3; cycle 3600.
        PiSchematic s = new PiSchematic(999, "SpecExample", 3600,
                List.of(
                        new PiSchematicMaterial(1L, 6, true),
                        new PiSchematicMaterial(2L, 6, true),
                        new PiSchematicMaterial(3L, 3, false)),
                List.of(1L));
        // 10 factories -> A=60, B=60, C=30
        Map<Long, Rate> in = ProductionCalculator.ratesFor(10, s, true);
        Map<Long, Rate> out = ProductionCalculator.ratesFor(10, s, false);
        assertEquals("60", in.get(1L).toDisplayString());
        assertEquals("60", in.get(2L).toDisplayString());
        assertEquals("30", out.get(3L).toDisplayString());
    }

    @Test
    void rateStaysExactForNonIntegerCyclesPerHour() {
        // A hypothetical 4000s cycle: cyclesPerHour = 3600/4000 = 0.9 -> 1 factory, qty 10
        // units/hour = 10 * 0.9 = 9 (exact via rational: 10*3600/4000 = 9/1).
        PiSchematic s = new PiSchematic(999, "Fractional", 4000,
                List.of(new PiSchematicMaterial(1L, 10, true)),
                List.of(1L));
        Rate r = ProductionCalculator.ratesFor(1, s, true).get(1L);
        assertEquals("9", r.toDisplayString(), "10 * 3600/4000 must be exactly 9");
    }
}
