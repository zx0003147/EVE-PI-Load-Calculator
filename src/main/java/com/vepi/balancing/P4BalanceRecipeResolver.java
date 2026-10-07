package com.vepi.balancing;

import com.vepi.domain.PiCommodity;
import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;
import com.vepi.sde.PiTierResolver;
import com.vepi.sde.SdeException;
import com.vepi.sde.SdeRepository;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Resolves executable P4 -> P3 and P4 -> P3 -> P2 balance blocks. */
public final class P4BalanceRecipeResolver {
    private final SdeRepository sde;
    private final PiTierResolver tiers;

    public P4BalanceRecipeResolver(SdeRepository sde, PiTierResolver tiers) {
        this.sde = sde;
        this.tiers = tiers;
    }

    public P4BalanceRecipe resolve(long p4TypeId) {
        if (tiers.tierOf(p4TypeId) != 4) {
            throw new BalanceException("Selected product is not a Tier 4 PI commodity.");
        }
        PiSchematic p4 = producingSchematic(p4TypeId);
        PiSchematicMaterial p4Output = outputOf(p4, p4TypeId);
        PiCommodity p4Product = sde.getCommodity(p4TypeId);
        Map<Long, Long> p3Requirements = new TreeMap<>();
        Map<Long, P3Step> steps = new TreeMap<>();
        List<P4BalanceRecipe.Ingredient> p4Inputs = new ArrayList<>();

        for (PiSchematicMaterial input : p4.inputs()) {
            if (tiers.tierOf(input.typeId()) != 3) continue;
            p3Requirements.merge(input.typeId(), (long) input.quantity(), Math::addExact);
            p4Inputs.add(new P4BalanceRecipe.Ingredient(
                    sde.getCommodity(input.typeId()), input.quantity()));
        }
        if (p3Requirements.isEmpty()) {
            throw new BalanceException("The selected P4 recipe has no Tier 3 inputs.");
        }

        BigInteger p4Cycles = BigInteger.ONE;
        List<P4BalanceRecipe.RecipeLine> p3Recipes = new ArrayList<>();
        for (Map.Entry<Long, Long> required : p3Requirements.entrySet()) {
            PiSchematic schematic = producingSchematic(required.getKey());
            PiSchematicMaterial output = outputOf(schematic, required.getKey());
            List<P4BalanceRecipe.Ingredient> inputs = new ArrayList<>();
            for (PiSchematicMaterial input : schematic.inputs()) {
                if (tiers.tierOf(input.typeId()) == 2) {
                    inputs.add(new P4BalanceRecipe.Ingredient(
                            sde.getCommodity(input.typeId()), input.quantity()));
                }
            }
            inputs.sort(Comparator.comparing(i -> i.commodity().name(), String.CASE_INSENSITIVE_ORDER));
            if (inputs.isEmpty()) {
                throw new BalanceException("P3 " + sde.getCommodity(required.getKey()).name()
                        + " has no Tier 2 inputs in the SDE.");
            }
            long requiredCyclesMultiple = output.quantity()
                    / gcd(required.getValue(), output.quantity());
            p4Cycles = lcm(p4Cycles, BigInteger.valueOf(requiredCyclesMultiple));
            steps.put(required.getKey(), new P3Step(required.getValue(), output.quantity(), inputs));
            p3Recipes.add(new P4BalanceRecipe.RecipeLine(
                    sde.getCommodity(required.getKey()), output.quantity(), inputs));
        }
        p3Recipes.sort(Comparator.comparing(r -> r.output().name(), String.CASE_INSENSITIVE_ORDER));

        p4Inputs.sort(Comparator.comparing(i -> i.commodity().name(), String.CASE_INSENSITIVE_ORDER));
        P4BalanceRecipe.RecipeLine p4Recipe = new P4BalanceRecipe.RecipeLine(
                p4Product, p4Output.quantity(), p4Inputs);
        P4BalanceRecipe.RecipeHierarchy p3Hierarchy = hierarchy(
                p4Product, p4Output.quantity(), BigInteger.ONE, steps, false);
        P4BalanceRecipe.RecipeHierarchy p2Hierarchy = hierarchy(
                p4Product, p4Output.quantity(), p4Cycles, steps, true);
        Map<Long, Long> p2Requirements = p2Hierarchy.p2LeafTotals();
        return new P4BalanceRecipe(p4TypeId, p4Product, p4Recipe, p3Recipes,
                p2Requirements, p3Requirements, p4Cycles.longValueExact(), 1L,
                p2Hierarchy, p3Hierarchy);
    }

    private P4BalanceRecipe.RecipeHierarchy hierarchy(
            PiCommodity p4Product,
            long p4OutputPerCycle,
            BigInteger p4Cycles,
            Map<Long, P3Step> steps,
            boolean includeP2) {
        List<P4BalanceRecipe.P3Branch> branches = new ArrayList<>();
        for (Map.Entry<Long, P3Step> entry : steps.entrySet()) {
            P3Step step = entry.getValue();
            BigInteger p3Quantity = p4Cycles.multiply(BigInteger.valueOf(step.requiredByP4()));
            List<P4BalanceRecipe.Ingredient> p2Inputs = new ArrayList<>();
            if (includeP2) {
                BigInteger[] division = p3Quantity.divideAndRemainder(
                        BigInteger.valueOf(step.outputPerBatch()));
                if (division[1].signum() != 0) {
                    throw new ArithmeticException("non-integral P3 batch count for typeID "
                            + entry.getKey());
                }
                BigInteger batches = division[0];
                for (P4BalanceRecipe.Ingredient input : step.inputs()) {
                    p2Inputs.add(new P4BalanceRecipe.Ingredient(input.commodity(),
                            batches.multiply(BigInteger.valueOf(input.quantity())).longValueExact()));
                }
            }
            branches.add(new P4BalanceRecipe.P3Branch(sde.getCommodity(entry.getKey()),
                    p3Quantity.longValueExact(), p2Inputs));
        }
        branches.sort(Comparator.comparing(b -> b.commodity().name(), String.CASE_INSENSITIVE_ORDER));
        return new P4BalanceRecipe.RecipeHierarchy(p4Product, p4Cycles.longValueExact(),
                p4Cycles.multiply(BigInteger.valueOf(p4OutputPerCycle)).longValueExact(), branches);
    }

    private PiSchematic producingSchematic(long typeId) {
        long id = sde.findSchematicByOutput(typeId)
                .orElseThrow(() -> new SdeException("No producing schematic for typeID " + typeId));
        return sde.getSchematic(id);
    }

    private static PiSchematicMaterial outputOf(PiSchematic schematic, long typeId) {
        return schematic.outputs().stream().filter(m -> m.typeId() == typeId).findFirst()
                .orElseThrow(() -> new SdeException("Schematic " + schematic.schematicId()
                        + " does not output typeID " + typeId));
    }

    private static long gcd(long a, long b) {
        return BigInteger.valueOf(a).gcd(BigInteger.valueOf(b)).longValueExact();
    }

    private static BigInteger lcm(BigInteger a, BigInteger b) {
        return a.divide(a.gcd(b)).multiply(b);
    }

    private record P3Step(long requiredByP4, long outputPerBatch,
                          List<P4BalanceRecipe.Ingredient> inputs) { }
}
