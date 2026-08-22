package com.vepi.ui;

import com.vepi.app.PiCalculatorController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;

/**
 * Inventory section: the user pastes their real PI stock ("Name quantity" per
 * line) — Copy → Paste is the primary workflow, no files required.
 *
 * <p>Buttons: Paste from Clipboard, Parse Inventory, Clear. Parse results are
 * ONE compact status line ("✓ Inventory loaded — 12 items (9 P2, 3 P3)"),
 * warnings a small gray line underneath; parse failures are inline too,
 * never blocking dialogs and never a stack trace.
 */
public final class InventoryPanel extends JPanel {

    public interface Listener {
        /** Parse Inventory clicked with non-blank text — parse it. */
        void inventoryTextSubmitted(String text);

        /** Inventory state must be reset (Clear clicked, or blank parse). */
        void inventoryReset();
    }

    private final Listener listener;
    final JTextArea textArea;                 // package-visible for the GUI smoke test
    final JTextArea status = new JTextArea(" ");  // package-visible for the GUI smoke test
    private final JLabel error = new JLabel(" ");
    private final JLabel warning = new JLabel(" ");
    private final JButton pasteButton;
    private final JButton parseButton;
    private final JButton clearButton;

    InventoryPanel(Listener listener) {
        this.listener = listener;
        setBorder(BorderFactory.createTitledBorder("Current Inventory"));
        setLayout(new BorderLayout(UiConstants.INNER_PADDING, UiConstants.ROW_GAP));

        textArea = new JTextArea();
        textArea.setFont(UiConstants.MONO_FONT);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
        textArea.setTabSize(8);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(0, 92));

        add(scroll, BorderLayout.CENTER);

        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(false);
        status.setFont(UiConstants.BODY_FONT);
        status.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        error.setForeground(UiConstants.ERROR);
        error.setFont(UiConstants.BODY_FONT);

        warning.setForeground(UiConstants.SECONDARY);
        warning.setFont(UiConstants.METRIC_CAPTION_FONT);

        pasteButton = new JButton("Paste");
        pasteButton.addActionListener(e -> pasteFromClipboard());
        parseButton = new JButton("Parse");
        parseButton.addActionListener(e -> onParseClicked());
        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearAll());

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,
                UiConstants.CARD_GAP, 0));
        buttonRow.add(pasteButton);
        buttonRow.add(parseButton);
        buttonRow.add(clearButton);

        JPanel messages = new JPanel(new BorderLayout(4, 1));
        messages.setOpaque(false);
        messages.add(error, BorderLayout.NORTH);
        messages.add(warning, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(8, 2));
        south.setOpaque(false);
        south.add(status, BorderLayout.CENTER);
        south.add(messages, BorderLayout.SOUTH);
        south.add(buttonRow, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    // ---- text access ----

    String getInventoryText() {
        return textArea.getText();
    }

    void setInventoryText(String text) {
        textArea.setText(text);
        textArea.setCaretPosition(0);
    }

    // ---- button actions ----

    void onParseClicked() {   // package-visible for the GUI smoke test
        String text = getInventoryText();
        if (text == null || text.isBlank()) {
            showError("Paste your inventory first.");
            listener.inventoryReset();
            return;
        }
        listener.inventoryTextSubmitted(text);
    }

    private void pasteFromClipboard() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                showError("Clipboard does not contain text.");
                return;
            }
            setInventoryText((String) t.getTransferData(DataFlavor.stringFlavor));
            error.setText(" ");
        } catch (Exception e) {
            showError("Clipboard does not contain text.");
        }
    }

    /** Clear: wipe text + status + parsed snapshot; notify the frame to reset. */
    void clearAll() {
        textArea.setText("");
        status.setText(" ");
        status.setForeground(null);
        error.setText(" ");
        warning.setText(" ");
        listener.inventoryReset();
    }

    // ---- state rendering (driven by the frame; also used by GUI smoke tests) ----

    void setBusy(boolean busy) {
        pasteButton.setEnabled(!busy);
        parseButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
    }

    void showLoaded(PiCalculatorController.InventoryStatus s) {
        status.setText("\u2713 Inventory loaded \u2014 " + s.itemCount()
                + (s.itemCount() == 1 ? " item" : " items")
                + " (" + s.p2Count() + " P2, " + s.p3Count() + " P3)");
        status.setForeground(UiConstants.SUCCESS);
        error.setText(" ");
        if (s.warnings().isEmpty()) {
            warning.setText(" ");
        } else {
            StringBuilder sb = new StringBuilder("\u26A0 ");
            for (int i = 0; i < s.warnings().size(); i++) {
                if (i > 0) {
                    sb.append(" · ");
                }
                sb.append(s.warnings().get(i));
            }
            warning.setText(sb.toString());
        }
    }

    void showError(String message) {
        status.setText(" ");
        status.setForeground(null);
        warning.setText(" ");
        error.setText("<html>" + htmlEscape(message) + "</html>");
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
