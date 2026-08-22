package com.vepi.balance;

import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.template.TemplateException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production block / net flow balance tests. All fixtures are synthetic
 * schematics — the block calculator is pure and needs no SDE.
 *
 * <p>Type IDs used: A=100, B=101, X=200 (intermediate), Y=300 (final),
 * IRD=2868, P3s=2348/2366/9846.
 */
class ProductionBlockCalculatorTest {

    private static PiSchematicMaterial in(long typeId, int qty) {
        return new PiSchematicMaterial(typeId, qty, true);
    }

    private static PiSchematicMaterial out(long typeId, int qty) {
        return new PiSchematicMaterial(typeId, qty, false);
    }

    private static PiSchematic schematic(long id, long cycleTime, PiSchematicMaterial... materials) {
        return new PiSchematic(id, "synthetic-" + id, cycleTime, List.of(materials), List.of());
    }

    private static TemplateProductionFacility facility(PiSchematic s) {
        return new TemplateProductionFacility(0, 2475L, s);
    }

    @Test
    void singleLevel_irdShape_8factories() {
        PiSchematic irdSchematic = schematic(118, 3600,
                in(2348L, 6), in(2366L, 6), in(9846L, 6), out(2868L, 1));
        List<TemplateProductionFacility> facilities =
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> new TemplateProductionFacility(i, 2475L, irdSchematic))
                        .toList();

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(3600L, block.basePeriodSeconds(), "LCM of 8x3600s is 3600s");
        assertEquals(Map.of(2348L, 48L, 2366L, 48L, 9846L, 48L),
                block.externalRequirements(), "8 factories x 6 units per block");
        assertEquals(Map.of(2868L, 8L), block.netOutputs(), "8 IRD per block");
    }

    @Test
    void multiStage_balanced_intermediateNotExternal() {
        // S1: 40xA -> 20xX   |   S2: 20xX + 20xB -> 5xY   (X exactly balanced)
        List<TemplateProductionFacility> facilities = List.of(
                facility(schematic(1, 3600, in(100L, 40), out(200L, 20))),
                facility(schematic(2, 3600, in(200L, 20), in(101L, 20), out(300L, 5))));

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(Map.of(100L, 40L, 101L, 20L), block.externalRequirements(),
                "X has zero external requirement (balanced); only A and B are imported");
        assertEquals(Map.of(300L, 5L), block.netOutputs());
        assertTrue(block.producedPerBlock().containsKey(200L)
                        && block.consumedPerBlock().containsKey(200L),
                "X is tracked as both produced and consumed");
    }

    @Test
    void multiStage_deficit_intermediateNeedsExternalSupply() {
        // S1: 40xA -> 16xX   |   S2: 20xX + 20xB -> 5xY   (X deficit = 4)
        List<TemplateProductionFacility> facilities = List.of(
                facility(schematic(1, 3600, in(100L, 40), out(200L, 16))),
                facility(schematic(2, 3600, in(200L, 20), in(101L, 20), out(300L, 5))));

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(Map.of(100L, 40L, 101L, 20L, 200L, 4L), block.externalRequirements(),
                "X external deficit = 20 consumed - 16 produced = 4");
        assertEquals(Map.of(300L, 5L), block.netOutputs());
    }

    @Test
    void multiStage_surplus_intermediateIsAnOutput() {
        // S1: 40xA -> 24xX   |   S2: 20xX + 20xB -> 5xY   (X surplus = 4)
        List<TemplateProductionFacility> facilities = List.of(
                facility(schematic(1, 3600, in(100L, 40), out(200L, 24))),
                facility(schematic(2, 3600, in(200L, 20), in(101L, 20), out(300L, 5))));

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(Map.of(100L, 40L, 101L, 20L), block.externalRequirements(),
                "surplus X is NOT an external input");
        assertEquals(Map.of(200L, 4L, 300L, 5L), block.netOutputs(),
                "X surplus appears as a net output alongside Y");
    }

    @Test
    void differentCycleTimes_lcmBase_upstreamRunsTwicePerBlock() {
        // Upstream cycle 1800s: 10xA -> 5xX  =>  2 cycles per 3600s block
        // Downstream cycle 3600s: 10xX + 10xB -> 2xY
        List<TemplateProductionFacility> facilities = List.of(
                facility(schematic(1, 1800, in(100L, 10), out(200L, 5))),
                facility(schematic(2, 3600, in(200L, 10), in(101L, 10), out(300L, 2))));

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(3600L, block.basePeriodSeconds(), "LCM(1800, 3600) = 3600");
        assertEquals(Map.of(100L, 20L, 101L, 10L), block.externalRequirements(),
                "upstream runs 2 cycles per block: 2x10 A; downstream: 10 B");
        assertEquals(Map.of(300L, 2L), block.netOutputs(),
                "X balanced: 2x5 produced = 10 consumed");
    }

    @Test
    void sameSchematicManyFactories_quantitiesAggregate() {
        List<TemplateProductionFacility> facilities = List.of(
                new TemplateProductionFacility(0, 2475L, schematic(1, 3600, in(100L, 4), in(101L, 2), out(300L, 1))),
                new TemplateProductionFacility(1, 2475L, schematic(1, 3600, in(100L, 4), in(101L, 2), out(300L, 1))),
                new TemplateProductionFacility(2, 2475L, schematic(1, 3600, in(100L, 4), in(101L, 2), out(300L, 1))));

        ProductionBlock block = ProductionBlockCalculator.calculate(facilities);

        assertEquals(Map.of(100L, 12L, 101L, 6L), block.externalRequirements());
        assertEquals(Map.of(300L, 3L), block.netOutputs());
    }

    @Test
    void emptyFacilities_throwsUnsupportedTemplate() {
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> ProductionBlockCalculator.calculate(List.of()));
        assertThrows(TemplateException.UnsupportedTemplate.class,
                () -> ProductionBlockCalculator.calculate(null));
    }
}
