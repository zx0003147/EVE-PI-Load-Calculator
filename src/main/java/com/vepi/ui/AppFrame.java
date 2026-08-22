package com.vepi.ui;

import com.vepi.app.BalanceInventoryController;
import com.vepi.app.PiCalculatorController;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Application main window: two independent tabs (spec §1, §23, §28).
 *
 * <ol>
 *   <li><b>Load Allocation</b> — the existing inventory → multi-planet P2
 *       load feature, unchanged, first tab (it is the primary function).</li>
 *   <li><b>Balance Inventory</b> — the second feature: one inventory + one
 *       template → which P2 to top up to whole sustainable blocks.</li>
 * </ol>
 *
 * <p>The two tabs share nothing except the SDE-backed {@link PiCalculatorController}
 * seam: separate input panels, separate state (spec §21 option A, §22). The
 * controller's SDE handle is closed once when the window closes.
 */
public final class AppFrame extends JFrame {

    public AppFrame(PiCalculatorController controller) {
        super("EVE PI Load Calculator");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                controller.close();
            }
        });

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.addTab("Load Allocation", new AllocationFrame(controller));
        tabs.addTab("Balance Inventory",
                new BalanceInventoryPanel(new BalanceInventoryController(controller)));
        setContentPane(tabs);

        setPreferredSize(new Dimension(1250, 850));
        pack();
        setMinimumSize(new Dimension(1100, 760));
        setLocationRelativeTo(null);
    }

    /** Standard entry: system look, EDT startup. */
    public static void launch(PiCalculatorController controller) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the cross-platform look
        }
        SwingUtilities.invokeLater(() -> new AppFrame(controller).setVisible(true));
    }
}
