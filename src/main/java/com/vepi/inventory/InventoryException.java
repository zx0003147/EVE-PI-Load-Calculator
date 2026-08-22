package com.vepi.inventory;

/**
 * Inventory parsing failures. Line-level details are always included so the UI
 * can point the user at the offending line.
 */
public class InventoryException extends RuntimeException {

    public InventoryException(String message) {
        super(message);
    }

    /** Malformed line: no trailing integer quantity, or a negative/non-integer one. */
    public static final class InvalidLine extends InventoryException {
        public InvalidLine(String message) { super(message); }
    }

    /** The whole text is blank — nothing to parse. */
    public static final class EmptyInventory extends InventoryException {
        public EmptyInventory(String message) { super(message); }
    }
}
