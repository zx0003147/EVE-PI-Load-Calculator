package com.vepi.ui;

import com.vepi.allocation.MaterialAllocation;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.allocation.PlanetRequest;
import com.vepi.domain.PiCommodity;
import com.vepi.flow.SustainableProductionPlan;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyTextTest {

    private static PiCommodity commodity(long id, String name, String volume) {
        return new PiCommodity(id, name, new BigDecimal(volume));
    }

    @Test
    void materialList_nameSortedQuantityOnly() {
        // Deliberately unsorted (typeID order) input — output must be name-sorted.
        RecommendedLoadPlan plan = new RecommendedLoadPlan(
                new BigDecimal("20000"), 46, 3600, 165600,
                new BigDecimal("19872"), new BigDecimal("128"),
                List.of(
                        new RecommendedMaterialLoad(
                                commodity(2366, "Hazmat Detection Systems", "3"),
                                2208, new BigDecimal("6624"), 165600),
                        new RecommendedMaterialLoad(
                                commodity(9846, "Planetary Vehicles", "3"),
                                2208, new BigDecimal("6624"), 165600),
                        new RecommendedMaterialLoad(
                                commodity(2348, "Gel-Matrix Biopaste", "3"),
                                2208, new BigDecimal("6624"), 165600)),
                List.of(new RecommendedLoadPlan.ExpectedOutput(
                        commodity(2868, "Integrity Response Drones", "50"), 368)));

        assertEquals("""
                Gel-Matrix Biopaste 2208
                Hazmat Detection Systems 2208
                Planetary Vehicles 2208""", CopyText.materialList(plan));
    }

    @Test
    void materialList_noVolumesOrRuntimeLeakIntoShoppingList() {
        RecommendedLoadPlan plan = new RecommendedLoadPlan(
                new BigDecimal("100"), 1, 3600, 3600,
                new BigDecimal("100"), BigDecimal.ZERO,
                List.of(new RecommendedMaterialLoad(
                        commodity(2348, "Gel-Matrix Biopaste", "3"),
                        6, new BigDecimal("18"), 3600)),
                List.of());
        String text = CopyText.materialList(plan);
        assertEquals("Gel-Matrix Biopaste 6", text);
    }

    @Test
    void fullReport_containsVolumesAndRuntime() {
        RecommendedLoadPlan plan = new RecommendedLoadPlan(
                new BigDecimal("100"), 1, 3600, 3600,
                new BigDecimal("18"), new BigDecimal("82"),
                List.of(new RecommendedMaterialLoad(
                        commodity(2348, "Gel-Matrix Biopaste", "3"),
                        6, new BigDecimal("18"), 3600)),
                List.of(new RecommendedLoadPlan.ExpectedOutput(
                        commodity(2868, "Integrity Response Drones", "50"), 1)));
        String report = CopyText.fullReport(plan);
        assertEquals(true, report.contains("Gel-Matrix Biopaste  6  18 m3"));
        assertEquals(true, report.contains("Runtime: 1h (3600 s)"));
        assertEquals(true, report.contains("Expected output: Integrity Response Drones x1"));
    }

    // ---- Copy Planet Load (per-planet haul list) ----

    private static PlanetAllocation planetOf(MaterialAllocation... materials) {
        PlanetRequest request = new PlanetRequest("Planet 1",
                SustainableProductionPlan.simple(3600, Map.of(2348L, 6L), Map.of(2868L, 1L)),
                new BigDecimal("10000"));
        return new PlanetAllocation(request, 2, 7200, BigDecimal.ZERO,
                new BigDecimal("10000"), List.of(materials), List.of());
    }

    private static MaterialAllocation material(long typeId, String name, int tier,
                                               long quantity, String volume) {
        return new MaterialAllocation(commodity(typeId, name, "3"), tier,
                quantity, new BigDecimal(volume));
    }

    @Test
    void planetLoad_p2FirstThenP3_nameSortedQuantityOnly() {
        // Deliberately unsorted material list: P3 first, P2 in reverse name order.
        PlanetAllocation planet = planetOf(
                material(2348, "Gel-Matrix Biopaste", 3, 420, "1260"),
                material(200, "Superconductors", 2, 9000, "27000"),
                material(100, "Biocells", 2, 12000, "36000"));

        assertEquals("""
                Biocells 12000
                Superconductors 9000
                Gel-Matrix Biopaste 420""", CopyText.planetLoad(planet),
                "P2 first (name-sorted), P3 second — volumes/runtime never leak in");
    }

    @Test
    void planetLoad_skipsZeroQuantityRows() {
        PlanetAllocation planet = planetOf(
                material(100, "Biocells", 2, 12000, "36000"),
                material(2348, "Gel-Matrix Biopaste", 3, 0, "0"));
        assertEquals("Biocells 12000", CopyText.planetLoad(planet));
    }

    @Test
    void planetLoad_omitsVolumesAndOutputs() {
        PlanetAllocation planet = planetOf(
                material(100, "Oxides", 2, 8000, "4000"));
        assertEquals("Oxides 8000", CopyText.planetLoad(planet));
    }
}
