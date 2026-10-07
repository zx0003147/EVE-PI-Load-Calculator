package com.vepi.balancing;

import com.vepi.domain.PiCommodity;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SDE recipe graph plus the two independently executable balance blocks. */
public record P4BalanceRecipe(
        long p4TypeId,
        PiCommodity p4Product,
        RecipeLine p4Recipe,
        List<RecipeLine> p3Recipes,
        Map<Long, Long> p2Requirements,
        Map<Long, Long> p3Requirements,
        long p2P4RecipeCyclesPerBlock,
        long p3P4RecipeCyclesPerBlock,
        RecipeHierarchy p2Hierarchy,
        RecipeHierarchy p3Hierarchy) {

    public P4BalanceRecipe {
        p3Recipes = List.copyOf(p3Recipes);
        p2Requirements = Map.copyOf(new TreeMap<>(p2Requirements));
        p3Requirements = Map.copyOf(new TreeMap<>(p3Requirements));
        if (p2Requirements.isEmpty() || p3Requirements.isEmpty()) {
            throw new BalanceException("The selected P4 has no complete P3/P2 recipe in the SDE.");
        }
        if (p2P4RecipeCyclesPerBlock <= 0 || p3P4RecipeCyclesPerBlock <= 0) {
            throw new IllegalArgumentException("balance block cycle counts must be positive");
        }
        if (p2Hierarchy.p4RecipeCycles() != p2P4RecipeCyclesPerBlock
                || p3Hierarchy.p4RecipeCycles() != p3P4RecipeCyclesPerBlock) {
            throw new IllegalArgumentException("hierarchy cycle counts must match balance blocks");
        }
        if (!p2Hierarchy.p2LeafTotals().equals(p2Requirements)
                || !p3Hierarchy.p3Totals().equals(p3Requirements)) {
            throw new IllegalArgumentException("hierarchy quantities must match balance requirements");
        }
    }

    public long p4OutputQuantityPerCycle() { return p4Recipe.outputQuantity(); }

    public long p2P4UnitsPerBlock() {
        return Math.multiplyExact(p2P4RecipeCyclesPerBlock, p4OutputQuantityPerCycle());
    }

    public long p3P4UnitsPerBlock() {
        return Math.multiplyExact(p3P4RecipeCyclesPerBlock, p4OutputQuantityPerCycle());
    }

    public record Ingredient(PiCommodity commodity, long quantity) {
        public Ingredient {
            if (quantity <= 0) throw new IllegalArgumentException("ingredient quantity must be positive");
        }
    }

    public record RecipeLine(PiCommodity output, long outputQuantity, List<Ingredient> inputs) {
        public RecipeLine {
            if (outputQuantity <= 0) throw new IllegalArgumentException("output quantity must be positive");
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * Display-ready recipe hierarchy for one executable balance block.  All
     * quantities are already scaled by the resolver; UI code only traverses
     * this immutable tree and never reconstructs recipe arithmetic.
     */
    public record RecipeHierarchy(
            PiCommodity p4Product,
            long p4RecipeCycles,
            long p4Quantity,
            List<P3Branch> p3Branches) {
        public RecipeHierarchy {
            if (p4RecipeCycles <= 0 || p4Quantity <= 0) {
                throw new IllegalArgumentException("hierarchy quantities must be positive");
            }
            p3Branches = List.copyOf(p3Branches);
            if (p3Branches.isEmpty()) {
                throw new IllegalArgumentException("hierarchy must contain P3 branches");
            }
        }

        public Map<Long, Long> p3Totals() {
            Map<Long, Long> totals = new TreeMap<>();
            for (P3Branch branch : p3Branches) {
                totals.merge(branch.commodity().typeId(), branch.quantity(), Math::addExact);
            }
            return Map.copyOf(totals);
        }

        public Map<Long, Long> p2LeafTotals() {
            Map<Long, Long> totals = new TreeMap<>();
            for (P3Branch branch : p3Branches) {
                for (Ingredient input : branch.p2Inputs()) {
                    totals.merge(input.commodity().typeId(), input.quantity(), Math::addExact);
                }
            }
            return Map.copyOf(totals);
        }
    }

    /** One P3 requirement and the P2 leaves belonging to that exact branch. */
    public record P3Branch(PiCommodity commodity, long quantity, List<Ingredient> p2Inputs) {
        public P3Branch {
            if (quantity <= 0) throw new IllegalArgumentException("P3 quantity must be positive");
            p2Inputs = List.copyOf(p2Inputs);
        }
    }
}
