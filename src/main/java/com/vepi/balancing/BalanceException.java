package com.vepi.balancing;

/**
 * Thrown by {@link InventoryBalanceCalculator} when the requested balance
 * cannot be computed from the given template.
 */
public class BalanceException extends RuntimeException {

    public BalanceException(String message) {
        super(message);
    }

    /**
     * The loaded template has no tier-2 external requirements — it is not a
     * P2 → P4 production chain (e.g. a pure P3 → P4 template). The P2 balance
     * is undefined for it; the program must NOT invent a virtual P2 chain from
     * the P4 recipe.
     */
    public static final class NoP2Requirements extends BalanceException {

        public NoP2Requirements() {
            super("This template does not contain a P2 → P4 production chain.");
        }
    }
}
