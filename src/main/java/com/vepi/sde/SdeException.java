package com.vepi.sde;

/**
 * Base class for all SDE-resolution failures. Each subclass carries a diagnostic
 * message so problems are never silently swallowed (no "catch -> return null").
 */
public class SdeException extends RuntimeException {
    public SdeException(String message) { super(message); }
    public SdeException(String message, Throwable cause) { super(message, cause); }

    /** A typeID referenced by a pin or recipe is absent from invTypes. */
    public static final class MissingSdeType extends SdeException {
        public final long typeId;
        public MissingSdeType(long typeId) {
            super("SDE invTypes has no published row for typeID " + typeId);
            this.typeId = typeId;
        }
    }

    /** A schematicID could not be found in planetSchematics. */
    public static final class MissingSchematic extends SdeException {
        public final long schematicId;
        public MissingSchematic(long schematicId) {
            super("SDE planetSchematics has no row for schematicID " + schematicId);
            this.schematicId = schematicId;
        }
    }

    /** A schematic exists but is inconsistent with the facility that claims to run it. */
    public static final class InvalidSchematic extends SdeException {
        public final long schematicId;
        public final long facilityTypeId;
        public InvalidSchematic(long schematicId, long facilityTypeId, String detail) {
            super("Schematic " + schematicId + " is not valid for facility typeID "
                    + facilityTypeId + ": " + detail);
            this.schematicId = schematicId;
            this.facilityTypeId = facilityTypeId;
        }
    }

    /** A facility typeID from the template is unknown to the SDE (not a PI facility). */
    public static final class UnknownFacilityType extends SdeException {
        public final long facilityTypeId;
        public UnknownFacilityType(long facilityTypeId) {
            super("Facility typeID " + facilityTypeId + " is not a known PI production facility in SDE");
            this.facilityTypeId = facilityTypeId;
        }
    }
}
