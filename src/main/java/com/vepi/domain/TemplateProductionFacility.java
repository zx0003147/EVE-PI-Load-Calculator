package com.vepi.domain;

/**
 * A production facility identified from a template, with its SDE-resolved schematic.
 *
 * <p>The template supplies the facility typeID and (via the pin's output typeID)
 * which product it makes; the SDE supplies the actual recipe (schematic). This type
 * is the bridge between the {@code template} and {@code calc} layers and keeps the
 * "what do I have" (template) vs "how is it made" (SDE) concerns separate.
 */
public final class TemplateProductionFacility {
    private final int nodeIndex;          // 0-based pin index in the template P array
    private final long facilityTypeId;     // from template pin T
    private final PiSchematic schematic;   // resolved from SDE

    public TemplateProductionFacility(int nodeIndex, long facilityTypeId, PiSchematic schematic) {
        this.nodeIndex = nodeIndex;
        this.facilityTypeId = facilityTypeId;
        this.schematic = schematic;
    }

    public int nodeIndex() { return nodeIndex; }
    public long facilityTypeId() { return facilityTypeId; }
    public PiSchematic schematic() { return schematic; }

    @Override
    public String toString() {
        return "Facility#" + nodeIndex + " type=" + facilityTypeId + " " + schematic;
    }
}
