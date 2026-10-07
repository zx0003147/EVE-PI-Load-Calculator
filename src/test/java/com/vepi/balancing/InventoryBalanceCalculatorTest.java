package com.vepi.balancing;

import com.vepi.domain.PiCommodity;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.inventory.InventorySnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §24 scenarios A–H for the Balance Inventory core, pure domain logic —
 * no SDE, no Swing. Every number is exact integer/BigDecimal arithmetic.
 */
class InventoryBalanceCalculatorTest {

    private final InventoryBalanceCalculator calculator = new InventoryBalanceCalculator();

    // ---- synthetic commodity/tier lookups (no SDE dependency) ----

    /** name = "Item<typeId>", volume 1 m3, tier from the map (default 99). */
    private PiCommodity commodity(long typeId) {
        return new PiCommodity(typeId, "Item" + typeId, BigDecimal.ONE);
    }

    private LongToIntFunction tiers(Map<Long, Integer> tierByType) {
        return typeId -> tierByType.getOrDefault(typeId, 99);
    }

    /** plan.simple with every external marked P2 and outputs P4. */
    private SustainableProductionPlan p2Plan(Map<Long, Long> perBlock, Map<Long, Long> outputs) {
        return SustainableProductionPlan.simple(3600, new TreeMap<>(perBlock), new TreeMap<>(outputs));
    }

    private InventoryBalanceMaterial material(InventoryBalancePlan plan, long typeId) {
        return plan.materials().stream()
                .filter(m -> m.commodity().typeId() == typeId)
                .findFirst().orElseThrow();
    }

    // ---- A: equal-ratio example straight from spec §6 ----

    @Test
    void scenarioA_equalRatio_blocksFromMaxCeil() {
        // template needs A=2, B=1, C=3; stock 200/80/300
        Map<Long, Long> req = Map.of(101L, 2L, 102L, 1L, 103L, 3L);
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of(901L, 5L)),
                new InventorySnapshot(Map.of(101L, 200L, 102L, 80L, 103L, 300L)),
                tiers(Map.of(101L, 2, 102L, 2, 103L, 2)), this::commodity);

        assertEquals(100, plan.targetBlocks());
        assertEquals(200, material(plan, 101L).targetQuantity());
        assertEquals(0, material(plan, 101L).addQuantity());
        assertEquals(100, material(plan, 102L).targetQuantity());
        assertEquals(20, material(plan, 102L).addQuantity());
        assertEquals(300, material(plan, 103L).targetQuantity());
        assertEquals(0, material(plan, 103L).addQuantity());

        // whole-plan rollups
        assertEquals(360000, plan.productionTimeSeconds());
        // only B must be topped up: 20 units x 1 m3 = 20 m3 additional
        assertEquals(0, new BigDecimal("20").compareTo(plan.totalAdditionalVolume()));
        // expected output scales with blocks
        assertEquals(500, plan.expectedFinalOutputs().get(0).quantity());
    }

    // ---- B: the real equal-requirement case (60 of each of 9 P2) ----

    @Test
    void scenarioB_sixtyPerBlock_oxidesDrives1238Blocks() {
        // Pandogodzilla shape: 9 P2 at 60/block (typeIDs are the real ones)
        long[] p2 = {2329L, 3689L, 9842L, 2317L, 3695L, 9840L, 9838L, 2312L, 3775L};
        TreeMap<Long, Long> req = new TreeMap<>();
        for (long t : p2) {
            req.put(t, 60L);
        }
        Map<Long, Integer> tier = new TreeMap<>();
        for (long t : p2) {
            tier.put(t, 2);
        }

        // the real user inventory from the spec (§10): Oxides 74225 is the max
        TreeMap<Long, Long> stock = new TreeMap<>();
        stock.put(2329L, 46080L);
        stock.put(3689L, 40594L);
        stock.put(9842L, 61830L);
        stock.put(2317L, 74225L);
        stock.put(3695L, 70909L);
        stock.put(9840L, 31390L);
        stock.put(9838L, 51110L);
        stock.put(2312L, 65939L);
        stock.put(3775L, 72134L);

        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of(2868L, 3L)),
                new InventorySnapshot(stock),
                tiers(tier), this::commodity);

        assertEquals(1238, plan.targetBlocks(), "ceil(74225 / 60)");
        assertEquals(74280, material(plan, 2317L).targetQuantity());
        assertEquals(55, material(plan, 2317L).addQuantity());
        assertEquals(74280, material(plan, 2329L).targetQuantity());
        assertEquals(28200, material(plan, 2329L).addQuantity());
        assertEquals(74280, material(plan, 9840L).targetQuantity());
        assertEquals(42890, material(plan, 9840L).addQuantity());
        // production time: 1238 x 3600s = 51d 14h in seconds = 4456800s
        assertEquals(1238L * 3600L, plan.productionTimeSeconds());
        // expected output: 3 IRD/block x 1238
        assertEquals(3714, plan.expectedFinalOutputs().get(0).quantity());
    }

    // ---- C: different ratios — never compare absolute stock sizes ----

    @Test
    void scenarioC_differentRatings_usePerBlockNotAbsoluteStock() {
        // A=20, B=60, C=40; stock 1000/1200/4000 -> 50/20/100 blocks -> 100
        Map<Long, Long> req = Map.of(201L, 20L, 202L, 60L, 203L, 40L);
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of()),
                new InventorySnapshot(Map.of(201L, 1000L, 202L, 1200L, 203L, 4000L)),
                tiers(Map.of(201L, 2, 202L, 2, 203L, 2)), this::commodity);

        assertEquals(100, plan.targetBlocks(), "C drives: ceil(4000/40), not the biggest pile");
        assertEquals(2000, material(plan, 201L).targetQuantity());
        assertEquals(6000, material(plan, 202L).targetQuantity());
        assertEquals(4000, material(plan, 203L).targetQuantity());
    }

    // ---- D: missing inventory item behaves like current = 0 ----

    @Test
    void scenarioD_missingInventory_addsFullRequirement() {
        Map<Long, Long> req = Map.of(301L, 10L, 302L, 30L);
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of()),
                new InventorySnapshot(Map.of(301L, 250L)),   // no 302 at all
                tiers(Map.of(301L, 2, 302L, 2)), this::commodity);

        assertEquals(25, plan.targetBlocks());   // ceil(250/10); current[302]=0
        assertEquals(750, material(plan, 302L).targetQuantity());
        assertEquals(750, material(plan, 302L).addQuantity());
    }

    // ---- E: irrelevant P2 never inflates targetBlocks ----

    @Test
    void scenarioE_irrelevantP2_goesToUnusedInventory() {
        Map<Long, Long> req = Map.of(401L, 2L);
        // D has a huge stock that must NOT drive targetBlocks
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of()),
                new InventorySnapshot(Map.of(401L, 200L, 402L, 100_000L)),
                tiers(Map.of(401L, 2, 402L, 2)), this::commodity);

        assertEquals(100, plan.targetBlocks(), "only the used P2 counts");
        assertEquals(1, plan.materials().size());
        assertEquals(1, plan.unusedInventory().size());
        assertEquals(402L, plan.unusedInventory().get(0).commodity().typeId());
        assertEquals(100_000L, plan.unusedInventory().get(0).quantity());
        assertEquals(2, plan.unusedInventory().get(0).tier(),
                "unused P2 is still reported as P2");
    }

    // ---- F: irrelevant P3 stock is unused and never changes the P2 balance ----

    @Test
    void scenarioF_p3StockIgnored_sameResultWithAndWithout() {
        Map<Long, Long> req = Map.of(501L, 3L);
        Map<Long, Integer> tier = Map.of(501L, 2, 601L, 3);
        InventorySnapshot withoutP3 = new InventorySnapshot(Map.of(501L, 90L));
        InventorySnapshot withP3 = new InventorySnapshot(Map.of(501L, 90L, 601L, 41L));

        InventoryBalancePlan a = calculator.calculate(p2Plan(req, Map.of()), withoutP3,
                tiers(tier), this::commodity);
        InventoryBalancePlan b = calculator.calculate(p2Plan(req, Map.of()), withP3,
                tiers(tier), this::commodity);

        assertEquals(a.targetBlocks(), b.targetBlocks());
        assertEquals(a.materials(), b.materials());
        // ...but the P3 shows up as unused so the UI can still display it.
        assertEquals(1, b.unusedInventory().size());
        assertEquals(601L, b.unusedInventory().get(0).commodity().typeId());
        assertEquals(41L, b.unusedInventory().get(0).quantity());
        assertTrue(a.unusedInventory().isEmpty());
    }

    // ---- G: pure P3→P4 template balances its actual external inputs ----

    @Test
    void scenarioG_p3Only_balancesP3WithoutInventingP2() {
        SustainableProductionPlan plan = SustainableProductionPlan.simple(3600,
                new TreeMap<>(Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L)),
                new TreeMap<>(Map.of(2868L, 8L)));
        InventorySnapshot stock = new InventorySnapshot(Map.of(2348L, 100L, 2366L, 49L));

        InventoryBalancePlan result = calculator.calculate(plan, stock,
                tiers(Map.of(2348L, 3, 2366L, 3, 9846L, 3)), this::commodity);

        assertTrue(result.p2Balance().materials().isEmpty());
        assertEquals(3, result.p3Balance().targetBlocks(), "ceil(100/48)");
        assertEquals(3, result.p3Balance().materials().size());
        assertEquals(144, result.p3Balance().materials().get(0).targetQuantity());
        assertEquals(24, result.expectedFinalOutputs().get(0).quantity());
    }

    @Test
    void p2AndP3_targetBlocksAreIndependent() {
        SustainableProductionPlan plan = SustainableProductionPlan.simple(3600,
                new TreeMap<>(Map.of(101L, 10L, 201L, 4L)), new TreeMap<>());
        InventoryBalancePlan result = calculator.calculate(plan,
                new InventorySnapshot(Map.of(101L, 25L, 201L, 400L)),
                tiers(Map.of(101L, 2, 201L, 3)), this::commodity);

        assertEquals(3, result.p2Balance().targetBlocks());
        assertEquals(100, result.p3Balance().targetBlocks());
        assertEquals(30, result.p2Balance().materials().get(0).targetQuantity());
        assertEquals(400, result.p3Balance().materials().get(0).targetQuantity());
    }

    @Test
    void noP3Requirements_returnsEmptyP3Section() {
        InventoryBalancePlan result = calculator.calculate(
                p2Plan(Map.of(101L, 10L), Map.of()),
                new InventorySnapshot(Map.of(101L, 25L)),
                tiers(Map.of(101L, 2)), this::commodity);

        assertFalse(result.p3Balance().hasRequirements());
        assertEquals(0, result.p3Balance().targetBlocks());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.p3Balance().totalAdditionalVolume()));
    }

    @Test
    void requiredP3WithNoP3Stock_targetsZeroAndBuysNothing() {
        SustainableProductionPlan plan = SustainableProductionPlan.simple(3600,
                new TreeMap<>(Map.of(201L, 4L)), new TreeMap<>());
        InventoryBalancePlan result = calculator.calculate(plan,
                new InventorySnapshot(Map.of()), tiers(Map.of(201L, 3)), this::commodity);

        assertEquals(0, result.p3Balance().targetBlocks());
        assertEquals(0, result.p3Balance().materials().get(0).addQuantity());
    }

    // ---- H: exact arithmetic on big numbers (no overflow, exact volumes) ----

    @Test
    void scenarioH_exactArithmetic_largeNumbers() {
        long big = 9_000_000_000L;   // beyond int range
        Map<Long, Long> req = Map.of(701L, 3L);
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of()),
                new InventorySnapshot(Map.of(701L, big)),
                tiers(Map.of(701L, 2)),
                typeId -> new PiCommodity(typeId, "Item" + typeId,
                        new BigDecimal("0.75")));   // real P2 unit volume

        assertEquals(3_000_000_000L, plan.targetBlocks());
        assertEquals(big + 0L, material(plan, 701L).targetQuantity());
        assertEquals(0, material(plan, 701L).addQuantity());
        // add volume stays exactly zero even at billions of units
        assertEquals(0, BigDecimal.ZERO.compareTo(plan.totalAdditionalVolume()));
        // and the target volume is exact: 9e9 x 0.75
        assertEquals(0, new BigDecimal("6750000000.00").compareTo(plan.totalTargetVolume()));
    }

    // ---- empty inventory is valid: everything must be bought ----

    @Test
    void emptyInventory_allAddsFullTarget() {
        Map<Long, Long> req = Map.of(801L, 5L, 802L, 15L);
        InventoryBalancePlan plan = calculator.calculate(
                p2Plan(req, Map.of()),
                new InventorySnapshot(Map.of()),   // nothing owned yet
                tiers(Map.of(801L, 2, 802L, 2)), this::commodity);

        assertEquals(0, plan.targetBlocks(), "no stock to cover -> no blocks needed");
        List<InventoryBalanceMaterial> mats = plan.materials();
        assertEquals(0, mats.get(0).currentQuantity());
        assertEquals(0, mats.get(0).addQuantity(), "target is 0 too — nothing to buy yet");
    }
}
