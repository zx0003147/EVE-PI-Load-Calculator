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
 * Numbers rendered (must match spec §41):
 *   Load Allocation : 133 blocks / 7980 each / 399 IRD / 53,865 used / 135 remaining
 *   Balance         : 1,238 blocks / 51d 14h / 3,714 IRD / 115,731.75 m3 / Oxides +55
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

        SwingUtilities.invokeAndWait(() -> {
            app.pack();
            app.setLocationRelativeTo(null);
            app.setAlwaysOnTop(true);
            app.setVisible(true);
        });
        Thread.sleep(800);
        capture(app, "out/ui-allocation.png");

        // ---- Tab 1: Balance Inventory — real inventory vs Pandogodzilla ----
        SwingUtilities.invokeAndWait(() -> {
            tabs.setSelectedIndex(1);
            var status = controller.parseInventory(REAL_INVENTORY);
            balance.inventoryLoaded(status);
            balance.templateLoaded(controller.loadPlanetTemplate(templateJson));
            var plan = new BalanceInventoryController(controller).balance(
                    status.snapshot(), controller.loadPlanetTemplate(templateJson).plan());
            balance.showPlan(new BalanceInventoryPanel.BalanceRun(plan));
        });
        Thread.sleep(800);
        capture(app, "out/ui-balance.png");

        SwingUtilities.invokeLater(() -> app.setAlwaysOnTop(false));

        System.out.println("screenshots written: out/ui-allocation.png, out/ui-balance.png");
        controller.close();
        System.exit(0);
    }

    private static void capture(AppFrame app, String path) throws Exception {
        Rectangle bounds = app.getBounds();
        bounds.setLocation(app.getLocationOnScreen());
        BufferedImage img = new Robot().createScreenCapture(bounds);
        ImageIO.write(img, "png", Path.of(path).toFile());
    }
}
