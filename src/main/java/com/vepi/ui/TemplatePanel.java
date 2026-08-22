package com.vepi.ui;

import com.vepi.app.PiCalculatorController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Template section: pasting template JSON text is the PRIMARY input method.
 *
 * <p>Layout: a compact multi-line monospace text area (Ctrl+V / Ctrl+A /
 * Ctrl+C all work) plus four buttons (Paste / Load / Clear / Open File...).
 * A successful load renders ONE green status line
 * ("✓ Pandogodzilla · 21 facilities · Integrity Response Drones"); the full
 * production configuration is diagnostic detail behind a collapsed
 * {@code Show Details} toggle, so the normal workflow never drowns in it.
 *
 * <p>File extensions are never a criterion of validity — the parser judges by
 * content alone; the file chooser accepts all files. Drag &amp; drop of a file is
 * kept as a nicety and behaves exactly like Open File.
 */
public final class TemplatePanel extends JPanel {

    public interface Listener {
        /** Load Template was clicked with non-blank text — parse it. */
        void templateTextSubmitted(String text);

        /** A file was chosen (Open File / drag & drop) — the frame reads it,
         *  fills the text area and submits it as text. */
        void templateFileChosen(Path file);

        /** The template state must be reset (Clear clicked, or Load clicked
         *  with blank text) — results cleared, Calculate disabled. */
        void templateReset();
    }

    private final Listener listener;
    final JTextArea textArea;                  // package-visible for the GUI smoke test
    final JTextArea summary = new JTextArea(" ");  // package-visible for the GUI smoke test
    private final CollapsibleSection details;
    private final JTextArea detailsText =
            new JTextArea(" ");
    private final JLabel error = new JLabel(" ");
    private final JButton pasteButton;
    private final JButton loadButton;
    private final JButton clearButton;
    private final JButton openButton;

    TemplatePanel(Listener listener) {
        this.listener = listener;
        setBorder(BorderFactory.createTitledBorder("PI Template"));
        setLayout(new BorderLayout(UiConstants.INNER_PADDING, UiConstants.ROW_GAP));

        // --- center: the paste area (monospace, scrollable, editable) ---
        textArea = new JTextArea();
        textArea.setFont(UiConstants.MONO_FONT);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
        textArea.setTabSize(2);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(0, 88));
        add(scroll, BorderLayout.CENTER);

        // --- south: one-line status (+ error) + buttons; details collapsed ---
        summary.setEditable(false);
        summary.setOpaque(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setFont(UiConstants.BODY_FONT);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        detailsText.setEditable(false);
        detailsText.setOpaque(false);
        detailsText.setFont(UiConstants.MONO_FONT);
        detailsText.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        details = new CollapsibleSection("Show Details", detailsText, false);

        error.setForeground(UiConstants.ERROR);
        error.setFont(UiConstants.BODY_FONT);

        pasteButton = new JButton("Paste");
        pasteButton.addActionListener(e -> pasteFromClipboard());
        loadButton = new JButton("Load");
        loadButton.addActionListener(e -> onLoadClicked());
        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearAll());
        openButton = new JButton("Open File...");
        openButton.addActionListener(e -> chooseFile());

        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,
                UiConstants.CARD_GAP, 0));
        buttonRow.add(pasteButton);
        buttonRow.add(loadButton);
        buttonRow.add(clearButton);
        buttonRow.add(openButton);

        JPanel statusRow = new JPanel(new BorderLayout(8, 2));
        statusRow.setOpaque(false);
        statusRow.add(summary, BorderLayout.CENTER);
        statusRow.add(buttonRow, BorderLayout.EAST);
        statusRow.add(error, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, UiConstants.ROW_GAP));
        south.setOpaque(false);
        south.add(statusRow, BorderLayout.NORTH);
        south.add(details, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        // File drag & drop (nicety, behaves like Open File).
        setTransferHandler(fileDropHandler());
    }

    // ---- text access (also used by the frame's Open File / drag&drop path) ----

    String getTemplateText() {
        return textArea.getText();
    }

    void setTemplateText(String text) {
        textArea.setText(text);
        textArea.setCaretPosition(0);
    }

    // ---- button actions ----

    void onLoadClicked() {   // package-visible for the GUI smoke test
        String text = getTemplateText();
        if (text == null || text.isBlank()) {
            showPasteFirstHint();
            listener.templateReset();
            return;
        }
        listener.templateTextSubmitted(text);
    }

    private void pasteFromClipboard() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                showError("Clipboard does not contain text.");
                return;
            }
            setTemplateText((String) t.getTransferData(DataFlavor.stringFlavor));
            error.setText(" ");
        } catch (Exception e) {
            showError("Clipboard does not contain text.");
        }
    }

    /** Clear: wipe text + parsed template + result; notify the frame to reset. */
    void clearAll() {
        textArea.setText("");
        summary.setText(" ");
        summary.setForeground(null);
        detailsText.setText(" ");
        error.setText(" ");
        if (details.isExpanded()) {
            details.setExpanded(false);
        }
        listener.templateReset();
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Open PI Template");
        fc.setAcceptAllFileFilterUsed(true);   // any text file; content decides validity
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int rc = fc.showOpenDialog(this);
        if (rc == JFileChooser.APPROVE_OPTION) {
            listener.templateFileChosen(fc.getSelectedFile().toPath());
        }
    }

    // ---- state rendering (driven by the frame; also used by GUI smoke tests) ----

    void setBusy(boolean busy, String message) {
        pasteButton.setEnabled(!busy);
        loadButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
        openButton.setEnabled(!busy);
        if (busy) {
            summary.setText(message);
            summary.setForeground(null);
            error.setText(" ");
        }
    }

    void showSummary(PiCalculatorController.TemplateSummary s) {
        // ONE compact success line — name · facility count · first product.
        StringBuilder line = new StringBuilder("\u2713 ").append(s.displayName())
                .append(" \u00B7 ").append(s.facilityCount()).append(" facilities");
        if (!s.outputLines().isEmpty()) {
            String product = s.outputLines().get(0).split(" x")[0].trim();
            line.append(" \u00B7 ").append(product);
        }
        summary.setText(line.toString());
        summary.setForeground(UiConstants.SUCCESS);
        error.setText(" ");

        // Full diagnostic configuration lives behind Show Details.
        StringBuilder sb = new StringBuilder();
        sb.append("Production configuration:\n");
        for (String cfg : s.configurationLines()) {
            sb.append("  ").append(cfg).append('\n');
        }
        sb.append("Product / net output:");
        for (String out : s.outputLines()) {
            sb.append("\n  ").append(out);
        }
        sb.append("\nBlock period: ").append(s.basePeriodSeconds())
                .append("s \u00B7 Block volume: ")
                .append(com.vepi.ui.Formats.volume(s.blockVolumeM3()));
        detailsText.setText(sb.toString());
    }

    void showError(String message) {
        error.setText("<html>" + htmlEscape(message) + "</html>");
    }

    void showPasteFirstHint() {
        summary.setText("Paste a PI template first.");
        summary.setForeground(null);
        detailsText.setText(" ");
        error.setText(" ");
    }

    // ---- drag & drop of files (nicety) ----

    private TransferHandler fileDropHandler() {
        return new TransferHandler() {
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
                    listener.templateFileChosen(files.get(0).toPath());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }
}
