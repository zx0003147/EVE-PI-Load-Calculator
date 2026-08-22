package com.vepi.app;

import com.vepi.balancing.BalanceException;
import com.vepi.balancing.InventoryBalancePlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end seam test of the Balance Inventory feature: pasted inventory
 * text + pasted template JSON → InventoryBalancePlan, through the real SDE.
 * Verifies the spec's own real-world numbers (§10, §27).
 */
class BalanceInventoryControllerTest {

    private static PiCalculatorController core;
    private static BalanceInventoryController controller;
    private static String pandogodzilla;

    /** The real 12-line user inventory: 9 P2 + 3 P3 (spec §4.1). */
    private static final String REAL_INVENTORY = """
            Biocells 46080
            Mechanical Parts 40594
            Miniature Electronics 61830
            Oxides 74225
            Polytextiles 70909
            Superconductors 51110
            Supertensile Plastics 65939
            Transmitter 31390
            Viral Agent 72134
            Gel-Matrix Biopaste 1527
            Hazmat Detection Systems 41
            Planetary Vehicles 688
            """;

    @BeforeAll
    static void setUp() throws Exception {
        core = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        controller = new BalanceInventoryController(core);
        pandogodzilla = Files.readString(Path.of("data/templates/Pandogodzilla.json"));
    }

    @AfterAll
    static void tearDown() {
        core.close();
    }

    @Test
    void realInventory_plusPandogodzilla_yieldsSpecNumbers() {
        InventoryBalancePlan plan = controller.balance(REAL_INVENTORY, pandogodzilla);

        // the spec's own arithmetic: ceil(74225/60) = 1238 blocks
        assertEquals(1238, plan.targetBlocks());
        assertEquals(3600, plan.blockDurationSeconds());
        assertEquals(9, plan.materials().size(), "exactly the 9 P2 inputs");

        InventoryBalancePlan.ExpectedOutput ird = plan.expectedFinalOutputs().stream()
                .filter(o -> o.commodity().typeId() == 2868L).findFirst().orElseThrow();
        assertEquals(3714, ird.quantity(), "3 IRD/block x 1238");

        // Oxides row: 74225 -> 74280 -> add 55 (spec §10)
        var oxides = plan.materials().stream()
                .filter(m -> m.commodity().typeId() == 2317L).findFirst().orElseThrow();
        assertEquals(60, oxides.requiredPerBlock());
        assertEquals(74225, oxides.currentQuantity());
        assertEquals(74280, oxides.targetQuantity());

        // every Need-to-Add value from spec §10, keyed by typeID
        Map<Long, Long> expectedAdds = Map.of(
                2329L, 28200L,   // Biocells
                3689L, 33686L,   // Mechanical Parts
                9842L, 12450L,   // Miniature Electronics
                2317L, 55L,      // Oxides
                3695L, 3371L,    // Polytextiles
                9840L, 42890L,   // Transmitter
                9838L, 23170L,   // Superconductors
                2312L, 8341L,    // Supertensile Plastics
                3775L, 2146L);   // Viral Agent
        for (var m : plan.materials()) {
            assertEquals(expectedAdds.get(m.commodity().typeId()), m.addQuantity(),
                    () -> "add for " + m.commodity().name());
        }

        // the 3 P3 items in stock are unused by the P2 balance
        assertEquals(3, plan.unusedInventory().size());
        assertTrue(plan.unusedInventory().stream().anyMatch(u -> u.commodity().typeId() == 2366L));
    }

    @Test
    void p3StockLevel_neverChangesTheP2Balance() {
        String withoutP3 = REAL_INVENTORY
                .replace("Gel-Matrix Biopaste 1527\n", "")
                .replace("Hazmat Detection Systems 41\n", "")
                .replace("Planetary Vehicles 688\n", "");

        InventoryBalancePlan a = controller.balance(REAL_INVENTORY, pandogodzilla);
        InventoryBalancePlan b = controller.balance(withoutP3, pandogodzilla);

        assertEquals(a.targetBlocks(), b.targetBlocks());
        assertEquals(a.materials(), b.materials());
        assertEquals(3, a.unusedInventory().size());
        assertEquals(0, b.unusedInventory().size());
    }

    @Test
    void pureP4Template_isRejected() throws Exception {
        String ird = Files.readString(Path.of("data/templates/IntegrityResponseDrones.json"));
        assertThrows(BalanceException.NoP2Requirements.class,
                () -> controller.balance("Biocells 100", ird));
    }

    @Test
    void hasP2Chain_distinguishesTemplates() throws Exception {
        assertTrue(controller.hasP2Chain(controller.loadTemplate(pandogodzilla).plan()));
        String ird = Files.readString(Path.of("data/templates/IntegrityResponseDrones.json"));
        assertFalse(controller.hasP2Chain(controller.loadTemplate(ird).plan()));
    }
}
