package com.vepi.load;

/**
 * Load-balancing layer failures (capacity input problems, unsolvable plans).
 * Kept separate from template / SDE exceptions so each layer stays diagnosable.
 */
public class LoadException extends RuntimeException {
    public LoadException(String message) { super(message); }

    /** The user-supplied capacity is not a usable non-negative finite m3 value. */
    public static final class InvalidCapacity extends LoadException {
        public InvalidCapacity(String message) { super(message); }
    }
}
