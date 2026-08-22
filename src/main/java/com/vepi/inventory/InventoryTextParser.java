package com.vepi.inventory;

import com.vepi.domain.PiCommodity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses pasted inventory text ("Copy → Paste → Calculate" workflow).
 *
 * <p>Accepted line format — item name, then whitespace (spaces and/or tabs),
 * then a non-negative integer quantity at the end of the line:
 * <pre>
 *   Mechanical Parts    40594
 *   Gel-Matrix Biopaste	1527
 * </pre>
 *
 * Rules:
 * <ul>
 *   <li>item names may contain spaces; the quantity is always the <b>last</b>
 *       whitespace-separated token (so "Mechanical Parts" is never split apart);</li>
 *   <li>leading/trailing whitespace of the whole text and of individual lines is
 *       ignored; empty lines are skipped;</li>
 *   <li>quantities must be non-negative integers — anything else is a hard
 *       {@link InventoryException.InvalidLine} error (never silently guessed);</li>
 *   <li>names are resolved to typeIDs through the SDE (never a hand-written list);
 *       unknown names produce a non-blocking warning and are skipped;</li>
 *   <li>duplicate item lines are <b>combined</b> (summed) with a warning — never
 *       silently overwritten.</li>
 * </ul>
 */
public final class InventoryTextParser {

    /** name (may contain spaces), separator whitespace, trailing integer quantity. */
    private static final Pattern LINE = Pattern.compile("^(.+?)\\s+([0-9]+)$");

    private final Function<String, Optional<PiCommodity>> nameResolver;

    /**
     * @param nameResolver resolves an exact item name to its SDE commodity
     *                     (typically {@code sdeRepository::findCommodityByName})
     */
    public InventoryTextParser(Function<String, Optional<PiCommodity>> nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * @param text pasted inventory text
     * @return the parsed snapshot plus warnings
     * @throws InventoryException.EmptyInventory if the text contains no items
     * @throws InventoryException.InvalidLine    for malformed lines (with line number)
     */
    public InventoryParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InventoryException.EmptyInventory(
                    "inventory text is empty — paste your item list first");
        }

        Map<Long, Long> quantities = new LinkedHashMap<>();
        Map<String, Long> seenNames = new LinkedHashMap<>();   // name (lowercase) -> typeId
        List<String> warnings = new ArrayList<>();
        boolean anyDuplicate = false;

        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.strip();
            if (line.isEmpty()) continue;

            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw new InventoryException.InvalidLine(
                        "line " + (i + 1) + " is not a valid item line (expected "
                                + "'item name <whitespace> quantity'): \"" + line + "\"");
            }
            String name = m.group(1).strip();
            long quantity;
            try {
                quantity = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                throw new InventoryException.InvalidLine(
                        "line " + (i + 1) + ": quantity too large: \"" + line + "\"");
            }

            PiCommodity commodity = nameResolver.apply(name).orElse(null);
            if (commodity == null) {
                warnings.add("Unknown item ignored on line " + (i + 1) + ": \"" + name + "\"");
                continue;
            }

            Long existingTypeId = seenNames.get(name.toLowerCase());
            if (existingTypeId != null && existingTypeId == commodity.typeId()) {
                anyDuplicate = true;
            }
            quantities.merge(commodity.typeId(), quantity, Long::sum);
            seenNames.put(name.toLowerCase(), commodity.typeId());
        }

        if (quantities.isEmpty()) {
            throw new InventoryException.EmptyInventory(
                    "no recognizable items in inventory text"
                            + (warnings.isEmpty() ? "" : " (" + warnings.size() + " unknown lines)"));
        }
        if (anyDuplicate) {
            warnings.add("Duplicate item lines were combined.");
        }
        return new InventoryParseResult(new InventorySnapshot(quantities), warnings);
    }
}
