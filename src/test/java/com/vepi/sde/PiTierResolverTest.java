package com.vepi.sde;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier derivation from the SDE recipe graph (no hand-written name lists):
 * raw = P0, P1 from raws, P2 from P1, P3 from P2, P4 from P3.
 */
class PiTierResolverTest {

    private static SdeRepository sde;
    private static PiTierResolver tiers;

    @BeforeAll
    static void setUp() {
        sde = new SdeRepository("data/sde/pi-sde.db");
        tiers = new PiTierResolver(sde);
    }

    @AfterAll
    static void tearDown() {
        sde.close();
    }

    @Test
    void rawResourcesAreTier0() {
        assertEquals(0, tiers.tierOf(2268), "Aqueous Liquids is a raw planetary resource");
    }

    @Test
    void p2CommoditiesAreTier2() {
        assertEquals(2, tiers.tierOf(sde.findCommodityByName("Biocells").orElseThrow().typeId()));
        assertEquals(2, tiers.tierOf(sde.findCommodityByName("Oxides").orElseThrow().typeId()));
        assertEquals(2, tiers.tierOf(sde.findCommodityByName("Mechanical Parts").orElseThrow().typeId()));
    }

    @Test
    void p3CommoditiesAreTier3() {
        assertEquals(3, tiers.tierOf(2348), "Gel-Matrix Biopaste");
        assertEquals(3, tiers.tierOf(2366), "Hazmat Detection Systems");
        assertEquals(3, tiers.tierOf(9846), "Planetary Vehicles");
    }

    @Test
    void p4CommoditiesAreTier4() {
        assertEquals(4, tiers.tierOf(2868), "Integrity Response Drones");
    }

    @Test
    void nonPiTypeIsMinusOne() {
        // Tritanium is in invTypes but not part of any PI schematic.
        assertEquals(-1, tiers.tierOf(34));
    }
}
