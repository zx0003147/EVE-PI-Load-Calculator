package com.vepi.sde;

import com.vepi.domain.PiCommodity;
import com.vepi.domain.PiSchematic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDE tests against the real pi-sde.db (Fuzzwork conversion v3475087).
 * Verifies schematic 118 (Integrity Response Drones): cycle time, inputs, outputs,
 * commodity volumes, and facility-type recognition.
 */
class SdeRepositoryTest {

    private static final String DB = "data/sde/pi-sde.db";
    private static SdeRepository sde;

    @BeforeAll
    static void setUp() {
        sde = new SdeRepository(DB);
    }

    @AfterAll
    static void tearDown() { sde.close(); }

    @Test
    void schematic118_hasCorrectCycleTime() {
        PiSchematic s = sde.getSchematic(118);
        assertEquals("Integrity Response Drones", s.name());
        assertEquals(3600L, s.cycleTimeSeconds());
    }

    @Test
    void schematic118_inputsCorrect() {
        PiSchematic s = sde.getSchematic(118);
        Map<Long, Integer> inputs = s.inputs().stream()
                .collect(Collectors.toMap(m -> m.typeId(), m -> m.quantity()));
        // 3 P3 inputs, 6 each
        assertEquals(6, inputs.get(2348L), "Gel-Matrix Biopaste");
        assertEquals(6, inputs.get(2366L), "Hazmat Detection Systems");
        assertEquals(6, inputs.get(9846L), "Planetary Vehicles");
        assertEquals(3, inputs.size());
    }

    @Test
    void schematic118_outputCorrect() {
        PiSchematic s = sde.getSchematic(118);
        List<Long> outputs = s.outputs().stream().map(m -> m.typeId()).toList();
        assertTrue(outputs.contains(2868L), "must output Integrity Response Drones (2868)");
        assertEquals(1, s.outputs().get(0).quantity(), "1 IRD per cycle");
    }

    @Test
    void commodityVolumesCorrect() {
        PiCommodity ird = sde.getCommodity(2868L);
        assertEquals("Integrity Response Drones", ird.name());
        assertEquals(0, new java.math.BigDecimal("50").compareTo(ird.volume()),
                "IRD volume must be 50 m³");

        PiCommodity gmb = sde.getCommodity(2348L);
        assertEquals("Gel-Matrix Biopaste", gmb.name());
        assertEquals(0, new java.math.BigDecimal("3").compareTo(gmb.volume()),
                "Gel-Matrix Biopaste volume must be 3 m³");
    }

    @Test
    void findSchematicByOutput_resolves118() {
        Optional<Long> sid = sde.findSchematicByOutput(2868L);
        assertTrue(sid.isPresent());
        assertEquals(118L, sid.get());
    }

    @Test
    void facilityRecognition() {
        // 2475 = Barren High-Tech Production Plant (runs schematic 118)
        assertTrue(sde.isFacilityType(2475L));
        // 2544 = Barren Launchpad (NOT a production facility)
        assertFalse(sde.isFacilityType(2544L));
        // 2475 is among the facility types for schematic 118
        assertTrue(sde.facilityTypesForSchematic(118L).contains(2475L));
    }

    @Test
    void missingSchematicThrows_notSwallowed() {
        assertThrows(SdeException.MissingSchematic.class, () -> sde.getSchematic(99999L));
    }

    @Test
    void missingSdeTypeThrows() {
        assertThrows(SdeException.MissingSdeType.class, () -> sde.getCommodity(1L));
    }
}
