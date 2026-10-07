package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;

import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI/UX revision screenshot tool: drives the REAL AppFrame with the
 * Pandogodzilla scenario on both tabs (same render entry points the workers
 * use), then captures each tab to out/ui-allocation.png / out/ui-balance.png
 * for manual visual review.
 *
 * The Balance tab uses the product-driven IRD workflow (typeID 2868).
 */
public class UiScreenshot {

    private static final String REAL_INVENTORY = """
            Biocells\t46080
            Mechanical Parts\t40594
            Miniature Electronics\t61830
            Oxides\t74225
            Polytextiles\t70909
            Superconductors\t51110
            Supertensile Plastics\t65939
            Transmitter\t31390
            Viral Agent\t72134
            Gel-Matrix Biopaste\t1527
            Hazmat Detection Systems\t41
            Planetary Vehicles\t688
            """;

    public static void main(String[] args) throws Exception {
        PiCalculatorController controller =
                new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        String templateJson = Files.readString(Path.of("data/templates/Pandogodzilla.json"));

        AtomicReference<AppFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(
                        javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            ref.set(new AppFrame(controller));
        });
        AppFrame app = ref.get();
        JTabbedPane tabs = (JTabbedPane) app.getContentPane();
        AllocationFrame alloc = (AllocationFrame) tabs.getComponentAt(0);
        BalanceInventoryPanel balance = (BalanceInventoryPanel) tabs.getComponentAt(1);

        SwingUtilities.invokeAndWait(() -> {
            app.pack();
            app.setLocationRelativeTo(null);
            app.setAlwaysOnTop(true);
            app.setVisible(true);
        });
        Thread.sleep(400);
        capture(app, "out/ui-allocation-disabled.png");

        // ---- Tab 0: Load Allocation — Pandogodzilla @ 54000 m3 ----
        SwingUtilities.invokeAndWait(() -> {
            var status = controller.parseInventory(REAL_INVENTORY);
            alloc.inventoryLoaded(status);
            alloc.addPlanet();
            long id = alloc.planetPanels.get(0).id();
            alloc.planetTemplateLoaded(id, controller.loadPlanetTemplate(templateJson));
            alloc.planetPanels.get(0).capacityPanel.setCapacityText("54000");
            AllocationFrame.AllocationRun run = alloc.computeRun(status.snapshot(),
                    alloc.planetPanels);
            alloc.showPlan(run);
        });

        SwingUtilities.invokeAndWait(app::validate);
        Thread.sleep(800);
        capture(app, "out/ui-allocation.png");
        SwingUtilities.invokeAndWait(() -> scrollAllocationInputToBottom(alloc));
        Thread.sleep(300);
        capture(app, "out/ui-allocation-bottom.png");
        captureButtonStates(app, alloc);

        // ---- Tab 1: Balance Inventory — real inventory + IRD product ----
        SwingUtilities.invokeAndWait(() -> {
            tabs.setSelectedIndex(1);
            var status = controller.parseInventory(REAL_INVENTORY);
            balance.inventoryLoaded(status);
            for (int i = 0; i < balance.p4Combo.getItemCount(); i++) {
                if (balance.p4Combo.getItemAt(i).typeId() == 2868L) {
                    balance.p4Combo.setSelectedIndex(i);
                    break;
                }
            }
            var plan = new BalanceInventoryController(controller).balance(status.snapshot(), 2868L);
            balance.showPlan(new BalanceInventoryPanel.BalanceRun(plan));
        });
        Thread.sleep(800);
        capture(app, "out/ui-balance.png");

        SwingUtilities.invokeLater(() -> app.setAlwaysOnTop(false));

        System.out.println("screenshots written: out/ui-allocation.png, "
                + "out/ui-allocation-disabled.png, "
                + "out/ui-allocation-bottom.png, out/ui-allocation-hover.png, "
                + "out/ui-allocation-pressed.png, out/ui-balance.png");
        controller.close();
        System.exit(0);
    }

    private static void capture(AppFrame app, String path) throws Exception {
        Rectangle bounds = app.getBounds();
        bounds.setLocation(app.getLocationOnScreen());
        BufferedImage img = new Robot().createScreenCapture(bounds);
        ImageIO.write(img, "png", Path.of(path).toFile());
    }

    private static void scrollAllocationInputToBottom(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JScrollPane scroll) {
                java.awt.Component view = scroll.getViewport().getView();
                if (view != null && view.getClass().getName().contains("AllocationFrame$PageColumn")) {
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                }
            }
            if (child instanceof java.awt.Container container) {
                scrollAllocationInputToBottom(container);
            }
        }
    }

    private static void captureButtonStates(AppFrame app, AllocationFrame alloc) throws Exception {
        SwingUtilities.invokeAndWait(alloc.resultPanel::clear);
        Robot robot = new Robot();
        java.awt.Point p = alloc.calculateButton.getLocationOnScreen();
        int x = p.x + alloc.calculateButton.getWidth() / 2;
        int y = p.y + alloc.calculateButton.getHeight() / 2;
        robot.mouseMove(x, y);
        robot.delay(250);
        capture(app, "out/ui-allocation-hover.png");
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(150);
        capture(app, "out/ui-allocation-pressed.png");
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        boolean rendered = false;
        for (int attempt = 0; attempt < 40 && !rendered; attempt++) {
            robot.delay(50);
            AtomicReference<Boolean> state = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> state.set(
                    alloc.resultPanel.sections.size() == 1
                            && alloc.calculateButton.isEnabled()
                            && "Calculate Allocation".equals(alloc.calculateButton.getText())));
            rendered = state.get();
        }
        if (!rendered) throw new AssertionError("real mouse click did not render allocation result");
    }
}
