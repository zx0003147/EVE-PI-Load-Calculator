package com.vepi.ui;

import com.vepi.app.PiCalculatorController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;

/**
 * One planet card: its own template paste area (reuses {@link TemplatePanel} —
 * the exact same parse(String) pipeline as everywhere else), its own capacity
 * field with inline validation (reuses {@link CapacityPanel}), and a Remove
 * button. Holds NO business logic — parsed state is a plain
 * {@link PiCalculatorController.PlanetTemplate} handed in by the frame.
 */
final class PlanetPanel extends JPanel {

    interface Listener {
        void templateTextSubmitted(long planetId, String text);

        void templateFileChosen(long planetId, Path file);

        void templateReset(long planetId);

        void capacityValidityChanged(long planetId, boolean valid);

        void removeRequested(long planetId);
    }

    private final long id;
    private final TitledBorder border;
    final TemplatePanel templatePanel;         // package-visible for the GUI smoke test
    final CapacityPanel capacityPanel;         // package-visible for the GUI smoke test
    private PiCalculatorController.PlanetTemplate loadedTemplate;   // null until loaded
    private boolean capacityValid = false;

    PlanetPanel(long id, Listener listener) {
        this.id = id;
        this.border = BorderFactory.createTitledBorder("Planet");
        setBorder(border);
        setLayout(new BorderLayout(8, 8));

        templatePanel = new TemplatePanel(new TemplatePanel.Listener() {
            @Override
            public void templateTextSubmitted(String text) {
                listener.templateTextSubmitted(id, text);
            }

            @Override
            public void templateFileChosen(Path file) {
                listener.templateFileChosen(id, file);
            }

            @Override
            public void templateReset() {
                listener.templateReset(id);
            }
        });
        add(templatePanel, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 4));
        capacityPanel = new CapacityPanel(valid -> {
            capacityValid = valid;
            listener.capacityValidityChanged(id, valid);
        });
        south.add(capacityPanel, BorderLayout.CENTER);

        JButton remove = new JButton("Remove");
        remove.setFont(UiConstants.BODY_FONT);
        remove.addActionListener(e -> listener.removeRequested(id));
        JPanel removeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        removeBar.add(remove);
        south.add(removeBar, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    long id() {
        return id;
    }

    /** Display number is the frame's insertion order, not the internal id. */
    void setDisplayNumber(int n) {
        border.setTitle("Planet " + n);
        repaint();
    }

    PiCalculatorController.PlanetTemplate loadedTemplate() {
        return loadedTemplate;
    }

    void setLoadedTemplate(PiCalculatorController.PlanetTemplate template) {
        this.loadedTemplate = template;
    }

    void clearLoadedTemplate() {
        this.loadedTemplate = null;
    }

    boolean templateLoaded() {
        return loadedTemplate != null;
    }

    boolean capacityValid() {
        return capacityValid;
    }

    String capacityText() {
        return capacityPanel.capacityText();
    }

    boolean ready() {
        return templateLoaded() && capacityValid;
    }
}
