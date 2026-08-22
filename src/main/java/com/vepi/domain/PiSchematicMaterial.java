package com.vepi.domain;

/**
 * One input or output material of a PI schematic, as defined by the SDE.
 *
 * <p>Recipe quantity comes exclusively from the SDE planetSchematicsTypeMap table —
 * never from template route quantities.
 */
public final class PiSchematicMaterial {
    private final long typeId;
    private final int quantity;
    private final boolean input;

    public PiSchematicMaterial(long typeId, int quantity, boolean input) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        this.typeId = typeId;
        this.quantity = quantity;
        this.input = input;
    }

    public long typeId() { return typeId; }
    public int quantity() { return quantity; }
    public boolean isInput() { return input; }

    @Override
    public String toString() {
        return (input ? "in " : "out ") + typeId + " x" + quantity;
    }
}
