package com.vepi.ui;

import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.InventoryItemUsage;
import com.vepi.allocation.MaterialAllocation;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.app.PiCalculatorController;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Results, one compact card per planet (the product's primary view).
 *
 * <p>Card structure — main result first, diagnostics collapsed:
 * <ol>
 *   <li>title line ("PLANET 1 — Pandogodzilla")</li>
 *   <li>a horizontal metric strip: Runtime · Blocks · Output · Capacity</li>
 *   <li>P2 TO LOAD table sized to show up to 12 rows at once (9 P2 fit whole)</li>
 *   <li>P3 / other-tier tables below it only when they carry rows</li>
 *   <li>Copy Planet Load bottom-right</li>
 *   <li>Show Details (collapsed): the full per-line summary incl. bottlenecks</li>
 * </ol>
 *
 * <p>Zero-block planets are a NORMAL result — they render an explanatory
 * notice in place of the tables, never an error look. The REMAINING
 * INVENTORY table closes the panel inside a collapsed section: secondary
 * data must not steal the result space.
 */
public final class AllocationResultPanel extends JPanel implements javax.swing.Scrollable {

    /** Everything needed to render one planet's result section. */
    public record PlanetView(PlanetAllocation allocation,
                             PiCalculatorController.ZeroBlockExplanation explanation,
                             String templateDisplayName,
                             List<String> bottleneckNotes) {
    }

    /** One rendered planet section (package-visible for the GUI smoke test). */
    static final class PlanetSection {
        final PlanetView view;
        final MaterialTableModel p2Model;
        final MaterialTableModel p3Model;     // may be empty
        final MaterialTableModel otherModel;  // non-P2/P3 tier inputs (e.g. P1), may be empty
        final JTextArea summary = new JTextArea(" ");   // details area (collapsed by default)
        final JTextArea notice = new JTextArea(" ");    // zero-block explanation, or blank

        PlanetSection(PlanetView view) {
            this.view = view;
            this.p2Model = new MaterialTableModel(view.allocation().materialsOfTier(2));
            this.p3Model = new MaterialTableModel(view.allocation().materialsOfTier(3));
            this.otherModel = new MaterialTableModel(view.allocation().materials().stream()
                    .filter(m -> m.tier() != 2 && m.tier() != 3)
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .toList());
        }
    }

    final List<PlanetSection> sections = new ArrayList<>();   // package-visible for tests
    private UsageTableModel usageModel = new UsageTableModel(List.of());

    AllocationResultPanel() {
        setLayout(new FullWidthStackLayout());
        setBackground(UiConstants.PAGE_BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, UiConstants.CARD_GAP,
                UiConstants.INNER_PADDING, 0));
        showPlaceholder();
    }

    /** Rebuilds the whole panel from a finished allocation run. */
    void showPlan(List<PlanetView> views, AllocationPlan plan) {
        sections.clear();
        removeAll();

        for (PlanetView view : views) {
            PlanetSection section = new PlanetSection(view);
            sections.add(section);
            add(buildPlanetSection(section));
            add(javax.swing.Box.createVerticalStrut(UiConstants.CARD_GAP));
        }

        add(buildRemainingInventorySection(plan));
        revalidate();
        repaint();
    }

    void clear() {
        sections.clear();
        usageModel = new UsageTableModel(List.of());
        removeAll();
        showPlaceholder();
        revalidate();
        repaint();
    }

    private void showPlaceholder() {
        add(dimLabel("Calculate Allocation to see the per-planet load plan."));
    }

    // ---- per-planet card ----

    private JPanel buildPlanetSection(PlanetSection section) {
        PlanetAllocation allocation = section.view.allocation();

        JPanel card = UiComponents.card();
        card.setLayout(new BorderLayout(0, UiConstants.ROW_GAP));
        card.add(UiComponents.sectionHeader(allocation.name() + " \u2014 "
                + section.view.templateDisplayName(),
                "Allocated load and sustainable output for this planet."), BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new FullWidthStackLayout());
        center.setOpaque(false);

        if (allocation.blockCount() == 0) {
            center.add(metricStrip(allocation, true));
            section.notice.setEditable(false);
            section.notice.setOpaque(false);
            section.notice.setLineWrap(true);
            section.notice.setWrapStyleWord(true);
            section.notice.setFont(UiConstants.BODY_FONT);
            section.notice.setForeground(UiConstants.WARNING);
            section.notice.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
            section.notice.setText(zeroBlockText(section.view.explanation()));
            center.add(section.notice);
        } else {
            center.add(metricStrip(allocation, false));
            // Sections appear only when they carry rows (an empty "P2 TO LOAD"
            // table would just be noise on a pure P3-fed planet).
            if (!section.p2Model.rows.isEmpty()) {
                center.add(sectionLabel("P2 TO LOAD"));
                center.add(materialTable(section.p2Model, 0.55, 0.20, 0.25));
            }
            if (!section.p3Model.rows.isEmpty()) {
                center.add(sectionLabel("P3 TO LOAD"));
                center.add(materialTable(section.p3Model, 0.55, 0.20, 0.25));
            }
            if (!section.otherModel.rows.isEmpty()) {
                center.add(sectionLabel("OTHER INPUT MATERIALS"));
                center.add(materialTable(section.otherModel, 0.55, 0.20, 0.25));
            }

            JButton copy = new JButton("Copy Planet Load");
            UiComponents.secondaryButton(copy);
            copy.addActionListener(e -> copyToClipboard(CopyText.planetLoad(allocation)));
            JPanel copyBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
            copyBar.add(copy);
            center.add(copyBar);
        }

        // Collapsed diagnostics: the original per-line summary (incl.
        // bottleneck notes) stays available but out of the way.
        section.summary.setEditable(false);
        section.summary.setOpaque(false);
        section.summary.setFont(UiConstants.MONO_FONT);
        section.summary.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        section.summary.setText(summaryText(allocation, section.view.bottleneckNotes()));
        JComponent summaryWrapper = new JPanel(new BorderLayout());
        summaryWrapper.setOpaque(false);
        summaryWrapper.add(section.summary, BorderLayout.CENTER);
        center.add(new CollapsibleSection("Show Details", summaryWrapper, false));

        card.add(center, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    /** Horizontal Runtime | Blocks | Output | Capacity strip. */
    private JPanel metricStrip(PlanetAllocation a, boolean zeroBlocks) {
        String runtime = Formats.runtime(a.runtimeSeconds());
        String blocks = Formats.amount(a.blockCount());
        String output = "\u2014";
        var outputs = a.expectedOutputs();
        if (!outputs.isEmpty()) {
            var first = outputs.get(0);
            output = Formats.amount(first.quantity()) + " " + first.commodity().name()
                    + (outputs.size() > 1 ? " +" + (outputs.size() - 1) + " more" : "");
        }
        return metricStripValues(runtime, blocks, output);
    }

    private static JPanel metricStripValues(String... values) {
        String[] captions = {"RUNTIME", "BLOCKS", "OUTPUT"};
        JPanel strip = new JPanel(new java.awt.GridBagLayout());
        strip.setOpaque(false);
        for (int i = 0; i < captions.length; i++) {
            JPanel cell = new JPanel(new GridLayout(2, 1, 0, 1));
            cell.setOpaque(false);
            JLabel caption = new JLabel(captions[i]);
            caption.setFont(UiConstants.METRIC_CAPTION_FONT);
            caption.setForeground(UiConstants.SECONDARY);
            JComponent value;
            if (i == 2) {
                JTextArea output = new JTextArea(values[i]);
                output.setEditable(false);
                output.setOpaque(false);
                output.setLineWrap(true);
                output.setWrapStyleWord(true);
                output.setRows(2);
                output.setFont(UiConstants.METRIC_FONT);
                value = output;
            } else {
                JLabel label = new JLabel(values[i]);
                label.setFont(UiConstants.METRIC_FONT);
                value = label;
            }
            cell.add(caption);
            cell.add(value);
            java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
            gbc.gridx = i;
            gbc.gridy = 0;
            gbc.weightx = i == 2 ? 2.0 : 1.0;
            gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
            gbc.anchor = java.awt.GridBagConstraints.NORTHWEST;
            gbc.insets = new java.awt.Insets(0, 0, 0,
                    i + 1 < captions.length ? UiConstants.CARD_GAP : 0);
            strip.add(cell, gbc);
        }
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        strip.setBorder(BorderFactory.createEmptyBorder(0, 0, UiConstants.ROW_GAP, 0));
        return strip;
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 18; }
    @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
        return Math.max(18, r.height - 36);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    private static String zeroBlockText(PiCalculatorController.ZeroBlockExplanation explanation) {
        StringBuilder sb = new StringBuilder();
        sb.append("No complete production block can be supplied from the current inventory.");
        if (explanation.capacityTooSmall()) {
            sb.append("\nPlanet capacity (")
                    .append(Formats.volume(explanation.capacity()))
                    .append(") is below one production block's volume (")
                    .append(Formats.volume(explanation.blockVolume())).append(").");
        }
        if (!explanation.limitingItems().isEmpty()) {
            sb.append("\nLimiting inventory:");
            for (var item : explanation.limitingItems()) {
                sb.append("\n  ").append(item.commodity().name())
                        .append(" \u2014 available: ").append(Formats.amount(item.available()))
                        .append(", required for one block: ")
                        .append(Formats.amount(item.requiredPerBlock()));
            }
        }
        return sb.toString();
    }

    private static String summaryText(PlanetAllocation allocation, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime: ").append(Formats.runtime(allocation.runtimeSeconds())).append('\n');
        sb.append("Production blocks: ").append(Formats.amount(allocation.blockCount())).append('\n');
        sb.append("Used capacity: ").append(Formats.volume(allocation.usedCapacity())).append('\n');
        sb.append("Remaining capacity: ").append(Formats.volume(allocation.remainingCapacity()));
        for (var output : allocation.expectedOutputs()) {
            sb.append("\nExpected output: ").append(output.commodity().name())
                    .append(" x").append(Formats.amount(output.quantity()));
        }
        // Sustainable-chain diagnostics (prepared by the controller with SDE names).
        for (String note : notes) {
            sb.append("\nBottleneck: ").append(note);
        }
        return sb.toString();
    }

    // ---- remaining inventory (collapsed secondary detail) ----

    private JComponent buildRemainingInventorySection(AllocationPlan plan) {
        List<InventoryItemUsage> usage = plan.inventoryUsage().stream()
                .sorted(Comparator.comparing(InventoryItemUsage::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        usageModel = new UsageTableModel(usage);

        JTable table = new JTable(usageModel);
        styleTable(table);
        applyColumnWidths(table, 0.40, 0.20, 0.20, 0.20);
        table.setPreferredScrollableViewportSize(
                new Dimension(0, viewportHeight(table, usage.size())));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(new javax.swing.JScrollPane(table), BorderLayout.CENTER);
        return new CollapsibleSection("Show Remaining Inventory", wrapper, false);
    }

    // ---- small builders ----

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiConstants.SECTION_FONT);
        label.setForeground(UiConstants.SECONDARY);
        label.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel dimLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiConstants.SECONDARY);
        return label;
    }

    /**
     * A material table whose viewport shows up to {@value #MAX_VISIBLE_ROWS}
     * rows without scrolling (the usual 9 P2 always fit), with fixed
     * percentage column widths (Material gets the lion's share).
     */
    private static final int MAX_VISIBLE_ROWS = 12;

    private static javax.swing.JScrollPane materialTable(MaterialTableModel model,
                                                        double... columnFractions) {
        JTable table = new JTable(model);
        styleTable(table);
        applyColumnWidths(table, columnFractions);
        table.setPreferredScrollableViewportSize(
                new Dimension(0, viewportHeight(table, model.rows.size())));
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
        return scroll;
    }

    private static void styleTable(JTable table) {
        UiComponents.table(table);
        table.setFillsViewportHeight(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setRowHeight(table.getRowHeight() + 2);
    }

    /** Preferred widths as fractions of the table width (must sum to ~1). */
    private static void applyColumnWidths(JTable table, double... fractions) {
        for (int i = 0; i < table.getColumnCount() && i < fractions.length; i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth((int) Math.round(1000 * fractions[i]));
        }
    }

    /** header + min(rows, MAX_VISIBLE_ROWS) rows — beyond that the pane scrolls. */
    private static int viewportHeight(JTable table, int rows) {
        int visible = Math.min(Math.max(1, rows), MAX_VISIBLE_ROWS);
        return visible * (table.getRowHeight() + 1) + 26;
    }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    // ---- table models ----

    static final class MaterialTableModel extends AbstractTableModel {
        private final List<MaterialAllocation> rows;
        private static final String[] COLUMNS = {"Material", "Quantity", "Volume"};

        MaterialTableModel(List<MaterialAllocation> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MaterialAllocation m = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> m.name();
                case 1 -> Formats.amount(m.quantity());
                case 2 -> Formats.volume(m.volume());
                default -> throw new IllegalArgumentException(columnIndex + "");
            };
        }
    }

    static final class UsageTableModel extends AbstractTableModel {
        private final List<InventoryItemUsage> rows;
        private static final String[] COLUMNS = {"Material", "Original", "Allocated", "Remaining"};

        UsageTableModel(List<InventoryItemUsage> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            InventoryItemUsage u = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> u.name();
                case 1 -> Formats.amount(u.original());
                case 2 -> Formats.amount(u.allocated());
                case 3 -> Formats.amount(u.remaining());
                default -> throw new IllegalArgumentException(columnIndex + "");
            };
        }
    }
}
