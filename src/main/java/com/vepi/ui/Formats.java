package com.vepi.ui;

import java.math.BigDecimal;

/**
 * Pure display formatters for the UI layer. No Swing, fully unit-testable.
 *
 * <p>Formatting is display-only: the exact internal values (long seconds,
 * exact BigDecimal volumes) are never modified.
 */
public final class Formats {

    private Formats() {}

    /**
     * Compact runtime, e.g. "1h", "30m", "1d 22h" (the spec's preferred
     * day-based form once runtime exceeds 24h), "1d 1h" for 90000s.
     * Zero renders as "0s".
     */
    public static String runtime(long seconds) {
        if (seconds == 0) {
            return "0s";
        }
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0) sb.append(s).append("s");
        return sb.toString().trim();
    }

    /** Runtime with the exact second count appended, e.g. "1d 22h (165600 s)". */
    public static String runtimeExact(long seconds) {
        return runtime(seconds) + " (" + seconds + " s)";
    }

    /** Grouped integer, e.g. 19872 -> "19,872". */
    public static String amount(long value) {
        return group(Long.toString(value));
    }

    /** Grouped exact decimal with trailing zeros stripped, e.g. 19872.50 -> "19,872.5". */
    public static String amount(BigDecimal value) {
        String plain = value.stripTrailingZeros().toPlainString();
        int dot = plain.indexOf('.');
        if (dot < 0) {
            return group(plain);
        }
        return group(plain.substring(0, dot)) + plain.substring(dot);
    }

    /** Grouped volume with unit, e.g. "19,872 m3". */
    public static String volume(BigDecimal value) {
        return amount(value) + " m3";
    }

    /** Compact capacity pair, e.g. "53,865 / 54,000 m3" for summary cells. */
    public static String capacityUsed(BigDecimal used, BigDecimal total) {
        return amount(used) + " / " + amount(total) + " m3";
    }

    /**
     * Balance-table "Need to Add" cell: a zero top-up reads as an em dash
     * (nothing to do) instead of a noisy "0"; anything positive stays a
     * grouped number. Display-only — the table model keeps the real value.
     */
    public static String addCell(long addQuantity) {
        return addQuantity == 0 ? "\u2014" : amount(addQuantity);
    }

    private static String group(String integerPart) {
        StringBuilder sb = new StringBuilder();
        int n = integerPart.length();
        for (int i = 0; i < n; i++) {
            sb.append(integerPart.charAt(i));
            int remaining = n - 1 - i;
            if (remaining > 0 && remaining % 3 == 0) {
                sb.append(',');
            }
        }
        return sb.toString();
    }
}
