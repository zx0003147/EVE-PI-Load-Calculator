package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;
import com.vepi.balancing.InventoryBalanceMaterial;
import com.vepi.balancing.InventoryBalancePlan;
import com.vepi.balancing.P4BalanceRecipe;
import com.vepi.inventory.InventorySnapshot;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/** Product-driven, independent P2/P3 inventory balancing workbench. */
public final class BalanceInventoryPanel extends JPanel {
    private final BalanceInventoryController controller;
    final InventoryPanel inventoryPanel;
    final JComboBox<BalanceInventoryController.P4Product> p4Combo = new JComboBox<>();
    final JButton calculateButton = new JButton("Calculate Balance");
    final JButton copyShoppingButton = new JButton("Copy Shopping List");
    final JButton copyTargetButton = new JButton("Copy Target Inventory");
    final JTextArea summary = new JTextArea(" ");
    final JPanel recipeHierarchyPanel = new JPanel();
    final DefaultTableModel p2Model = materialModel();
    final DefaultTableModel p3Model = materialModel();
    final DefaultTableModel balanceModel = p2Model;
    final DefaultTableModel unusedModel = new DefaultTableModel(
            new Object[]{"Unused Item", "Quantity", "Tier"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel totalPurchaseValue = metricValue();
    private final JLabel p2PurchaseValue = metricValue();
    private final JLabel p3PurchaseValue = metricValue();
    private final JLabel productStatus = new JLabel("Select a P4 product from the SDE.");
    private TierView p2View;
    private TierView p3View;
    private RecipeView recipeView;
    private final JLabel unusedCaption = new JLabel(" ");
    private boolean inventoryReady;
    private boolean changingProducts;
    private InventorySnapshot inventory;
    private InventoryBalancePlan lastPlan;
    private P4BalanceRecipe selectedRecipe;

    record BalanceRun(InventoryBalancePlan plan) {}

    public BalanceInventoryPanel(BalanceInventoryController controller) {
        this.controller = controller;
        setLayout(new BorderLayout());
        setBackground(UiConstants.PAGE_BACKGROUND);
        add(UiComponents.pageHeader("Balance Inventory",
                "Choose a P4 product; its P3 and P2 requirements are resolved directly from the SDE."),
                BorderLayout.NORTH);

        inventoryPanel = new InventoryPanel(new InventoryPanel.Listener() {
            @Override public void inventoryTextSubmitted(String text) { onInventoryTextSubmitted(text); }
            @Override public void inventoryReset() { onInventoryReset(); }
            @Override public void inventoryTextChanged() { invalidateInventoryEdit(); }
        });
        populateProducts();
        p4Combo.setMaximumRowCount(18);
        p4Combo.setSelectedIndex(-1);
        p4Combo.addActionListener(e -> {
            if (!changingProducts) {
                onProductChanged();
            }
        });

        UiComponents.primaryButton(calculateButton);
        calculateButton.setEnabled(false);
        calculateButton.addActionListener(e -> onCalculate());

        VerticalScrollablePanel leftContent = new VerticalScrollablePanel();
        leftContent.setBackground(UiConstants.CARD_BACKGROUND);
        leftContent.setLayout(new BoxLayout(leftContent, BoxLayout.Y_AXIS));
        leftContent.setBorder(UiComponents.cardBorder());
        inventoryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftContent.add(inventoryPanel);
        leftContent.add(Box.createVerticalStrut(UiConstants.SECTION_GAP));
        JPanel product = buildProductSection();
        align(product);
        leftContent.add(product);
        leftContent.add(Box.createVerticalStrut(UiConstants.SECTION_GAP));
        JPanel calcBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        calcBar.setOpaque(false);
        calcBar.add(calculateButton);
        align(calcBar);
        leftContent.add(calcBar);

        JScrollPane leftScroll = pageScroll(leftContent, UiConstants.CARD_BACKGROUND);
        p2View = new TierView("P2 Balance", "P2 needed across the selected P4's P3 recipes", p2Model);
        p3View = new TierView("P3 Balance", "Direct P3 inputs of the selected P4 recipe", p3Model);
        recipeView = new RecipeView();
        VerticalScrollablePanel results = buildResults();
        JScrollPane resultsScroll = pageScroll(results, UiConstants.PAGE_BACKGROUND);
        MouseWheelForwarder.install(leftContent, leftScroll);
        MouseWheelForwarder.install(results, resultsScroll);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, resultsScroll);
        split.setResizeWeight(0.32);
        split.setContinuousLayout(true);
        split.setDividerLocation(0.32);
        split.setBorder(BorderFactory.createEmptyBorder(0, UiConstants.INNER_PADDING,
                UiConstants.INNER_PADDING, UiConstants.INNER_PADDING));
        add(split, BorderLayout.CENTER);
        clearResults();
    }

    private JPanel buildProductSection() {
        JPanel section = new JPanel(new BorderLayout(0, UiConstants.ROW_GAP));
        section.setOpaque(false);
        section.add(UiComponents.sectionHeader("P4 Product",
                "Discovered from the current SDE; typeID is retained internally."), BorderLayout.NORTH);
        p4Combo.setPrototypeDisplayValue(new BalanceInventoryController.P4Product(0,
                "Organic Mortar Applicators        "));
        section.add(p4Combo, BorderLayout.CENTER);
        productStatus.setForeground(UiConstants.SECONDARY);
        productStatus.setFont(UiConstants.METRIC_CAPTION_FONT);
        section.add(productStatus, BorderLayout.SOUTH);
        return section;
    }

    private void populateProducts() {
        changingProducts = true;
        try {
            p4Combo.removeAllItems();
            List<BalanceInventoryController.P4Product> products = controller.p4Products();
            for (var product : products) p4Combo.addItem(product);
            if (products.isEmpty()) {
                productStatus.setText("No Tier 4 products were found in the SDE.");
                productStatus.setForeground(UiConstants.ERROR);
            }
        } catch (RuntimeException e) {
            productStatus.setText("Unable to load P4 products: " + messageOf(e));
            productStatus.setForeground(UiConstants.ERROR);
        } finally {
            changingProducts = false;
        }
    }

    private void onProductChanged() {
        clearResults();
        updateProductStatus();
        selectedRecipe = null;
        if (recipeView != null) recipeView.clear();
        var product = (BalanceInventoryController.P4Product) p4Combo.getSelectedItem();
        if (product != null) {
            try {
                selectedRecipe = controller.recipe(product.typeId());
                if (recipeView != null) recipeView.show(selectedRecipe);
            } catch (RuntimeException e) {
                if (recipeView != null) recipeView.showError("Unable to resolve recipe: " + messageOf(e));
                errorLabel.setText("<html>" + escape(messageOf(e)) + "</html>");
            }
        }
        updateCalculateEnabled();
    }

    private VerticalScrollablePanel buildResults() {
        VerticalScrollablePanel column = new VerticalScrollablePanel();
        column.setBackground(UiConstants.PAGE_BACKGROUND);
        column.setLayout(new FullWidthStackLayout());
        column.setBorder(BorderFactory.createEmptyBorder(0, UiConstants.CARD_GAP,
                UiConstants.INNER_PADDING, 0));
        errorLabel.setForeground(UiConstants.ERROR);
        errorLabel.setFont(UiConstants.BODY_FONT);
        align(errorLabel);
        column.add(errorLabel);

        JPanel totalCard = UiComponents.card();
        totalCard.setLayout(new BorderLayout(18, UiConstants.ROW_GAP));
        totalCard.add(UiComponents.sectionHeader("Purchase Summary",
                "P2 and P3 are balanced independently; neither tier compensates for the other."),
                BorderLayout.NORTH);
        JPanel purchaseMetrics = new JPanel(new GridLayout(1, 3, UiConstants.CARD_GAP, 0));
        purchaseMetrics.setOpaque(false);
        purchaseMetrics.add(UiComponents.metric("P2 PURCHASE VOLUME", p2PurchaseValue));
        purchaseMetrics.add(UiComponents.metric("P3 PURCHASE VOLUME", p3PurchaseValue));
        purchaseMetrics.add(UiComponents.metric("TOTAL", totalPurchaseValue));
        totalCard.add(purchaseMetrics, BorderLayout.CENTER);
        align(totalCard);
        column.add(totalCard);
        column.add(Box.createVerticalStrut(UiConstants.CARD_GAP));
        column.add(recipeView.card);
        column.add(Box.createVerticalStrut(UiConstants.CARD_GAP));
        column.add(p2View.card);
        column.add(Box.createVerticalStrut(UiConstants.CARD_GAP));
        column.add(p3View.card);
        column.add(Box.createVerticalStrut(UiConstants.CARD_GAP));

        JPanel copyBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        copyBar.setOpaque(false);
        UiComponents.secondaryButton(copyShoppingButton);
        UiComponents.secondaryButton(copyTargetButton);
        copyShoppingButton.addActionListener(e -> copyPlan(true));
        copyTargetButton.addActionListener(e -> copyPlan(false));
        copyBar.add(copyShoppingButton);
        copyBar.add(copyTargetButton);
        align(copyBar);
        column.add(copyBar);

        JTable unusedTable = new JTable(unusedModel);
        UiComponents.table(unusedTable);
        JPanel unusedWrapper = new JPanel(new BorderLayout(0, UiConstants.ROW_GAP));
        unusedWrapper.setOpaque(false);
        unusedCaption.setForeground(UiConstants.SECONDARY);
        unusedWrapper.add(unusedCaption, BorderLayout.NORTH);
        unusedWrapper.add(compactTableScroll(unusedTable, 0), BorderLayout.CENTER);
        align(unusedWrapper);
        column.add(new CollapsibleSection("Show Unused Inventory", unusedWrapper, false));

        summary.setEditable(false);
        summary.setOpaque(false);
        summary.setFont(UiConstants.MONO_FONT);
        JPanel details = new JPanel(new BorderLayout());
        details.setOpaque(false);
        details.add(summary, BorderLayout.CENTER);
        align(details);
        column.add(new CollapsibleSection("Show Details", details, false));
        return column;
    }

    private static JScrollPane pageScroll(Component content, Color background) {
        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(background);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private static void align(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getMaximumSize().height));
    }

    private static DefaultTableModel materialModel() {
        return new DefaultTableModel(
                new Object[]{"Material", "Per Block", "Current", "Target", "Need to Add"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static JLabel metricValue() { return new JLabel("—"); }

    private final class TierView {
        final JPanel card = UiComponents.card();
        final JLabel blocks = metricValue();
        final JLabel additional = metricValue();
        final JLabel p4PerBlock = metricValue();
        final JLabel equivalentP4 = metricValue();
        final JLabel note = new JLabel(" ");
        final JTable table;
        final JScrollPane scroll;

        TierView(String title, String description, DefaultTableModel model) {
            card.setLayout(new BorderLayout(0, UiConstants.ROW_GAP));
            card.add(UiComponents.sectionHeader(title, description), BorderLayout.NORTH);
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            JPanel metrics = new JPanel(new GridLayout(1, 4, UiConstants.CARD_GAP, 0));
            metrics.setOpaque(false);
            metrics.add(UiComponents.metric("TARGET BLOCKS", blocks));
            metrics.add(UiComponents.metric("P4 UNITS / BLOCK", p4PerBlock));
            metrics.add(UiComponents.metric("EQUIVALENT P4 OUTPUT", equivalentP4));
            metrics.add(UiComponents.metric("ADDITIONAL VOLUME", additional));
            align(metrics);
            content.add(metrics);
            content.add(Box.createVerticalStrut(UiConstants.ROW_GAP));
            note.setFont(UiConstants.BODY_FONT);
            note.setForeground(UiConstants.SECONDARY);
            align(note);
            content.add(note);
            table = new JTable(model);
            UiComponents.table(table);
            table.setFillsViewportHeight(false);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            scroll = compactTableScroll(table, 0);
            align(scroll);
            content.add(scroll);
            card.add(content, BorderLayout.CENTER);
            align(card);
        }

        void show(InventoryBalancePlan.TierBalance balance) {
            blocks.setText(Formats.amount(balance.targetBlocks()));
            p4PerBlock.setText(Formats.amount(balance.p4UnitsPerBalanceBlock()));
            equivalentP4.setText(Formats.amount(balance.equivalentP4Units()));
            additional.setText(Formats.volume(balance.totalAdditionalVolume()));
            DefaultTableModel model = (DefaultTableModel) table.getModel();
            model.setRowCount(0);
            boolean hasStock = false;
            for (InventoryBalanceMaterial material : balance.materials()) {
                hasStock |= material.currentQuantity() > 0;
                model.addRow(new Object[]{material.commodity().name(),
                        Formats.amount(material.requiredPerBlock()), Formats.amount(material.currentQuantity()),
                        Formats.amount(material.targetQuantity()), Formats.addCell(material.addQuantity())});
            }
            String blockMeaning = "1 block = " + balance.p4RecipeCyclesPerBalanceBlock()
                    + (balance.p4RecipeCyclesPerBalanceBlock() == 1 ? " P4 recipe cycle" : " P4 recipe cycles")
                    + " = " + balance.p4UnitsPerBalanceBlock() + " "
                    + (balance.p4UnitsPerBalanceBlock() == 1 ? "P4 unit" : "P4 units");
            note.setText(blockMeaning + (!hasStock
                    ? " · No related P" + balance.tier()
                    + " stock found — target remains 0; no purchase is proposed."
                    : ""));
            resizeTable(scroll, table, model.getRowCount());
        }

        void clear(int tier) {
            blocks.setText("—");
            p4PerBlock.setText("—");
            equivalentP4.setText("—");
            additional.setText("—");
            ((DefaultTableModel) table.getModel()).setRowCount(0);
            note.setText("Select a P4 and parse inventory to calculate P" + tier + ".");
            resizeTable(scroll, table, 0);
        }
    }

    private final class RecipeView {
        final JPanel card = UiComponents.card();

        RecipeView() {
            card.setLayout(new BorderLayout(0, UiConstants.ROW_GAP));
            card.add(UiComponents.sectionHeader("Recipe Summary",
                    "P4 → P3 → P2 relationships for each executable balance block."),
                    BorderLayout.NORTH);
            recipeHierarchyPanel.setOpaque(false);
            recipeHierarchyPanel.setLayout(new BoxLayout(recipeHierarchyPanel, BoxLayout.Y_AXIS));
            card.add(recipeHierarchyPanel, BorderLayout.CENTER);
            clear();
            align(card);
        }

        void show(P4BalanceRecipe recipe) {
            recipeHierarchyPanel.removeAll();
            addCaption("SELECTED PRODUCT");
            addRow(recipe.p4Product().name(), UiConstants.TITLE_FONT, null);
            addGap(UiConstants.CARD_GAP);
            addSeparator();
            addGap(UiConstants.CARD_GAP);
            addHierarchy("P3 Balance Block", recipe.p3Hierarchy(), false);
            addGap(UiConstants.CARD_GAP);
            addSeparator();
            addGap(UiConstants.CARD_GAP);
            addHierarchy("P2 Balance Block", recipe.p2Hierarchy(), true);
            refresh();
        }

        void showError(String message) {
            recipeHierarchyPanel.removeAll();
            addRow(message, UiConstants.BODY_FONT, UiConstants.ERROR);
            refresh();
        }

        void clear() {
            recipeHierarchyPanel.removeAll();
            addRow("Select a P4 product to inspect its SDE recipe hierarchy.",
                    UiConstants.BODY_FONT, UiConstants.SECONDARY);
            refresh();
        }

        private void addHierarchy(String title, P4BalanceRecipe.RecipeHierarchy hierarchy,
                                  boolean includeP2) {
            addRow(title, UiConstants.BODY_BOLD_FONT, null);
            String cycles = hierarchy.p4RecipeCycles() + (hierarchy.p4RecipeCycles() == 1
                    ? " P4 recipe cycle" : " P4 recipe cycles");
            addRow("1 block = " + cycles + " = " + Formats.amount(hierarchy.p4Quantity())
                            + " × " + hierarchy.p4Product().name(),
                    UiConstants.METRIC_CAPTION_FONT, UiConstants.SECONDARY);
            addGap(UiConstants.ROW_GAP);
            addRow(hierarchy.p4Product().name() + " × "
                    + Formats.amount(hierarchy.p4Quantity()), UiConstants.TITLE_FONT, null);
            List<P4BalanceRecipe.P3Branch> branches = hierarchy.p3Branches();
            for (int branchIndex = 0; branchIndex < branches.size(); branchIndex++) {
                P4BalanceRecipe.P3Branch branch = branches.get(branchIndex);
                boolean lastBranch = branchIndex == branches.size() - 1;
                addRow((lastBranch ? "└─ " : "├─ ") + branch.commodity().name() + " × "
                        + Formats.amount(branch.quantity()), UiConstants.BODY_BOLD_FONT, null);
                if (includeP2) {
                    for (int inputIndex = 0; inputIndex < branch.p2Inputs().size(); inputIndex++) {
                        P4BalanceRecipe.Ingredient input = branch.p2Inputs().get(inputIndex);
                        boolean lastInput = inputIndex == branch.p2Inputs().size() - 1;
                        String prefix = (lastBranch ? "   " : "│  ")
                                + (lastInput ? "└─ " : "├─ ");
                        addRow(prefix + input.commodity().name() + " × "
                                        + Formats.amount(input.quantity()),
                                UiConstants.BODY_FONT, UiConstants.SECONDARY);
                    }
                    if (!lastBranch) addGap(3);
                }
            }
        }

        private void addCaption(String text) {
            addRow(text, UiConstants.METRIC_CAPTION_FONT, UiConstants.SECONDARY);
        }

        private void addRow(String text, Font font, Color color) {
            JLabel row = new JLabel(text);
            row.setFont(font);
            if (color != null) row.setForeground(color);
            align(row);
            recipeHierarchyPanel.add(row);
        }

        private void addSeparator() {
            JSeparator separator = new JSeparator();
            align(separator);
            separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            recipeHierarchyPanel.add(separator);
        }

        private void addGap(int height) {
            recipeHierarchyPanel.add(Box.createVerticalStrut(height));
        }

        private void refresh() {
            recipeHierarchyPanel.revalidate();
            recipeHierarchyPanel.repaint();
        }
    }

    private static JScrollPane compactTableScroll(JTable table, int rows) {
        JScrollPane scroll = new JScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resizeTable(scroll, table, rows);
        return scroll;
    }

    private static void resizeTable(JScrollPane scroll, JTable table, int rows) {
        int visible = Math.min(Math.max(1, rows), 12);
        int height = table.getTableHeader().getPreferredSize().height + visible * table.getRowHeight() + 3;
        scroll.setPreferredSize(new Dimension(0, height));
        scroll.setMinimumSize(new Dimension(0, height));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        scroll.revalidate();
    }

    private void onInventoryTextSubmitted(String text) {
        inventoryReady = false;
        inventory = null;
        clearResults();
        updateCalculateEnabled();
        inventoryPanel.setBusy(true);
        new SwingWorker<PiCalculatorController.InventoryStatus, Void>() {
            @Override protected PiCalculatorController.InventoryStatus doInBackground() {
                return controller.parseInventory(text);
            }
            @Override protected void done() {
                inventoryPanel.setBusy(false);
                try { inventoryLoaded(get()); }
                catch (Exception e) { inventoryFailed(messageOf(e)); }
            }
        }.execute();
    }

    private void invalidateInventoryEdit() {
        if (inventoryReady || lastPlan != null) {
            inventoryReady = false;
            inventory = null;
            clearResults();
            updateCalculateEnabled();
        }
    }

    private void onInventoryReset() {
        inventoryReady = false;
        inventory = null;
        clearResults();
        updateCalculateEnabled();
    }

    void inventoryLoaded(PiCalculatorController.InventoryStatus status) {
        inventory = status.snapshot();
        inventoryReady = !inventory.isEmpty();
        inventoryPanel.showLoaded(status);
        updateCalculateEnabled();
    }

    void inventoryFailed(String message) {
        inventoryReady = false;
        inventory = null;
        clearResults();
        inventoryPanel.showError(message);
        updateCalculateEnabled();
    }

    void showPlan(BalanceRun run) {
        InventoryBalancePlan plan = run.plan();
        lastPlan = plan;
        errorLabel.setText(" ");
        p2PurchaseValue.setText(Formats.volume(plan.p2Balance().totalAdditionalVolume()));
        p3PurchaseValue.setText(Formats.volume(plan.p3Balance().totalAdditionalVolume()));
        totalPurchaseValue.setText(Formats.volume(plan.totalPurchaseVolume()));
        p2View.show(plan.p2Balance());
        p3View.show(plan.p3Balance());
        unusedModel.setRowCount(0);
        for (InventoryBalancePlan.UnusedItem item : plan.unusedInventory()) {
            unusedModel.addRow(new Object[]{item.commodity().name(), Formats.amount(item.quantity()),
                    "P" + item.tier()});
        }
        unusedCaption.setText(plan.unusedInventory().isEmpty()
                ? "All parsed inventory is related to this P4."
                : plan.unusedInventory().size() + " unrelated item(s) were ignored.");
        summary.setText("P2 target blocks: " + Formats.amount(plan.p2Balance().targetBlocks())
                + "\nP2 equivalent P4 output: " + Formats.amount(plan.p2Balance().equivalentP4Units())
                + "\nP3 target blocks: " + Formats.amount(plan.p3Balance().targetBlocks())
                + "\nP3 equivalent P4 output: " + Formats.amount(plan.p3Balance().equivalentP4Units())
                + "\nP2 additional volume: " + Formats.volume(plan.p2Balance().totalAdditionalVolume())
                + "\nP3 additional volume: " + Formats.volume(plan.p3Balance().totalAdditionalVolume()));
        copyShoppingButton.setEnabled(true);
        copyTargetButton.setEnabled(true);
    }

    void clearResults() {
        lastPlan = null;
        errorLabel.setText(" ");
        p2PurchaseValue.setText("—");
        p3PurchaseValue.setText("—");
        totalPurchaseValue.setText("—");
        if (p2View != null) p2View.clear(2);
        if (p3View != null) p3View.clear(3);
        p2Model.setRowCount(0);
        p3Model.setRowCount(0);
        unusedModel.setRowCount(0);
        unusedCaption.setText(" ");
        summary.setText(" ");
        copyShoppingButton.setEnabled(false);
        copyTargetButton.setEnabled(false);
    }

    void showError(String message) {
        clearResults();
        errorLabel.setText("<html>" + escape(message) + "</html>");
    }

    private void onCalculate() {
        InventorySnapshot snapshot = inventory;
        P4BalanceRecipe recipe = selectedRecipe;
        if (snapshot == null || recipe == null) return;
        clearResults();
        calculateButton.setText("Calculating…");
        calculateButton.setEnabled(false);
        new SwingWorker<BalanceRun, Void>() {
            @Override protected BalanceRun doInBackground() {
                return new BalanceRun(controller.balance(snapshot, recipe));
            }
            @Override protected void done() {
                calculateButton.setText("Calculate Balance");
                try { showPlan(get()); }
                catch (Exception e) { showError(messageOf(e)); }
                updateCalculateEnabled();
            }
        }.execute();
    }

    private void updateProductStatus() {
        var product = (BalanceInventoryController.P4Product) p4Combo.getSelectedItem();
        productStatus.setText(product == null ? "Select a P4 product from the SDE."
                : "Selected SDE typeID: " + product.typeId());
        productStatus.setForeground(UiConstants.SECONDARY);
    }

    private void updateCalculateEnabled() {
        calculateButton.setEnabled(inventoryReady && selectedRecipe != null);
    }

    private void copyPlan(boolean shopping) {
        if (lastPlan == null) return;
        String text = shopping ? CopyShoppingListFormatter.shoppingList(lastPlan)
                : CopyShoppingListFormatter.targetInventory(lastPlan);
        if (!text.isBlank()) Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private static String messageOf(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
    }

    private static final class VerticalScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(18, r.height - 36); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
