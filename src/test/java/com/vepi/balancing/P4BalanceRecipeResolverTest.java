package com.vepi.balancing;

import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fixture-SDE coverage for output quantities, repeated P2 aggregation and integer scaling. */
class P4BalanceRecipeResolverTest {
    @TempDir Path temp;

    @Test
    void expandsThroughNonUnitOutputsAndAggregatesRepeatedP2Exactly() throws Exception {
        Path db = temp.resolve("fixture.db");
        createFixture(db);
        try (SdeRepository sde = new SdeRepository(db.toString())) {
            P4BalanceRecipe recipe = new P4BalanceRecipeResolver(sde, new PiTierResolver(sde))
                    .resolve(4001L);
            assertEquals(Map.of(3001L, 6L, 3002L, 4L), recipe.p3Requirements());
            assertEquals(1, recipe.p3P4RecipeCyclesPerBlock());
            assertEquals(3, recipe.p3P4UnitsPerBlock());
            // A needs multiples of 2 P4 cycles; B needs multiples of 3, so K=6.
            assertEquals(6, recipe.p2P4RecipeCyclesPerBlock());
            assertEquals(18, recipe.p2P4UnitsPerBlock(), "6 P4 cycles x output 3");
            // A: 6*6/4=9 batches -> X90/shared90.
            // B: 6*4/6=4 batches -> Y28/shared20. Shared aggregates to 110.
            assertEquals(Map.of(2001L, 90L, 2002L, 28L, 2003L, 110L),
                    recipe.p2Requirements());
            assertEquals(6, recipe.p2Hierarchy().p4RecipeCycles());
            assertEquals(18, recipe.p2Hierarchy().p4Quantity(),
                    "tree root must use output units, not recipe-cycle count");
            assertEquals(recipe.p2Requirements(), recipe.p2Hierarchy().p2LeafTotals(),
                    "P2 tree leaves must be the exact balance-card per-block requirements");
            assertEquals(recipe.p3Requirements(), recipe.p3Hierarchy().p3Totals());
        }
    }

    @Test
    void halfBatchRequiresTwoP4CyclesAndIsNeverGcdReduced() throws Exception {
        Path db = temp.resolve("half-batch.db");
        createFixture(db);
        try (SdeRepository sde = new SdeRepository(db.toString())) {
            P4BalanceRecipe recipe = new P4BalanceRecipeResolver(sde, new PiTierResolver(sde))
                    .resolve(4002L);
            assertEquals(Map.of(3001L, 6L), recipe.p3Requirements());
            assertEquals(2, recipe.p2P4RecipeCyclesPerBlock());
            assertEquals(2, recipe.p2P4UnitsPerBlock());
            assertEquals(Map.of(2001L, 30L, 2003L, 30L), recipe.p2Requirements(),
                    "2 P4 cycles require 3 complete P3 batches; 30/30 must not reduce");
            P4BalanceRecipe.P3Branch branch = recipe.p2Hierarchy().p3Branches().getFirst();
            assertEquals(2, recipe.p2Hierarchy().p4Quantity());
            assertEquals(12, branch.quantity());
            assertEquals(Map.of(2001L, 30L, 2003L, 30L), recipe.p2Hierarchy().p2LeafTotals());
        }
    }

    private static void createFixture(Path db) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE planetSchematics(schematicID INTEGER PRIMARY KEY, schematicName TEXT, cycleTime INTEGER)");
                s.execute("CREATE TABLE planetSchematicsTypeMap(schematicID INTEGER, typeID INTEGER, quantity INTEGER, isInput INTEGER)");
                s.execute("CREATE TABLE planetSchematicsPinMap(schematicID INTEGER, pinTypeID INTEGER)");
                s.execute("CREATE TABLE invTypes(typeID INTEGER PRIMARY KEY, typeName TEXT, volume TEXT)");
            }
            long[] ids = {1001,1002,1003,1101,1102,1103,2001,2002,2003,3001,3002,4001,4002};
            try (var ps = c.prepareStatement("INSERT INTO invTypes VALUES(?,?,?)")) {
                for (long id : ids) {
                    ps.setLong(1, id); ps.setString(2, "Item " + id); ps.setString(3, "1"); ps.addBatch();
                }
                ps.executeBatch();
            }
            recipe(c, 11, "P1 A", 1101, 20, Map.of(1001L, 3000));
            recipe(c, 12, "P1 B", 1102, 20, Map.of(1002L, 3000));
            recipe(c, 13, "P1 C", 1103, 20, Map.of(1003L, 3000));
            recipe(c, 21, "P2 X", 2001, 5, Map.of(1101L, 40, 1102L, 40));
            recipe(c, 22, "P2 Y", 2002, 5, Map.of(1102L, 40, 1103L, 40));
            recipe(c, 23, "P2 Shared", 2003, 5, Map.of(1101L, 40, 1103L, 40));
            recipe(c, 31, "P3 A", 3001, 4, Map.of(2001L, 10, 2003L, 10));
            recipe(c, 32, "P3 B", 3002, 6, Map.of(2002L, 7, 2003L, 5));
            recipe(c, 41, "P4", 4001, 3, Map.of(3001L, 6, 3002L, 4));
            recipe(c, 42, "P4 Half Batch", 4002, 1, Map.of(3001L, 6));
        }
    }

    private static void recipe(java.sql.Connection c, long schematicId, String name,
                               long outputId, int outputQuantity,
                               Map<Long, Integer> inputs) throws Exception {
        try (var ps = c.prepareStatement("INSERT INTO planetSchematics VALUES(?,?,?)")) {
            ps.setLong(1, schematicId); ps.setString(2, name); ps.setInt(3, 3600); ps.executeUpdate();
        }
        try (var ps = c.prepareStatement("INSERT INTO planetSchematicsTypeMap VALUES(?,?,?,?)")) {
            for (var input : inputs.entrySet()) {
                ps.setLong(1, schematicId); ps.setLong(2, input.getKey());
                ps.setInt(3, input.getValue()); ps.setInt(4, 1); ps.addBatch();
            }
            ps.setLong(1, schematicId); ps.setLong(2, outputId);
            ps.setInt(3, outputQuantity); ps.setInt(4, 0); ps.addBatch();
            ps.executeBatch();
        }
    }
}
