package com.vepi.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presentation-only test of the collapse/expand helper: default state,
 * toggle behavior and label text. Pure Swing state — no pixels, no business
 * data (the collapsed content is ordinary secondary detail panels).
 */
class CollapsibleSectionTest {

    @Test
    void defaultState_followsConstructorFlag() {
        JPanel content = new JPanel();
        CollapsibleSection collapsed = new CollapsibleSection("Show Details", content, false);
        assertFalse(collapsed.isExpanded(), "details start collapsed");
        assertFalse(content.isVisible(), "collapsed content is hidden");

        CollapsibleSection expanded = new CollapsibleSection("Show Remaining Inventory",
                new JPanel(), true);
        assertTrue(expanded.isExpanded());
    }

    @Test
    void toggleFlipsVisibilityAndState() {
        JPanel content = new JPanel();
        content.add(new JLabel("row"));
        CollapsibleSection section = new CollapsibleSection("Show Details", content, false);

        section.setExpanded(true);
        assertTrue(section.isExpanded(), "expanded flag flips");
        assertTrue(content.isVisible(), "expanding shows the content");

        section.setExpanded(false);
        assertFalse(section.isExpanded(), "flag flips back");
        assertFalse(content.isVisible(), "collapsing hides the content");
    }
}
