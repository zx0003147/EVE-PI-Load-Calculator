package com.vepi.inventory;

import com.vepi.domain.PiCommodity;
import com.vepi.sde.SdeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory text parser tests against the real SDE — covers the pasted-inventory
 * format the user actually copies out of EVE (names with spaces, tabs or runs of
 * spaces, blank lines, duplicates, invalid quantities, unknown items).
 */
class InventoryTextParserTest {

    private static SdeRepository sde;
    private static InventoryTextParser parser;

    @BeforeAll
    static void setUp() {
        sde = new SdeRepository("data/sde/pi-sde.db");
        parser = new InventoryTextParser(sde::findCommodityByName);
    }

    @AfterAll
    static void tearDown() {
        sde.close();
    }

    /** The real inventory text from the product spec — must parse exactly. */
    @Test
    void realInventoryText_parsesAllTwelveItems() {
        String text = """
                Biocells\t46080
                Mechanical Parts\t40594
                Miniature Electronics\t61830
                Oxides\t74225
                Polytextiles\t70909
                Superconductors\t51110
                Supertensile Plastics\t65939
                Transmitter\t31390
                Viral Agent\t72134
                Gel-Matrix Biopaste\t1527
                Hazmat Detection Systems\t41
                Planetary Vehicles\t688
                """;

        InventoryParseResult result = parser.parse(text);
        InventorySnapshot snapshot = result.snapshot();

        assertEquals(12, snapshot.quantities().size(), "12 distinct PI items");
        assertTrue(result.warnings().isEmpty(), "clean input has no warnings");

        // Resolve expected typeIDs through the same SDE — no hand-written ID list.
        Map<String, Long> expected = java.util.List.of(
                        "Biocells", "Mechanical Parts", "Miniature Electronics", "Oxides",
                        "Polytextiles", "Superconductors", "Supertensile Plastics", "Transmitter",
                        "Viral Agent", "Gel-Matrix Biopaste", "Hazmat Detection Systems",
                        "Planetary Vehicles")
                .stream()
                .collect(Collectors.toMap(n -> n, n -> sde.findCommodityByName(n).orElseThrow().typeId()));

        assertEquals(46080L, snapshot.quantityOf(expected.get("Biocells")));
        assertEquals(40594L, snapshot.quantityOf(expected.get("Mechanical Parts")));
        assertEquals(74225L, snapshot.quantityOf(expected.get("Oxides")));
        assertEquals(1527L, snapshot.quantityOf(expected.get("Gel-Matrix Biopaste")), "P3 in stock");
        assertEquals(41L, snapshot.quantityOf(expected.get("Hazmat Detection Systems")));
        assertEquals(688L, snapshot.quantityOf(expected.get("Planetary Vehicles")));
    }

    @Test
    void nameWithSpaces_isNotSplitAtFirstSpace() {
        InventoryParseResult result = parser.parse("Mechanical Parts    40594");
        long mechanicalParts = sde.findCommodityByName("Mechanical Parts").orElseThrow().typeId();
        assertEquals(40594L, result.snapshot().quantityOf(mechanicalParts));
        assertEquals(1, result.snapshot().quantities().size());
    }

    @Test
    void multipleSpacesTabs_blankLines_leadingTrailingWhitespace_allFine() {
        String text = "\n\n   Oxides   1000  \n\n\n\t\tBiocells\t\t42\t\n\n  \n";
        InventoryParseResult result = parser.parse(text);
        long oxides = sde.findCommodityByName("Oxides").orElseThrow().typeId();
        long biocells = sde.findCommodityByName("Biocells").orElseThrow().typeId();
        assertEquals(1000L, result.snapshot().quantityOf(oxides));
        assertEquals(42L, result.snapshot().quantityOf(biocells));
    }

    @Test
    void duplicateLines_areCombinedWithWarning() {
        InventoryParseResult result = parser.parse("Oxides 1000\nOxides 2000");
        long oxides = sde.findCommodityByName("Oxides").orElseThrow().typeId();
        assertEquals(3000L, result.snapshot().quantityOf(oxides), "summed, not overwritten");
        assertEquals(1, result.snapshot().quantities().size());
        assertTrue(result.warnings().stream()
                        .anyMatch(w -> w.contains("Duplicate item lines were combined")),
                "non-blocking duplicate warning, got: " + result.warnings());
    }

    @Test
    void invalidQuantity_isHardErrorWithLineNumber() {
        InventoryException.InvalidLine e1 = assertThrows(InventoryException.InvalidLine.class,
                () -> parser.parse("Oxides -5"));
        assertTrue(e1.getMessage().contains("line 1"));

        assertThrows(InventoryException.InvalidLine.class, () -> parser.parse("Oxides 12.5"));
        assertThrows(InventoryException.InvalidLine.class, () -> parser.parse("Oxides abc"));
        assertThrows(InventoryException.InvalidLine.class, () -> parser.parse("just a name"));
        assertThrows(InventoryException.InvalidLine.class, () -> parser.parse("Oxides"));

        InventoryException.InvalidLine e2 = assertThrows(InventoryException.InvalidLine.class,
                () -> parser.parse("Oxides 100\n\nOxides 1.5"));
        assertTrue(e2.getMessage().contains("line 3"), "empty lines still count in numbering");
    }

    @Test
    void unknownItem_isSkippedWithWarning() {
        InventoryParseResult result = parser.parse("Oxides 500\nNot A Real PI Item 999");
        long oxides = sde.findCommodityByName("Oxides").orElseThrow().typeId();
        assertEquals(500L, result.snapshot().quantityOf(oxides));
        assertEquals(1, result.snapshot().quantities().size());
        assertTrue(result.warnings().stream()
                .anyMatch(w -> w.contains("Not A Real PI Item")), "warning names the item");
    }

    @Test
    void blankText_throwsEmptyInventory() {
        assertThrows(InventoryException.EmptyInventory.class, () -> parser.parse(""));
        assertThrows(InventoryException.EmptyInventory.class, () -> parser.parse("   \n\n  \t "));
        assertThrows(InventoryException.EmptyInventory.class, () -> parser.parse(null));
    }

    @Test
    void onlyUnknownItems_throwsEmptyInventory() {
        assertThrows(InventoryException.EmptyInventory.class,
                () -> parser.parse("Totally Unknown 5\nAlso Unknown 10"));
    }

    @Test
    void caseSensitiveNames_eveCopyIsExact() {
        // EVE copies exact names; a case variant is unknown, not guessed.
        InventoryParseResult result = parser.parse("Oxides 100\noxides 50");
        long oxides = sde.findCommodityByName("Oxides").orElseThrow().typeId();
        assertEquals(100L, result.snapshot().quantityOf(oxides));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("oxides")));
    }

    @Test
    void zeroQuantity_isLegal() {
        InventoryParseResult result = parser.parse("Oxides 0");
        long oxides = sde.findCommodityByName("Oxides").orElseThrow().typeId();
        assertEquals(0L, result.snapshot().quantityOf(oxides));
        assertEquals(1, result.snapshot().quantities().size());
    }
}
