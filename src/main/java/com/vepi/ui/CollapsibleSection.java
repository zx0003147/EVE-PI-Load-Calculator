package com.vepi.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * Small presentation helper: a one-line toggle button that shows/hides a
 * content panel. Used for secondary details (template configuration,
 * remaining inventory, unused items) so the main screen stays compact.
 *
 * <p>Pure view logic — no business state. The expanded flag is the only
 * modelized bit (kept simple on purpose; no pixel-level testing).
 */
final class CollapsibleSection extends JPanel {

    private final JButton toggle;
    private final JComponent content;
    private final String title;
    private boolean expanded;

    /**
     * @param title          toggle text, e.g. "Show Details" / "Show Remaining Inventory"
     * @param content        the panel to show/hide (visibility is controlled here)
     * @param expandedByDefault initial state
     */
    CollapsibleSection(String title, JComponent content, boolean expandedByDefault) {
        super(new BorderLayout(0, UiConstants.ROW_GAP));
        this.title = title;
        this.content = content;
        this.expanded = expandedByDefault;

        setOpaque(false);
        setLayout(new BorderLayout(0, UiConstants.ROW_GAP));

        toggle = new JButton(title);
        toggle.setFont(UiConstants.BODY_FONT);
        toggle.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        toggle.setContentAreaFilled(false);
        toggle.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        toggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        toggle.addActionListener(e -> setExpanded(!expanded));
        add(toggle, BorderLayout.NORTH);

        content.setVisible(expanded);
        add(content, BorderLayout.CENTER);
        updateToggleText();
    }

    boolean isExpanded() {
        return expanded;
    }

    void setExpanded(boolean value) {
        expanded = value;
        content.setVisible(value);
        updateToggleText();
        revalidate();
        repaint();
    }

    private void updateToggleText() {
        toggle.setText(expanded ? "▾ " + title.replace("Show ", "Hide ")
                : "▸ " + title);
    }
}
