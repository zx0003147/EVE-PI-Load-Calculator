package com.vepi.ui;

import com.vepi.allocation.AllocationPlan;
import com.vepi.allocation.PlanetAllocation;
import com.vepi.allocation.PlanetRequest;
import com.vepi.app.PiCalculatorController;
import com.vepi.inventory.InventorySnapshot;
import com.vepi.load.LoadOptimizer;
import com.vepi.template.TemplateException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main window of the inventory-driven workflow:
 * paste inventory → paste one template + capacity per planet → Calculate
 * Allocation → per-planet P2 load (P3 listed separately).
 *
 * <p>The frame only collects input, calls {@link PiCalculatorController} and
 * renders records. Fairness, inventory subtraction, production blocks and
 * P2/P3 classification all stay in the model layer. Planet order in the UI is
 * the insertion order handed to the planner — stable across recalculations.
 *
 * <p>Threading: SDE-backed parsing (inventory, templates) and the allocation
 * run execute on {@link SwingWorker}s; all rendering happens on the EDT.
 * The sync render methods ({@code inventoryLoaded}, {@code planetTemplateLoaded},
 * {@code showPlan}) are the same entry points the workers call — the GUI smoke
 * test drives them directly.
 */
public final class AllocationFrame extends JPanel {

    private final PiCalculatorController controller;
    final InventoryPanel inventoryPanel;          // package-visible for the GUI smoke test
    final AllocationResultPanel resultPanel;      // package-visible for the GUI smoke test
    final JButton calculateButton;                // package-visible for the GUI smoke test
    final List<PlanetPanel> planetPanels = new ArrayList<>();   // insertion order = planner order

    private final JPanel planetsContainer;
    private final AtomicLong nextPlanetId = new AtomicLong(1);
    private boolean inventoryReady = false;
    private InventorySnapshot inventory;

    /** One allocation run's render inputs (plan + per-planet views). */
    record AllocationRun(AllocationPlan plan, List<AllocationResultPanel.PlanetView> views) {
    }

    public AllocationFrame(PiCalculatorController controller) {
        this.controller = controller;
        setLayout(new BorderLayout(0, 0));

        // ---- LEFT COLUMN: inputs (inventory → planets → Calculate) ----
        // ---- CURRENT INVENTORY ----
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

        // ---- PLANETS (dynamic cards, own scroll) ----
        planetsContainer = new JPanel();
        planetsContainer.setLayout(new javax.swing.BoxLayout(planetsContainer, javax.swing.BoxLayout.Y_AXIS));
        planetsContainer.setOpaque(false);

        JPanel addBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, UiConstants.ROW_GAP));
        JButton addPlanet = new JButton("+ Add Planet");
        addPlanet.addActionListener(e -> addPlanet());
        addBar.add(addPlanet);

        JPanel planetsColumn = new JPanel(new BorderLayout(0, UiConstants.ROW_GAP));
        planetsColumn.setOpaque(false);
        planetsColumn.add(planetsContainer, BorderLayout.CENTER);
        planetsColumn.add(addBar, BorderLayout.SOUTH);
        JScrollPane planetsScroll = new JScrollPane(planetsColumn);
        planetsScroll.setBorder(BorderFactory.createTitledBorder("Planets"));
        planetsScroll.getVerticalScrollBar().setUnitIncrement(16);

        // ---- CALCULATE (primary action, slightly emphasized) ----
        calculateButton = new JButton("Calculate Allocation");
        calculateButton.setEnabled(false);
        calculateButton.setFont(UiConstants.BODY_BOLD_FONT.deriveFont(14f));
        calculateButton.setToolTipText("Allocate shared inventory across all planets (fair runtime)");
        calculateButton.addActionListener(e -> onCalculate());
        JPanel calcBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, UiConstants.ROW_GAP));
        calcBar.add(calculateButton);

        JPanel left = new JPanel(new BorderLayout(0, UiConstants.SECTION_GAP));
        left.setBorder(BorderFactory.createEmptyBorder(UiConstants.INNER_PADDING,
                UiConstants.INNER_PADDING, UiConstants.INNER_PADDING, UiConstants.CARD_GAP));
        left.add(inventoryPanel, BorderLayout.NORTH);
        left.add(planetsScroll, BorderLayout.CENTER);
        left.add(calcBar, BorderLayout.SOUTH);

        // ---- RIGHT COLUMN: results (own scroll; the main visual weight) ----
        resultPanel = new AllocationResultPanel();
        JScrollPane right = new JScrollPane(resultPanel);
        right.getVerticalScrollBar().setUnitIncrement(16);
        right.setBorder(BorderFactory.createEmptyBorder(UiConstants.INNER_PADDING,
                UiConstants.CARD_GAP, UiConstants.INNER_PADDING, UiConstants.INNER_PADDING));

        javax.swing.JSplitPane split =
                new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.40);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    // ---- planet management ----

    /** Adds a new planet card (renumbered to its insertion position). */
    void addPlanet() {   // package-visible for the GUI smoke test
        long id = nextPlanetId.getAndIncrement();
        PlanetPanel panel = new PlanetPanel(id, new PlanetPanel.Listener() {
            @Override
            public void templateTextSubmitted(long planetId, String text) {
                onPlanetTemplateText(planetId, text);
            }

            @Override
            public void templateFileChosen(long planetId, Path file) {
                onPlanetTemplateFile(planetId, file);
            }

            @Override
            public void templateReset(long planetId) {
                PlanetPanel p = panelById(planetId);
                if (p != null) {
                    p.clearLoadedTemplate();
                }
                resultPanel.clear();
                updateCalculateEnabled();
            }

            @Override
            public void capacityValidityChanged(long planetId, boolean valid) {
                resultPanel.clear();
                updateCalculateEnabled();
            }

            @Override
            public void removeRequested(long planetId) {
                removePlanet(planetId);
            }
        });
        planetPanels.add(panel);
        // Bottom margin lives on the card itself — removing the card leaves no stray spacing.
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 10, 0), panel.getBorder()));
        panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        planetsContainer.add(panel);
        renumberPlanets();
        resultPanel.clear();
        updateCalculateEnabled();
        planetsContainer.revalidate();
        planetsContainer.repaint();
    }

    /** Removes a planet card, renumbers the rest, wipes stale results. */
    void removePlanet(long planetId) {   // package-visible for the GUI smoke test
        PlanetPanel panel = panelById(planetId);
        if (panel == null) {
            return;
        }
        planetPanels.remove(panel);
        planetsContainer.remove(panel);
        renumberPlanets();
        resultPanel.clear();
        updateCalculateEnabled();
        planetsContainer.revalidate();
        planetsContainer.repaint();
    }

    private void renumberPlanets() {
        for (int i = 0; i < planetPanels.size(); i++) {
            planetPanels.get(i).setDisplayNumber(i + 1);
        }
    }

    private PlanetPanel panelById(long planetId) {
        for (PlanetPanel p : planetPanels) {
            if (p.id() == planetId) {
                return p;
            }
        }
        return null;
    }

    // ---- inventory flow (worker + sync render entry) ----

    private void onInventoryTextSubmitted(String text) {
        inventoryPanel.setBusy(true);
        resultPanel.clear();
        inventoryReady = false;
        inventory = null;
        updateCalculateEnabled();

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
                    inventoryFailed(inventoryErrorMessage(e));
                }
            }
        }.execute();
    }

    private void onInventoryReset() {
        inventoryReady = false;
        inventory = null;
        resultPanel.clear();
        updateCalculateEnabled();
    }

    void inventoryLoaded(PiCalculatorController.InventoryStatus status) {   // test entry
        inventoryPanel.showLoaded(status);
        this.inventory = status.snapshot();
        this.inventoryReady = true;
        resultPanel.clear();
        updateCalculateEnabled();
    }

    void inventoryFailed(String message) {   // test entry
        inventoryPanel.showError(message);
        this.inventory = null;
        this.inventoryReady = false;
        updateCalculateEnabled();
    }

    // ---- planet template flow (worker + sync render entry) ----

    private void onPlanetTemplateText(long planetId, String text) {
        PlanetPanel panel = panelById(planetId);
        if (panel == null) {
            return;
        }
        panel.templatePanel.setBusy(true, "Loading template...");
        panel.clearLoadedTemplate();
        resultPanel.clear();
        updateCalculateEnabled();

        new SwingWorker<PiCalculatorController.PlanetTemplate, Void>() {
            @Override
            protected PiCalculatorController.PlanetTemplate doInBackground() {
                return controller.loadPlanetTemplate(text);
            }

            @Override
            protected void done() {
                panel.templatePanel.setBusy(false, null);
                try {
                    planetTemplateLoaded(planetId, get());
                } catch (Exception e) {
                    planetTemplateFailed(planetId, templateErrorMessage(e));
                }
            }
        }.execute();
    }

    private void onPlanetTemplateFile(long planetId, Path file) {
        PlanetPanel panel = panelById(planetId);
        if (panel == null) {
            return;
        }
        panel.templatePanel.setBusy(true, "Reading file:\n" + file.getFileName() + " ...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return Files.readString(file);
            }

            @Override
            protected void done() {
                try {
                    String text = get();
                    panel.templatePanel.setTemplateText(text);
                    onPlanetTemplateText(planetId, text);   // same pipeline as pasting
                } catch (Exception e) {
                    panel.templatePanel.setBusy(false, null);
                    planetTemplateFailed(planetId,
                            "Cannot read template file " + file.getFileName());
                }
            }
        }.execute();
    }

    void planetTemplateLoaded(long planetId, PiCalculatorController.PlanetTemplate template) {
        PlanetPanel panel = panelById(planetId);
        if (panel == null) {
            return;
        }
        panel.setLoadedTemplate(template);
        panel.templatePanel.showSummary(template.summary());
        resultPanel.clear();
        updateCalculateEnabled();
    }

    void planetTemplateFailed(long planetId, String message) {
        PlanetPanel panel = panelById(planetId);
        if (panel == null) {
            return;
        }
        panel.clearLoadedTemplate();
        panel.templatePanel.showError(message);
        updateCalculateEnabled();
    }

    // ---- allocation flow ----

    private void onCalculate() {
        if (!calculateButton.isEnabled()) {
            return;
        }
        final List<PlanetPanel> panels = List.copyOf(planetPanels);
        final InventorySnapshot snapshot = inventory;
        if (snapshot == null || panels.isEmpty()) {
            return;
        }

        calculateButton.setEnabled(false);
        calculateButton.setText("Calculating...");

        new SwingWorker<AllocationRun, Void>() {
            @Override
            protected AllocationRun doInBackground() {
                return computeRun(snapshot, panels);
            }

            @Override
            protected void done() {
                calculateButton.setText("Calculate Allocation");
                try {
                    showPlan(get());
                } catch (Exception e) {
                    resultPanel.clear();
                    javax.swing.JOptionPane.showMessageDialog(AllocationFrame.this,
                            rootCauseMessage(e), "Calculation failed",
                            javax.swing.JOptionPane.WARNING_MESSAGE);
                }
                updateCalculateEnabled();
            }
        }.execute();
    }

    /**
     * Synchronous core of Calculate Allocation: builds the planet requests in
     * UI insertion order, runs the planner, and prepares the per-planet render
     * views (including zero-block explanations). Testable without workers.
     */
    AllocationRun computeRun(InventorySnapshot snapshot, List<PlanetPanel> panels) {
        List<PlanetRequest> requests = new ArrayList<>(panels.size());
        for (int i = 0; i < panels.size(); i++) {
            PlanetPanel p = panels.get(i);
            requests.add(new PlanetRequest("Planet " + (i + 1), p.loadedTemplate().plan(),
                    LoadOptimizer.parseCapacity(p.capacityText())));
        }

        AllocationPlan plan = controller.calculateAllocation(snapshot, requests);

        List<AllocationResultPanel.PlanetView> views = new ArrayList<>(panels.size());
        for (int i = 0; i < panels.size(); i++) {
            PlanetAllocation allocation = plan.planets().get(i);
            PiCalculatorController.ZeroBlockExplanation explanation = null;
            if (allocation.blockCount() == 0) {
                explanation = controller.explainZeroBlocks(requests.get(i), plan);
            }
            views.add(new AllocationResultPanel.PlanetView(
                    allocation, explanation,
                    panels.get(i).loadedTemplate().summary().displayName(),
                    controller.bottleneckNotes(requests.get(i).plan())));
        }
        return new AllocationRun(plan, views);
    }

    void showPlan(AllocationRun run) {   // test entry
        resultPanel.showPlan(run.views(), run.plan());
    }

    // ---- enablement ----

    /** Test support: wipes inventory + planet state so each test starts clean.
     *  Must be called on the EDT. */
    void resetState() {
        inventoryReady = false;
        inventory = null;
        for (PlanetPanel p : List.copyOf(planetPanels)) {
            removePlanet(p.id());
        }
        inventoryPanel.clearAll();
        resultPanel.clear();
        updateCalculateEnabled();
    }

    private void updateCalculateEnabled() {
        Runnable update = () -> {
            List<AllocationReadiness.PlanetInput> inputs = planetPanels.stream()
                    .map(p -> new AllocationReadiness.PlanetInput(p.templateLoaded(), p.capacityValid()))
                    .toList();
            calculateButton.setEnabled(AllocationReadiness.ready(inventoryReady, inputs));
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    // ---- error message mapping (inline, never stack traces) ----

    private static String inventoryErrorMessage(Exception e) {
        Throwable t = rootCause(e);
        if (t instanceof com.vepi.inventory.InventoryException) {
            return "Unable to parse inventory.\n\n" + t.getMessage();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String templateErrorMessage(Exception e) {
        Throwable t = rootCause(e);
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

    private static Throwable rootCause(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String rootCauseMessage(Exception e) {
        Throwable t = rootCause(e);
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
