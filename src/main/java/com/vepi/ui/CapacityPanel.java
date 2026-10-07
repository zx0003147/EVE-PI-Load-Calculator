package com.vepi.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * Capacity section: one obvious input field (m3) with inline validation.
 *
 * <p>Validation runs on every keystroke via the controller's pure
 * {@code validateCapacity}; errors are shown inline next to the field,
 * never as blocking dialogs.
 */
public final class CapacityPanel extends JPanel {

    public interface Listener {
        /** Called whenever the capacity text becomes valid or invalid. */
        void capacityValidityChanged(boolean valid);
    }

    private static final Color ERROR_COLOR = new Color(0xB3261E);

    private final JTextField field;
    private final JLabel error;
    private final Listener listener;
    private boolean valid = false;

    CapacityPanel(Listener listener) {
        this.listener = listener;
        setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        setLayout(new FlowLayout(FlowLayout.LEFT, 8, 6));

        JLabel title = new JLabel("Available input capacity");
        title.setFont(UiConstants.BODY_BOLD_FONT);
        add(title);

        field = new JTextField(12);
        field.setFont(field.getFont().deriveFont(field.getFont().getSize() + 2f));
        field.setToolTipText("Storage volume you will use for external input materials, in m3");
        add(field);

        add(new JLabel("m3"));

        error = new JLabel(" ");
        error.setForeground(ERROR_COLOR);
        add(error);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { revalidateText(); }
            @Override public void removeUpdate(DocumentEvent e) { revalidateText(); }
            @Override public void changedUpdate(DocumentEvent e) { revalidateText(); }
        });
        // Re-validate on focus loss too (covers paste-then-tab without typing).
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { revalidateText(); }
        });

        setPreferredSize(new Dimension(0, 68));
    }

    String capacityText() {
        return field.getText();
    }

    /** Test hook: sets the text programmatically (DocumentListener still fires). */
    void setCapacityText(String text) {
        field.setText(text);
    }

    boolean isCapacityValid() {
        return valid;
    }

    void requestFocusOnField() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    private void revalidateText() {
        String text = field.getText();
        if (text.isBlank()) {
            // Empty is not an error worth shouting about, but it is not valid either.
            valid = false;
            error.setText(" ");
        } else {
            String message = com.vepi.app.PiCalculatorController.validateCapacity(text)
                    .map(m -> "Available capacity must be a non-negative number (m3).")
                    .orElse(null);
            valid = message == null;
            error.setText(message == null ? " " : message);
        }
        listener.capacityValidityChanged(valid);
    }
}
