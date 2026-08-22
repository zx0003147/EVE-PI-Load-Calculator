package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;
import com.vepi.balancing.BalanceException;
import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.template.TemplateException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>Balance Inventory</b> tab — an independent second product function
 * next to Load Allocation.
 *
 * <p>Question answered: “my current P2 stock is unbalanced for this template.
 * To consume ALL of it with whole sustainable blocks, which P2 must I top up,
 * to what target level, and what shopping list do I need?”
 *
 * <p>Layout: a left input column (inventory, template, Calculate) and a right
 * result column whose visual center is the four-metric strip (Target Blocks ·
 * Production Time · Final Output · Additional P2) above the BALANCE TABLE —
 * sized so the typical nine P2 rows are visible at once. Unused inventory and
 * the per-line diagnostics live behind collapsed toggles.
 *
 * <p>No planet capacity, no multi-planet split — one inventory, one template.
 * Own inventory and template inputs (spec §21 option A: no state sharing with
 * the Load Allocation tab). Copy buttons share only the
 * {@link CopyShoppingListFormatter} formatting discipline.
 *
 * <p>Threading mirrors AllocationFrame: parse/load on SwingWorkers, rendering
 * on the EDT. The sync render methods ({@code inventoryLoaded},
 * {@code templateLoaded}, {@code showPlan}, {@code showError}) are the same
 * entry points the workers call — the smoke test drives them directly.
 */
public final class BalanceInventoryPanel extends JPanel {

    private final BalanceInventoryController controller;

    final InventoryPanel inventoryPanel;       // package-visible for tests
    final TemplatePanel templatePanel;         // package-visible for tests
    final JButton calculateButton;             // package-visible for tests
    final JTextArea summary = new JTextArea(" ");   // package-visible for tests (details area)
    final DefaultTableModel balanceModel;      // package-visible for tests
    final DefaultTableModel unusedModel = new DefaultTableModel(
            new Object[]{"Unused Item", "Quantity", "Tier"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final DefaultTableModel outputModel = new DefaultTableModel(
            new Object[]{"Final Output", "Quantity"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    /** Raw Need-to-Add values parallel to {@link #balanceModel}'s rows (renderer). */
    private final List<Long> balanceAdds = new ArrayList<>();

    private JTable balanceTable;

    // Metric strip labels (the result page's visual headline).
    private final JLabel metricBlocksValue = blankMetric();
    private final JLabel metricTimeValue = blankMetric();
    private final JLabel metricOutputValue = blankMetric();
    private final JLabel metricAdditionalValue = blankMetric();

    private final CollapsibleSection unusedSection;
    private final JLabel unusedCaption = new JLabel(" ");
    private final JLabel errorLabel = new JLabel(" ");

    private boolean inventoryReady = false;
    private InventorySnapshot inventory;
    private PiCalculatorController.PlanetTemplate template;

    /** One balance run's render inputs. */
    record BalanceRun(InventoryBalancePlan plan) {
    }

    public BalanceInventoryPanel(BalanceInventoryController controller) {
        this.controller = controller;
        setLayout(new BorderLayout(0, 0));

        balanceModel = new DefaultTableModel(
                new Object[]{"Material", "Per Block", "Current", "Target", "Need to Add"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        // ---- LEFT COLUMN: own inputs (spec §21 A) ----
        inventoryPanel = new InventoryPanel(new InventoryPanel.Listener() {
            @Override
            public void inventoryTextSubmitted(String text) {
                onInventoryTextSubmitted(text);
            }

            @Override
            public void inventoryReset() {
                onInventoryReset();
            }
        });

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

        calculateButton = new JButton("Calculate Balance");
        calculateButton.setEnabled(false);
        calculateButton.setFont(UiConstants.BODY_BOLD_FONT.deriveFont(14f));
        calculateButton.setToolTipText("Top up current P2 stock to whole sustainable blocks");
        calculateButton.addActionListener(e -> onCalculate());
        JPanel calcBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0,
                UiConstants.ROW_GAP));
        calcBar.add(calculateButton);

        JPanel left = new JPanel(new BorderLayout(0, UiConstants.SECTION_GAP));
        left.setBorder(BorderFactory.createEmptyBorder(UiConstants.INNER_PADDING,
                UiConstants.INNER_PADDING, UiConstants.INNER_PADDING, UiConstants.CARD_GAP));
        JPanel inputs = new JPanel();
        inputs.setLayout(new javax.swing.BoxLayout(inputs, javax.swing.BoxLayout.Y_AXIS));
        inputs.setOpaque(false);
        inputs.add(inventoryPanel);
        inputs.add(javax.swing.Box.createVerticalStrut(UiConstants.SECTION_GAP));
        inputs.add(templatePanel);
        left.add(inputs, BorderLayout.NORTH);
        left.add(calcBar, BorderLayout.CENTER);

        // ---- RIGHT COLUMN: results ----
        JComponent results = buildResultsPanel();
        JScrollPane right = new JScrollPane(results);
        right.getVerticalScrollBar().setUnitIncrement(16);
        right.setBorder(BorderFactory.createEmptyBorder(UiConstants.INNER_PADDING,
                UiConstants.CARD_GAP, UiConstants.INNER_PADDING, UiConstants.INNER_PADDING));

        javax.swing.JSplitPane split =
                new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.40);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        // Unused inventory lives in a collapsed secondary section.
        JTable unusedTable = new JTable(unusedModel);
        styleTable(unusedTable);
        JPanel unusedWrapper = new JPanel(new BorderLayout(0, UiConstants.ROW_GAP));
        unusedWrapper.setOpaque(false);
        unusedWrapper.add(unusedCaption, BorderLayout.NORTH);
        JScrollPane unusedScroll = new JScrollPane(unusedTable);
        unusedScroll.setPreferredSize(new Dimension(0,
                3 * (unusedTable.getRowHeight() + 1) + 26));
        unusedWrapper.add(unusedScroll, BorderLayout.CENTER);
        unusedSection = new CollapsibleSection("Show Unused Inventory", unusedWrapper, false);
        attachUnusedSection();
    }

    /** The unused section is created after the results panel — slot it in. */
    private void attachUnusedSection() {
        resultsColumn.add(unusedSection, 1);   // right below the copy bar
    }

    private final JPanel resultsColumn = new JPanel();
    private JComponent copyBar;

    // ---- results panel ----

    private JComponent buildResultsPanel() {
        resultsColumn.setLayout(new javax.swing.BoxLayout(resultsColumn,
                javax.swing.BoxLayout.Y_AXIS));
        resultsColumn.setOpaque(false);

        errorLabel.setForeground(UiConstants.ERROR);
        errorLabel.setFont(UiConstants.BODY_FONT);
        errorLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        resultsColumn.add(errorLabel);

        resultsColumn.add(buildMetricStrip());

        resultsColumn.add(sectionTitle("BALANCE TABLE"));
        balanceTable = new JTable(balanceModel);
        styleTable(balanceTable);
        applyColumnWidths(balanceTable, 0.35, 0.15, 0.17, 0.17, 0.16);
        // "Need to Add" — bold when there is something to buy, em dash at zero.
        balanceTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                long add = row >= 0 && row < balanceAdds.size() ? balanceAdds.get(row) : 0;
                c.setFont(add > 0 ? UiConstants.BODY_BOLD_FONT : UiConstants.BODY_FONT);
                return c;
            }
        });
        JScrollPane scroll = new JScrollPane(balanceTable);
        scroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
        resultsColumn.add(scroll);

        copyBar = buildCopyBar();
        resultsColumn.add(copyBar);

        // Collapsed diagnostics: the original per-line summary + expected outputs.
        JPanel diagnostics = new JPanel();
        diagnostics.setLayout(new javax.swing.BoxLayout(diagnostics,
                javax.swing.BoxLayout.Y_AXIS));
        diagnostics.setOpaque(false);

        summary.setEditable(false);
        summary.setOpaque(false);
        summary.setFont(UiConstants.MONO_FONT);
        summary.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        diagnostics.add(summary);

        diagnostics.add(sectionTitle("EXPECTED OUTPUTS"));
        JTable outTable = new JTable(outputModel);
        styleTable(outTable);
        outTable.setPreferredScrollableViewportSize(new Dimension(0,
                Math.max(1, 1) * (outTable.getRowHeight() + 1) + 26));
        JScrollPane outScroll = new JScrollPane(outTable);
        outScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        diagnostics.add(outScroll);
        resultsColumn.add(new CollapsibleSection("Show Details", diagnostics, false));

        return resultsColumn;
    }

    private JPanel buildMetricStrip() {
        JPanel strip = new JPanel(new GridLayout(1, 4, UiConstants.CARD_GAP, 0));
        strip.setOpaque(false);
        strip.add(metricCell("TARGET BLOCKS", metricBlocksValue));
        strip.add(metricCell("PRODUCTION TIME", metricTimeValue));
        strip.add(metricCell("FINAL OUTPUT", metricOutputValue));
        strip.add(metricCell("ADDITIONAL P2", metricAdditionalValue));
        strip.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        strip.setBorder(BorderFactory.createEmptyBorder(0, 0, UiConstants.SECTION_GAP, 0));
        return strip;
    }

    private static JPanel metricCell(String caption, JLabel value) {
        JPanel cell = new JPanel(new GridLayout(2, 1, 0, 1));
        cell.setOpaque(false);
        JLabel cap = new JLabel(caption);
        cap.setFont(UiConstants.METRIC_CAPTION_FONT);
        cap.setForeground(UiConstants.SECONDARY);
        value.setFont(UiConstants.METRIC_FONT);
        cell.add(cap);
        cell.add(value);
        return cell;
    }

    private static JLabel blankMetric() {
        return new JLabel("\u2014");
    }

    private JComponent buildCopyBar() {
        JPanel copyBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, UiConstants.ROW_GAP));
        JButton copyShopping = new JButton("Copy Shopping List");
        copyShopping.setToolTipText("Only the P2 items you still need to add");
        copyShopping.addActionListener(e -> copyToClipboard(
                CopyShoppingListFormatter.shoppingList(lastPlan), "Shopping list"));
        JButton copyTarget = new JButton("Copy Target Inventory");
        copyTarget.setToolTipText("Every balanced P2 at its final target level");
        copyTarget.addActionListener(e -> copyToClipboard(
                CopyShoppingListFormatter.targetInventory(lastPlan), "Target inventory"));
        copyBar.add(copyShopping);
        copyBar.add(copyTarget);
        copyBar.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return copyBar;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiConstants.SECTION_FONT);
        label.setForeground(UiConstants.SECONDARY);
        label.setBorder(BorderFactory.createEmptyBorder(10, 2, 3, 2));
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return label;
    }

    private static void styleTable(JTable table) {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setRowHeight(table.getRowHeight() + 2);
    }

    private static void applyColumnWidths(JTable table, double... fractions) {
        for (int i = 0; i < table.getColumnCount() && i < fractions.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth((int) Math.round(1000 * fractions[i]));
        }
    }

    /** The usual nine P2 rows must be fully visible without scrolling. */
    private static int viewportHeight(JTable table) {
        int rows = Math.max(1, table.getModel().getRowCount());
        int visible = Math.min(rows, 12);
        return visible * (table.getRowHeight() + 1) + 26;
    }

    // ---- input events ----

    private void onInventoryTextSubmitted(String text) {
        inventoryPanel.setBusy(true);
        new SwingWorker<PiCalculatorController.InventoryStatus, Void>() {
            @Override
            protected PiCalculatorController.InventoryStatus doInBackground() {
                return controller.parseInventory(text);
            }

            @Override
            protected void done() {
                inventoryPanel.setBusy(false);
                try {
                    inventoryLoaded(get());
                } catch (Exception e) {
                    inventoryPanel.showError(messageOf(e));
                }
            }
        }.execute();
    }

    private void onInventoryReset() {
        inventoryReady = false;
        inventory = null;
        updateCalculateEnabled();
    }

    private void onTemplateTextSubmitted(String text) {
        templatePanel.setBusy(true, "loading");
        new SwingWorker<PiCalculatorController.PlanetTemplate, Void>() {
            @Override
            protected PiCalculatorController.PlanetTemplate doInBackground() {
                return controller.loadTemplate(text);
            }

            @Override
            protected void done() {
                templatePanel.setBusy(false, null);
                try {
                    templateLoaded(get());
                } catch (Exception e) {
                    templatePanel.showError(messageOf(e));
                }
            }
        }.execute();
    }

    private void onTemplateFileChosen(Path file) {
        try {
            String text = java.nio.file.Files.readString(file);
            SwingUtilities.invokeLater(() -> {
                templatePanel.setTemplateText(text);
                onTemplateTextSubmitted(text);
            });
        } catch (Exception e) {
            templatePanel.showError("Unable to read file:\n" + e.getMessage());
        }
    }

    private void onTemplateReset() {
        template = null;
        updateCalculateEnabled();
    }

    // ---- render (EDT, also driven directly by tests) ----

    void inventoryLoaded(PiCalculatorController.InventoryStatus status) {
        this.inventoryReady = !status.snapshot().isEmpty();
        this.inventory = status.snapshot();
        inventoryPanel.showLoaded(status);
        updateCalculateEnabled();
    }

    void templateLoaded(PiCalculatorController.PlanetTemplate loaded) {
        this.template = loaded;
        templatePanel.showSummary(loaded.summary());
        updateCalculateEnabled();
    }

    void showPlan(BalanceRun run) {
        InventoryBalancePlan plan = run.plan();
        lastPlan = plan;
        errorLabel.setText(" ");

        // metric strip — the four numbers the user came for
        metricBlocksValue.setText(Formats.amount(plan.targetBlocks()));
        metricTimeValue.setText(Formats.runtime(plan.productionTimeSeconds()));
        metricOutputValue.setText(firstOutputText(plan));
        metricAdditionalValue.setText(Formats.volume(plan.totalAdditionalVolume()));

        // balance table (display-only em dash for zero adds; raw values intact)
        balanceAdds.clear();
        balanceModel.setRowCount(0);
        for (InventoryBalanceMaterial m : plan.materials()) {
            balanceAdds.add(m.addQuantity());
            balanceModel.addRow(new Object[]{
                    m.commodity().name(),
                    Formats.amount(m.requiredPerBlock()),
                    Formats.amount(m.currentQuantity()),
                    Formats.amount(m.targetQuantity()),
                    Formats.addCell(m.addQuantity())});
        }
        // size the viewport to the actual row count (9 P2 fit without scrolling)
        balanceTable.setPreferredScrollableViewportSize(
                new Dimension(0, viewportHeight(balanceTable)));

        // unused inventory (collapsed section)
        if (plan.unusedInventory().isEmpty()) {
            unusedCaption.setText("All inventory participates in this balance.");
            unusedModel.setRowCount(0);
        } else {
            unusedCaption.setText(plan.unusedInventory().size()
                    + " item(s) not consumed by this template:");
            unusedModel.setRowCount(0);
            for (InventoryBalancePlan.UnusedItem u : plan.unusedInventory()) {
                unusedModel.addRow(new Object[]{
                        u.commodity().name(), Formats.amount(u.quantity()), "P" + u.tier()});
            }
        }

        // expected outputs (collapsed details) + per-line summary text
        outputModel.setRowCount(0);
        for (InventoryBalancePlan.ExpectedOutput o : plan.expectedFinalOutputs()) {
            outputModel.addRow(new Object[]{
                    o.commodity().name(), Formats.amount(o.quantity())});
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Target production blocks: ").append(Formats.amount(plan.targetBlocks())).append('\n');
        sb.append("Production time: ").append(Formats.runtimeExact(plan.productionTimeSeconds())).append('\n');
        sb.append("Additional P2 volume required: ").append(Formats.volume(plan.totalAdditionalVolume())).append('\n');
        summary.setForeground(null);
        summary.setText(sb.toString());
    }

    private static String firstOutputText(InventoryBalancePlan plan) {
        if (plan.expectedFinalOutputs().isEmpty()) {
            return "\u2014";
        }
        var first = plan.expectedFinalOutputs().get(0);
        String text = Formats.amount(first.quantity()) + " " + first.commodity().name();
        if (plan.expectedFinalOutputs().size() > 1) {
            text += " +" + (plan.expectedFinalOutputs().size() - 1) + " more";
        }
        return text;
    }

    private InventoryBalancePlan lastPlan;   // for copy buttons

    void showError(String message) {
        errorLabel.setText(message);
        metricBlocksValue.setText("\u2014");
        metricTimeValue.setText("\u2014");
        metricOutputValue.setText("\u2014");
        metricAdditionalValue.setText("\u2014");
        balanceAdds.clear();
        balanceModel.setRowCount(0);
        unusedModel.setRowCount(0);
        outputModel.setRowCount(0);
        summary.setForeground(UiConstants.ERROR);
        summary.setText(message);
    }

    // ---- helpers ----

    private void onCalculate() {
        InventorySnapshot snapshot = inventory;
        PiCalculatorController.PlanetTemplate tpl = template;
        if (snapshot == null || tpl == null) {
            return;
        }
        calculateButton.setText("Calculating...");
        new SwingWorker<BalanceRun, Void>() {
            @Override
            protected BalanceRun doInBackground() {
                return new BalanceRun(controller.balance(snapshot, tpl.plan()));
            }

            @Override
            protected void done() {
                calculateButton.setText("Calculate Balance");
                try {
                    showPlan(get());
                } catch (Exception e) {
                    showError(balanceErrorMessage(rootCause(e)));
                }
                updateCalculateEnabled();
            }
        }.execute();
    }

    /** Maps low-level exceptions to honest user-facing messages (never invented data). */
    private static String balanceErrorMessage(Throwable t) {
        if (t instanceof BalanceException.NoP2Requirements) {
            return "This template does not contain a P2 \u2192 P4 production chain.\n"
                    + "Its sustainable plan has no tier-2 external inputs, so there is "
                    + "nothing to balance against P2 stock.";
        }
        if (t instanceof TemplateException.UnsupportedTemplate) {
            return "This JSON does not appear to be a supported PI template.";
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String messageOf(Exception e) {
        Throwable t = rootCause(e);
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static Throwable rootCause(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private void updateCalculateEnabled() {
        calculateButton.setEnabled(inventoryReady && template != null);
    }

    private void copyToClipboard(String text, String what) {
        if (text == null || text.isBlank()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }
}
