package com.vepi.ui;

import com.vepi.app.PiCalculatorController;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.template.TemplateException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Main window: PI Template (paste text) -> Capacity -> Recommended Load.
 *
 * <p>Primary input flow: the user pastes the template JSON into the text area
 * (or clicks "Paste from Clipboard") and presses "Load Template". "Open File..."
 * and drag & drop are conveniences that read the file into the SAME text area
 * and run the SAME parse(String) pipeline — there is one template code path.
 *
 * <p>Threading rules: template parsing runs on a {@link SwingWorker} (DB +
 * file I/O); the load optimization itself is millisecond-level and runs on
 * the EDT. All UI updates happen on the EDT only. The frame consumes the
 * controller's records — never the Phase 0 hourly set-difference model.
 */
public final class PiCalculatorFrame extends JFrame {

    private final PiCalculatorController controller;
    final TemplatePanel templatePanel;      // package-visible for the GUI smoke test
    private final CapacityPanel capacityPanel;
    final ResultPanel resultPanel;          // package-visible for the GUI smoke test
    final JButton calculateButton;          // package-visible for the GUI smoke test

    private boolean templateReady = false;
    private boolean capacityValid = false;

    public PiCalculatorFrame(PiCalculatorController controller) {
        super("EVE PI Load Calculator");
        this.controller = controller;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                controller.close();
            }
        });

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));

        // Top stack: template section, then capacity + calculate bar.
        JPanel top = new JPanel(new BorderLayout(12, 12));
        templatePanel = new TemplatePanel(new TemplatePanel.Listener() {
            @Override
            public void templateTextSubmitted(String text) {
                onTemplateTextSubmitted(text);
            }

            @Override
            public void templateFileChosen(Path file) {
                onTemplateFileChosen(file);
            }

            @Override
            public void templateReset() {
                onTemplateReset();
            }
        });
        top.add(templatePanel, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(12, 6));
        capacityPanel = new CapacityPanel(this::onCapacityValidityChanged);
        middle.add(capacityPanel, BorderLayout.CENTER);

        calculateButton = new JButton("Calculate");
        calculateButton.setEnabled(false);
        calculateButton.setToolTipText("Compute the balanced recommended load");
        calculateButton.addActionListener(e -> onCalculate());
        JPanel calcBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 12));
        calcBar.add(calculateButton);
        middle.add(calcBar, BorderLayout.EAST);
        top.add(middle, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        // The result table stretches with the window.
        resultPanel = new ResultPanel();
        root.add(resultPanel, BorderLayout.CENTER);

        setContentPane(root);
        // Whole-window drag & drop for template files (nicety; same as Open File).
        root.setTransferHandler(new TransferHandlerPassthrough(this::onTemplateFileChosen));

        pack();
        setSize(880, 800);
        setMinimumSize(new java.awt.Dimension(760, 640));
        setLocationRelativeTo(null);
    }

    /** Load Template clicked (or a file was read into the text area) — parse the text. */
    private void onTemplateTextSubmitted(String text) {
        templatePanel.setBusy(true, "Loading template...");
        resultPanel.clear();
        templateReady = false;
        updateCalculateEnabled();

        new SwingWorker<PiCalculatorController.TemplateSummary, Void>() {
            @Override
            protected PiCalculatorController.TemplateSummary doInBackground() {
                return controller.loadTemplateText(text);
            }

            @Override
            protected void done() {
                templatePanel.setBusy(false, null);
                try {
                    showTemplateSummary(get());
                } catch (Exception e) {
                    showTemplateError(templateErrorMessage(e));
                }
            }
        }.execute();
    }

    /**
     * Open File / drag & drop: read the file's TEXT, fill the text area with it,
     * then submit it through the exact same parse(String) path as pasting.
     */
    private void onTemplateFileChosen(Path file) {
        templatePanel.setBusy(true, "Reading file:\n" + file.getFileName() + " ...");
        resultPanel.clear();
        templateReady = false;
        updateCalculateEnabled();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return Files.readString(file);
            }

            @Override
            protected void done() {
                try {
                    String text = get();
                    templatePanel.setTemplateText(text);
                    onTemplateTextSubmitted(text);   // same pipeline as pasting
                } catch (Exception e) {
                    templatePanel.setBusy(false, null);
                    showTemplateError("Cannot read template file " + file.getFileName());
                }
            }
        }.execute();
    }

    /** Blank text / Clear — no parsed template, no result, Calculate disabled. */
    private void onTemplateReset() {
        controller.clearTemplate();
        resultPanel.clear();
        templateReady = false;
        updateCalculateEnabled();
    }

    private void onCapacityValidityChanged(boolean valid) {
        capacityValid = valid;
        updateCalculateEnabled();
    }

    private void onCalculate() {
        if (!templateReady) {
            return;
        }
        try {
            RecommendedLoadPlan plan = controller.calculate(capacityPanel.capacityText());
            showResult(plan);
        } catch (RuntimeException e) {
            // Domain errors (no external inputs etc.) — show inline, not a dialog.
            resultPanel.clear();
            JOptionPane.showMessageDialog(this,
                    rootCauseMessage(e), "Calculation failed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    // ---- state setters, also driven directly by GUI smoke tests ----

    void showTemplateSummary(PiCalculatorController.TemplateSummary summary) {
        templatePanel.showSummary(summary);
        templateReady = true;
        updateCalculateEnabled();
    }

    void showTemplateError(String message) {
        templatePanel.showError(message);
        templateReady = false;
        updateCalculateEnabled();
    }

    void showResult(RecommendedLoadPlan plan) {
        var blockVolume = controller.blockVolume().orElseThrow();
        resultPanel.showPlan(plan, blockVolume);
    }

    private void updateCalculateEnabled() {
        SwingUtilities.invokeLater(() ->
                calculateButton.setEnabled(templateReady && capacityValid));
    }

    /**
     * Maps parse failures to the two user-facing messages (no stack traces):
     * invalid JSON vs valid JSON that is not a PI template.
     */
    private static String templateErrorMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        if (t instanceof TemplateException.UnsupportedTemplate) {
            return "This JSON does not appear to be a supported PI template.";
        }
        if (t instanceof TemplateException.InvalidTemplate) {
            if (t.getMessage() != null && t.getMessage().contains("empty")) {
                return "Paste a PI template first.";
            }
            return "Unable to parse PI template.\nThe pasted text is not valid template JSON.";
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String rootCauseMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    /** Forwards whole-window file drops to the template loader. */
    private static final class TransferHandlerPassthrough extends javax.swing.TransferHandler {
        private final java.util.function.Consumer<Path> consumer;

        TransferHandlerPassthrough(java.util.function.Consumer<Path> consumer) {
            this.consumer = consumer;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                Transferable t = support.getTransferable();
                List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                if (files.isEmpty()) {
                    return false;
                }
                consumer.accept(files.get(0).toPath());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
