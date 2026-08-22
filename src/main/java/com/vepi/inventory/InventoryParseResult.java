package com.vepi.inventory;

import java.util.List;

/**
 * Result of parsing pasted inventory text: the snapshot plus non-blocking
 * warnings (duplicate lines combined, unknown items skipped).
 */
public record InventoryParseResult(InventorySnapshot snapshot, List<String> warnings) {

    public InventoryParseResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    @Override
    public String toString() {
        return "InventoryParseResult[" + snapshot.quantities().size() + " items, "
                + warnings.size() + " warnings]";
    }
}
