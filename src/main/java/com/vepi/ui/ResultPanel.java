package com.vepi.ui;

import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Result section: the recommended-load table plus the summary
 * (runtime / expected output / used / remaining) and "Copy Material List".
 *
 * <p>The table renders directly from {@link RecommendedLoadPlan#materials()} —
 * it never recomputes anything. Rows are name-sorted for a stable order.
 */
public final class ResultPanel extends JPanel {

    private static final Color NOTICE_COLOR = new Color(0x9A6700);

    final MaterialTableModel model = new MaterialTableModel();  // package-visible for the GUI smoke test
    private final JTable table;
    final JLabel notice = new JLabel(" ");    // package-visible for the GUI smoke test
    private final JLabel runtimeValue = new JLabel("-");
    private final JLabel outputValue = new JLabel("-");
    private final JLabel usedValue = new JLabel("-");
    private final JLabel remainingValue = new JLabel("-");
    private final JButton copyButton = new JButton("Copy Material List");
    private RecommendedLoadPlan currentPlan;

    ResultPanel() {
        setBorder(BorderFactory.createTitledBorder("Recommended Load"));
        setLayout(new BorderLayout(10, 8));

        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.setAutoCreateRowSorter(true);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(0, 170));
        add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(10, 8));
        notice.setForeground(NOTICE_COLOR);

        JPanel summary = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 8, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 0;
        addSummaryRow(summary, gbc, 0, "Runtime:", runtimeValue);
        addSummaryRow(summary, gbc, 1, "Expected output:", outputValue);
        addSummaryRow(summary, gbc, 2, "Used capacity:", usedValue);
        addSummaryRow(summary, gbc, 3, "Remaining capacity:", remainingValue);

        copyButton.setEnabled(false);
        copyButton.setToolTipText("Copy \"Material quantity\" lines to the clipboard");
        copyButton.addActionListener(e -> copyMaterialList());

        JPanel noticeAndCopy = new JPanel(new BorderLayout(10, 4));
        noticeAndCopy.add(notice, BorderLayout.CENTER);
        noticeAndCopy.add(copyButton, BorderLayout.EAST);

        south.add(noticeAndCopy, BorderLayout.NORTH);
        south.add(summary, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** Render a computed plan. blockVolume is passed in for the "insufficient" notice. */
    void showPlan(RecommendedLoadPlan plan, BigDecimal blockVolume) {
        this.currentPlan = plan;
        model.setData(plan.materials());

        runtimeValue.setText(Formats.runtimeExact(plan.runtimeSeconds()));
        StringBuilder out = new StringBuilder("<html>");
        for (RecommendedLoadPlan.ExpectedOutput o : plan.expectedOutputs()) {
            if (out.length() > 6) {
                out.append("<br>");
            }
            out.append(Formats.amount(o.quantity())).append(' ')
                    .append(o.commodity().name());
        }
        outputValue.setText(out.append("</html>").toString());
        usedValue.setText(Formats.volume(plan.usedCapacity()));
        remainingValue.setText(Formats.volume(plan.remainingCapacity()));

        if (plan.blockCount() == 0) {
            notice.setText("<html>Capacity is insufficient for one complete production block.<br>"
                    + "Minimum required: " + Formats.volume(blockVolume) + "</html>");
        } else {
            notice.setText(" ");
        }
        copyButton.setEnabled(!plan.materials().isEmpty());
    }

    void clear() {
        currentPlan = null;
        model.setData(List.of());
        runtimeValue.setText("-");
        outputValue.setText("-");
        usedValue.setText("-");
        remainingValue.setText("-");
        notice.setText(" ");
        copyButton.setEnabled(false);
    }

    /** Current plan (for tests). */
    RecommendedLoadPlan currentPlan() {
        return currentPlan;
    }

    private void copyMaterialList() {
        if (currentPlan == null) {
            return;
        }
        String text = CopyText.materialList(currentPlan);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private static void addSummaryRow(JPanel panel, GridBagConstraints gbc,
                                      int row, String label, JLabel value) {
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(value, gbc);
        gbc.weightx = 0;
    }

    /** Material / Quantity / Volume table model over an immutable plan list. */
    static final class MaterialTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Material", "Quantity", "Volume"};

        private List<RecommendedMaterialLoad> rows = new ArrayList<>();

        void setData(List<RecommendedMaterialLoad> materials) {
            List<RecommendedMaterialLoad> sorted = new ArrayList<>(materials);
            sorted.sort(Comparator.comparing(
                    m -> m.commodity().name(), String.CASE_INSENSITIVE_ORDER));
            this.rows = sorted;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }
        @Override public boolean isCellEditable(int row, int col) { return false; }

        @Override
        public Object getValueAt(int row, int col) {
            RecommendedMaterialLoad m = rows.get(row);
            return switch (col) {
                case 0 -> m.commodity().name();
                case 1 -> Formats.amount(m.quantity());
                case 2 -> Formats.volume(m.volume());
                default -> throw new IllegalArgumentException("col " + col);
            };
        }
    }
}
