package com.vepi.domain;

import java.util.List;
import java.util.Objects;

/**
 * A PI schematic resolved from the SDE: the authoritative production recipe.
 *
 * <p>Source tables:
 * <ul>
 *   <li>planetSchematics (schematicID, schematicName, cycleTime)</li>
 *   <li>planetSchematicsTypeMap (schematicID, typeID, quantity, isInput)</li>
 *   <li>planetSchematicsPinMap (schematicID, pinTypeID) — which facility types can run it</li>
 * </ul>
 */
public final class PiSchematic {
    private final long schematicId;
    private final String name;
    private final long cycleTimeSeconds;
    private final List<PiSchematicMaterial> materials;
    private final List<Long> compatibleFacilityTypeIds;

    public PiSchematic(long schematicId, String name, long cycleTimeSeconds,
                       List<PiSchematicMaterial> materials,
                       List<Long> compatibleFacilityTypeIds) {
        this.schematicId = schematicId;
        this.name = Objects.requireNonNull(name, "name");
        if (cycleTimeSeconds <= 0) {
            throw new IllegalArgumentException("cycleTime must be positive, got " + cycleTimeSeconds);
        }
        this.cycleTimeSeconds = cycleTimeSeconds;
        this.materials = List.copyOf(materials);
        this.compatibleFacilityTypeIds = List.copyOf(compatibleFacilityTypeIds);
    }

    public long schematicId() { return schematicId; }
    public String name() { return name; }
    public long cycleTimeSeconds() { return cycleTimeSeconds; }
    public List<PiSchematicMaterial> materials() { return materials; }
    public List<Long> compatibleFacilityTypeIds() { return compatibleFacilityTypeIds; }

    /** Materials consumed per cycle (isInput = true). */
    public List<PiSchematicMaterial> inputs() {
        return materials.stream().filter(PiSchematicMaterial::isInput).toList();
    }

    /** Materials produced per cycle (isInput = false). */
    public List<PiSchematicMaterial> outputs() {
        return materials.stream().filter(m -> !m.isInput()).toList();
    }

    /** Seconds per hour divided by cycle time, as an exact fraction (3600 / cycleTime). */
    public long cyclesPerHourNumerator() { return 3600L; }
    public long cyclesPerHourDenominator() { return cycleTimeSeconds; }

    @Override
    public String toString() {
        return "Schematic " + schematicId + " '" + name + "' cycle=" + cycleTimeSeconds + "s";
    }
}
