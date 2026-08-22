package com.vepi.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * The one place for UI look constants: spacing rhythm, fonts and the small
 * semantic color set. Panels must reference these instead of hard-coding
 * their own values, so the whole tool stays visually consistent.
 *
 * <p>Deliberately NOT a theme system — the system Look &amp; Feel stays in
 * charge of chrome; these constants only fix layout rhythm and the few
 * semantic accents (success / warning / error / secondary text).
 */
public final class UiConstants {

    private UiConstants() {}

    // ---- spacing rhythm ----
    public static final int SECTION_GAP = 16;     // between major cards
    public static final int CARD_GAP = 10;        // between sibling cards
    public static final int INNER_PADDING = 12;   // inside a card's border
    public static final int ROW_GAP = 6;          // between rows inside a card

    // ---- fonts (plain UI text is sans-serif; only raw data areas are mono) ----
    public static final Font TITLE_FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 15);
    public static final Font SECTION_FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 12);
    public static final Font BODY_FONT =
            new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font BODY_BOLD_FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 13);
    /** Big number of a summary metric cell. */
    public static final Font METRIC_FONT =
            new Font(Font.SANS_SERIF, Font.BOLD, 17);
    /** Small caption above a summary metric. */
    public static final Font METRIC_CAPTION_FONT =
            new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    /** Raw paste areas (inventory text, template JSON) and read-only details. */
    public static final Font MONO_FONT =
            new Font(Font.MONOSPACED, Font.PLAIN, 13);

    // ---- semantic colors (the only accents; no custom theme) ----
    public static final Color SUCCESS = new Color(0x1B5E20);   // dark green
    public static final Color WARNING = new Color(0x8A6D00);   // amber/brown
    public static final Color ERROR = new Color(0xB3261E);     // red
    public static final Color SECONDARY = new Color(0x666666); // gray text
}
