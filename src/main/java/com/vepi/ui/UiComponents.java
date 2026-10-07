package com.vepi.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.AbstractButton;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;

/** Small shared visual primitives for the two workbench pages. */
final class UiComponents {

    private UiComponents() {}

    static JPanel pageHeader(String title, String description) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel heading = new JLabel(title);
        heading.setFont(UiConstants.PAGE_TITLE_FONT);
        JLabel help = new JLabel(description);
        help.setFont(UiConstants.BODY_FONT);
        help.setForeground(UiConstants.SECONDARY);
        panel.add(heading, BorderLayout.NORTH);
        panel.add(help, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(
                UiConstants.INNER_PADDING, UiConstants.INNER_PADDING,
                UiConstants.SECTION_GAP, UiConstants.INNER_PADDING));
        return panel;
    }

    static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiConstants.BORDER),
                BorderFactory.createEmptyBorder(UiConstants.INNER_PADDING,
                        UiConstants.INNER_PADDING, UiConstants.INNER_PADDING,
                        UiConstants.INNER_PADDING));
    }

    static JPanel card() {
        JPanel card = new JPanel();
        card.setBackground(UiConstants.CARD_BACKGROUND);
        card.setBorder(cardBorder());
        return card;
    }

    static JPanel sectionHeader(String title, String description) {
        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setOpaque(false);
        JLabel heading = new JLabel(title);
        heading.setFont(UiConstants.TITLE_FONT);
        JLabel help = new JLabel(description);
        help.setFont(UiConstants.METRIC_CAPTION_FONT);
        help.setForeground(UiConstants.SECONDARY);
        header.add(heading, BorderLayout.NORTH);
        header.add(help, BorderLayout.SOUTH);
        return header;
    }

    static JPanel metric(String caption, JLabel value) {
        JPanel cell = new JPanel(new BorderLayout(0, 3));
        cell.setOpaque(false);
        JLabel cap = new JLabel(caption);
        cap.setFont(UiConstants.METRIC_CAPTION_FONT);
        cap.setForeground(UiConstants.SECONDARY);
        value.setFont(UiConstants.METRIC_FONT);
        cell.add(cap, BorderLayout.NORTH);
        cell.add(value, BorderLayout.CENTER);
        return cell;
    }

    static void primaryButton(JButton button) {
        styleButton(button, true);
    }

    static void secondaryButton(JButton button) {
        styleButton(button, false);
    }

    private static void styleButton(JButton button, boolean primary) {
        Color normal = primary ? UiConstants.PRIMARY : UiConstants.SECONDARY_BUTTON;
        Color hover = primary ? UiConstants.PRIMARY_DARK : UiConstants.SECONDARY_BUTTON_HOVER;
        Color pressed = primary ? UiConstants.PRIMARY_PRESSED : UiConstants.SECONDARY_BUTTON_PRESSED;
        Color disabled = primary ? UiConstants.PRIMARY_DISABLED : new Color(0xE3E7EC);
        Color text = primary ? Color.WHITE : new Color(0x263238);
        Color disabledText = primary ? UiConstants.BUTTON_TEXT_DISABLED : new Color(0x707780);
        button.setUI(new ReadableButtonUI(disabledText));
        button.setFont(primary ? UiConstants.BODY_BOLD_FONT : UiConstants.BODY_FONT);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setRolloverEnabled(true);
        button.setFocusPainted(true);
        button.setMargin(new java.awt.Insets(primary ? 7 : 6, primary ? 14 : 12,
                primary ? 7 : 6, primary ? 14 : 12));
        Runnable applyState = () -> {
            var model = button.getModel();
            button.setBackground(!model.isEnabled() ? disabled
                    : (model.isPressed() ? pressed : (model.isRollover() ? hover : normal)));
            button.setForeground(model.isEnabled() ? text : disabledText);
        };
        button.getModel().addChangeListener(e -> applyState.run());
        // Critical for buttons styled after setEnabled(false): do not wait for a
        // later model event or a disabled button will initially look enabled.
        applyState.run();
    }

    /** Basic UI with an explicit disabled text color, independent of Windows/Metal defaults. */
    private static final class ReadableButtonUI extends BasicButtonUI {
        private final Color disabledText;
        private ReadableButtonUI(Color disabledText) { this.disabledText = disabledText; }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle textRect, String text) {
            Color old = g.getColor();
            g.setColor(b.isEnabled() ? b.getForeground() : disabledText);
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(g, text,
                    b.getDisplayedMnemonicIndex(), textRect.x,
                    textRect.y + g.getFontMetrics().getAscent());
            g.setColor(old);
        }
    }

    static void table(JTable table) {
        table.setFont(UiConstants.BODY_FONT);
        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setGridColor(UiConstants.BORDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.getTableHeader().setFont(UiConstants.BODY_BOLD_FONT);
        table.getTableHeader().setBackground(UiConstants.TABLE_HEADER);
        table.getTableHeader().setForeground(Color.DARK_GRAY);
        table.getTableHeader().setReorderingAllowed(false);
    }
}
