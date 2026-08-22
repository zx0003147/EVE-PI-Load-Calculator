package com.vepi.calc;

import com.vepi.domain.PiCommodity;
import com.vepi.domain.PiSchematic;
import com.vepi.domain.PiSchematicMaterial;
import com.vepi.domain.TemplateProductionFacility;
import com.vepi.sde.SdeRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates full-load hourly throughput from a template's production capacity and
 * the SDE recipes.
 *
 * <p>Formula (units/hour), exact rational arithmetic:
 * <pre>
 *   unitsPerHour = factoryCount * recipeQuantity * (3600 / cycleTimeSeconds)
 * </pre>
 * Recipe quantity comes from the SDE — never from template route quantities.
 *
 * <p>External inputs = consumed commodities that are NOT produced by any facility
 * in the template (must be imported onto the planet). For a single-level P3→P4
 * template this is exact. Multi-level quantity balancing is out of Phase 0 scope.
 */
public final class ProductionCalculator {

    private final SdeRepository sde;

    public ProductionCalculator(SdeRepository sde) { this.sde = sde; }

    /**
     * Pure, SDE-free per-schematic rates for a single facility group. Used by unit
     * tests with synthetic schematics (no database needed).
     *
     * @param factoryCount number of identical facilities running the schematic
     * @param schematic    the SDE recipe
     * @param inputs       true for consumed materials, false for produced
     * @return typeID → exact units/hour rate
     */
    public static Map<Long, Rate> ratesFor(int factoryCount, PiSchematic schematic, boolean inputs) {
        Map<Long, Rate> rates = new LinkedHashMap<>();
        Rate cyclesPerHour = Rate.of(schematic.cyclesPerHourNumerator(),
                schematic.cyclesPerHourDenominator());
        for (PiSchematicMaterial m : schematic.materials()) {
            if (m.isInput() != inputs) continue;
            // per factory: quantity * (3600 / cycleTime); then * factoryCount
            Rate perFactory = Rate.of(m.quantity()).multiply(cyclesPerHour);
            Rate total = perFactory;
            for (int i = 1; i < factoryCount; i++) total = total.add(perFactory);
            rates.merge(m.typeId(), total, Rate::add);
        }
        return rates;
    }

    /**
     * Full template calculation: resolves commodities from the SDE, aggregates all
     * facilities, derives m³/hour and identifies external inputs.
     */
    public ProductionResult compute(List<TemplateProductionFacility> facilities) {
        // Aggregate units/hour per typeID across every facility (mixed schematics supported).
        Map<Long, Rate> inputRates = new LinkedHashMap<>();
        Map<Long, Rate> outputRates = new LinkedHashMap<>();
        for (TemplateProductionFacility f : facilities) {
            Map<Long, Rate> in = ratesFor(1, f.schematic(), true);
            in.forEach((id, r) -> inputRates.merge(id, r, Rate::add));
            Map<Long, Rate> out = ratesFor(1, f.schematic(), false);
            out.forEach((id, r) -> outputRates.merge(id, r, Rate::add));
        }

        // Resolve commodities (name + volume) from the SDE, sorted by typeID for stable output.
        List<MaterialThroughput> inputs = toThroughput(inputRates);
        List<MaterialThroughput> outputs = toThroughput(outputRates);

        Set<Long> producedTypeIds = outputRates.keySet();
        List<MaterialThroughput> externalInputs = inputs.stream()
                .filter(t -> !producedTypeIds.contains(t.typeId()))
                .collect(Collectors.toList());

        BigDecimal totalExternalM3 = externalInputs.stream()
                .map(MaterialThroughput::m3PerHour)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ProductionResult(
                facilities.size(), inputs, outputs, externalInputs, totalExternalM3);
    }

    private List<MaterialThroughput> toThroughput(Map<Long, Rate> rates) {
        List<MaterialThroughput> list = new ArrayList<>();
        for (Map.Entry<Long, Rate> e : rates.entrySet()) {
            PiCommodity commodity = sde.getCommodity(e.getKey());
            BigDecimal m3 = e.getValue().toBigDecimal().multiply(commodity.volume());
            list.add(new MaterialThroughput(commodity, e.getValue(), m3));
        }
        return list;
    }
}
