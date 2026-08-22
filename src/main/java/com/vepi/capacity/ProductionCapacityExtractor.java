package com.vepi.capacity;

import com.vepi.domain.TemplateProductionFacility;
import com.vepi.sde.SdeException;
import com.vepi.sde.SdeRepository;
import com.vepi.template.PiTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a parsed {@link PiTemplate} into a list of SDE-validated production
 * facilities ({@link TemplateProductionFacility}).
 *
 * <p>This is the seam between the <b>template</b> layer (what facilities exist)
 * and the <b>SDE</b> layer (the real recipe). It does NOT read route quantities.
 * For each producer pin the output typeID is mapped to its schematic via the SDE
 * {@code planetSchematicsTypeMap}, and the facility is checked against
 * {@code planetSchematicsPinMap} for compatibility.
 */
public final class ProductionCapacityExtractor {

    private final SdeRepository sde;

    public ProductionCapacityExtractor(SdeRepository sde) { this.sde = sde; }

    /**
     * @param template parsed template
     * @return one entry per producer pin, in pin order
     */
    public List<TemplateProductionFacility> extract(PiTemplate template) {
        List<TemplateProductionFacility> facilities = new ArrayList<>();
        List<PiTemplate.Pin> pins = template.P;
        for (int i = 0; i < pins.size(); i++) {
            PiTemplate.Pin pin = pins.get(i);
            if (!pin.isProducer()) continue;   // launchpads, storage, command centers etc.

            long facilityTypeId = pin.T;
            long outputTypeId = pin.S;

            Optional<Long> schematicId = sde.findSchematicByOutput(outputTypeId);
            if (schematicId.isEmpty()) {
                throw new SdeException("Template pin #" + (i + 1) + " claims to produce typeID "
                        + outputTypeId + " but no PI schematic produces it");
            }
            var schematic = sde.getSchematic(schematicId.get());

            // Validate the facility can actually run this schematic.
            if (!schematic.compatibleFacilityTypeIds().contains(facilityTypeId)) {
                if (!sde.isFacilityType(facilityTypeId)) {
                    throw new SdeException.UnknownFacilityType(facilityTypeId);
                }
                throw new SdeException.InvalidSchematic(
                        schematic.schematicId(), facilityTypeId,
                        "facility cannot run this schematic (pin #" + (i + 1) + ")");
            }
            facilities.add(new TemplateProductionFacility(i, facilityTypeId, schematic));
        }
        return facilities;
    }
}
