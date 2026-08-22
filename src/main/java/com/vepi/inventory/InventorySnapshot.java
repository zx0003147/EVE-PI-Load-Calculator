package com.vepi.inventory;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The user's real PI inventory: typeId -> quantity. This is a <b>global shared
 * constraint</b> across every planet in an allocation run — never a per-planet
 * budget. Quantities are exact non-negative longs.
 */
public final class InventorySnapshot {

    private final SortedMap<Long, Long> quantities;

    public InventorySnapshot(Map<Long, Long> quantities) {
        SortedMap<Long, Long> copy = new TreeMap<>();
        if (quantities != null) {
            for (Map.Entry<Long, Long> e : quantities.entrySet()) {
                if (e.getValue() == null || e.getValue() < 0) {
                    throw new IllegalArgumentException(
                            "inventory quantity for typeID " + e.getKey() + " must be >= 0");
                }
                copy.put(e.getKey(), e.getValue());
            }
        }
        this.quantities = Collections.unmodifiableSortedMap(copy);
    }

    /** Immutable typeId -> quantity view (sorted for deterministic reporting). */
    public Map<Long, Long> quantities() { return quantities; }

    public long quantityOf(long typeId) {
        return quantities.getOrDefault(typeId, 0L);
    }

    public boolean isEmpty() { return quantities.isEmpty(); }

    @Override
    public String toString() {
        return "InventorySnapshot" + quantities;
    }
}
