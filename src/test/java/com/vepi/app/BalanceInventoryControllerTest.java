package com.vepi.app;

import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.balancing.P4BalanceRecipe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests of inventory + P4 typeID -> independent SDE-backed balances. */
class BalanceInventoryControllerTest {
    private static final long IRD = 2868L;
    private static PiCalculatorController core;
    private static BalanceInventoryController controller;

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

    @BeforeAll static void setUp() {
        core = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        controller = new BalanceInventoryController(core);
    }

    @AfterAll static void tearDown() { core.close(); }

    @Test
    void p4Products_areTier4UniqueSortedAndTypeIdBacked() {
        List<BalanceInventoryController.P4Product> products = controller.p4Products();
        assertFalse(products.isEmpty());
        assertEquals(products.size(), products.stream().map(p -> p.typeId()).distinct().count());
        for (int i = 1; i < products.size(); i++) {
            assertTrue(products.get(i - 1).name().compareToIgnoreCase(products.get(i).name()) <= 0);
        }
        var ird = products.stream().filter(p -> p.typeId() == IRD).findFirst().orElseThrow();
        assertEquals("Integrity Response Drones", ird.name());
        assertEquals(ird.name(), ird.toString());
        assertTrue(products.stream().allMatch(p -> core.tierOf(p.typeId()) == 4));
    }

    @Test
    void integrityResponseDrones_resolvesDirectP3AndExpandedP2() {
        InventoryBalancePlan plan = controller.balance(
                controller.parseInventory(REAL_INVENTORY).snapshot(), IRD);

        assertEquals(9, plan.p2Balance().materials().size());
        assertEquals(3, plan.p3Balance().materials().size());
        assertEquals(3_712, plan.p2Balance().targetBlocks());
        assertEquals(255, plan.p3Balance().targetBlocks());
        assertTrue(plan.p3Balance().materials().stream().allMatch(m -> m.requiredPerBlock() == 6));
        assertTrue(plan.p2Balance().materials().stream().allMatch(m -> m.requiredPerBlock() == 20));
        assertEquals(1, plan.p2Balance().p4RecipeCyclesPerBalanceBlock());
        assertEquals(1, plan.p2Balance().p4UnitsPerBalanceBlock());
        assertEquals(3_712, plan.p2Balance().equivalentP4Units());
        assertEquals(255, plan.p3Balance().equivalentP4Units());
    }

    @Test
    void integrityResponseDrones_exposesReadableP4ToP3ToP2Hierarchy() {
        P4BalanceRecipe recipe = controller.recipe(IRD);
        assertEquals("Integrity Response Drones", recipe.p2Hierarchy().p4Product().name());
        assertEquals(1, recipe.p2Hierarchy().p4RecipeCycles());
        assertEquals(1, recipe.p2Hierarchy().p4Quantity());

        Map<String, P4BalanceRecipe.P3Branch> branches = recipe.p2Hierarchy().p3Branches().stream()
                .collect(Collectors.toMap(b -> b.commodity().name(), b -> b));
        assertEquals(6, branches.get("Gel-Matrix Biopaste").quantity());
        assertEquals(Map.of("Biocells", 20L, "Oxides", 20L, "Superconductors", 20L),
                namedInputs(branches.get("Gel-Matrix Biopaste")));
        assertEquals(6, branches.get("Hazmat Detection Systems").quantity());
        assertEquals(Map.of("Polytextiles", 20L, "Transmitter", 20L, "Viral Agent", 20L),
                namedInputs(branches.get("Hazmat Detection Systems")));
        assertEquals(6, branches.get("Planetary Vehicles").quantity());
        assertEquals(Map.of("Mechanical Parts", 20L, "Miniature Electronics", 20L,
                        "Supertensile Plastics", 20L),
                namedInputs(branches.get("Planetary Vehicles")));
    }

    @Test
    void everyP4HierarchyLeafTotalMatchesItsP2BalanceRequirements() {
        for (var product : controller.p4Products()) {
            P4BalanceRecipe recipe = controller.recipe(product.typeId());
            assertEquals(recipe.p2Requirements(), recipe.p2Hierarchy().p2LeafTotals(),
                    () -> product.name() + " tree does not match P2 Per Block values");
            assertEquals(recipe.p3Requirements(), recipe.p3Hierarchy().p3Totals(),
                    () -> product.name() + " P3 tree does not match P3 Per Block values");
        }
    }

    @Test
    void secondRealP4_hasExecutableP2BatchesAndIntegerP4Output() {
        long another = controller.p4Products().stream()
                .mapToLong(BalanceInventoryController.P4Product::typeId)
                .filter(id -> id != IRD).findFirst().orElseThrow();
        P4BalanceRecipe recipe = controller.recipe(another);
        long k = recipe.p2P4RecipeCyclesPerBlock();
        for (var p3 : recipe.p3Recipes()) {
            long required = recipe.p3Requirements().get(p3.output().typeId());
            assertEquals(0, Math.multiplyExact(k, required) % p3.outputQuantity(),
                    () -> p3.output().name() + " must use whole schematic batches");
        }
        assertEquals(Math.multiplyExact(k, recipe.p4OutputQuantityPerCycle()),
                recipe.p2P4UnitsPerBlock());
    }

    @Test
    void changingOnlyP3_neverChangesP2() {
        InventoryBalancePlan a = controller.balance(
                controller.parseInventory(REAL_INVENTORY).snapshot(), IRD);
        String changed = REAL_INVENTORY
                .replace("Gel-Matrix Biopaste 1527", "Gel-Matrix Biopaste 999999")
                .replace("Hazmat Detection Systems 41", "Hazmat Detection Systems 888888")
                .replace("Planetary Vehicles 688", "Planetary Vehicles 777777");
        InventoryBalancePlan b = controller.balance(controller.parseInventory(changed).snapshot(), IRD);
        assertEquals(a.p2Balance(), b.p2Balance());
        assertNotEquals(a.p3Balance().targetBlocks(), b.p3Balance().targetBlocks());
    }

    @Test
    void changingOnlyP2_neverChangesP3() {
        InventoryBalancePlan a = controller.balance(
                controller.parseInventory(REAL_INVENTORY).snapshot(), IRD);
        String changed = REAL_INVENTORY.replace("Oxides 74225", "Oxides 999999");
        InventoryBalancePlan b = controller.balance(controller.parseInventory(changed).snapshot(), IRD);
        assertEquals(a.p3Balance(), b.p3Balance());
        assertNotEquals(a.p2Balance().targetBlocks(), b.p2Balance().targetBlocks());
    }

    @Test
    void noRelatedStock_keepsBothTargetsAndPurchasesAtZero() {
        var snapshot = controller.parseInventory("Water 100").snapshot();
        InventoryBalancePlan plan = controller.balance(snapshot, IRD);
        assertEquals(0, plan.p2Balance().targetBlocks());
        assertEquals(0, plan.p3Balance().targetBlocks());
        assertTrue(plan.p2Balance().materials().stream().allMatch(m -> m.addQuantity() == 0));
        assertTrue(plan.p3Balance().materials().stream().allMatch(m -> m.addQuantity() == 0));
    }

    private static Map<String, Long> namedInputs(P4BalanceRecipe.P3Branch branch) {
        return branch.p2Inputs().stream().collect(Collectors.toMap(
                input -> input.commodity().name(), P4BalanceRecipe.Ingredient::quantity));
    }
}
