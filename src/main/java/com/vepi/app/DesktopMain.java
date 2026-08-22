package com.vepi.app;

import com.vepi.ui.AppFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * Desktop GUI entry point.
 *
 * <p>Locates the SDE database automatically so users never pass a --db flag.
 * Resolution is delegated to {@link ApplicationPaths}, which prefers an
 * explicit {@code --db=} override, then the packaged jpackage app-image
 * location (via {@code jpackage.app-path}), then a development fallback.
 * The working directory is never consulted in packaged mode.
 */
public final class DesktopMain {

    public static void main(String[] args) {
        Path db = locateDb(args);
        if (db == null) {
            // Exit AFTER the dialog is dismissed — calling System.exit on the
            // main thread would kill the async dialog before it is painted.
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null,
                        """
                                SDE database not found.

                                Expected one of:
                                  app\\data\\pi-sde.db
                                  data\\sde\\pi-sde.db
                                  data\\pi-sde.db

                                located next to the program (or start with --db=<path>).""",
                        "EVE PI Load Calculator", JOptionPane.ERROR_MESSAGE);
                System.exit(2);
            });
            return;
        }

        PiCalculatorController controller = null;
        try {
            controller = new PiCalculatorController(db);
        } catch (RuntimeException e) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null,
                        "Failed to open SDE database:\n" + db + "\n\nReason: " + e.getMessage(),
                        "EVE PI Load Calculator", JOptionPane.ERROR_MESSAGE);
                System.exit(3);
            });
            return;
        }

        final var theController = controller;
        SwingUtilities.invokeLater(() ->
                AppFrame.launch(theController));
    }

    /** Resolved DB path or null when nothing was found. */
    static Path locateDb(String[] args) {
        Path explicit = null;
        for (String a : args) {
            if (a.startsWith("--db=")) {
                explicit = Path.of(a.substring(5));
                break;
            }
        }
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                explicit,
                ApplicationPaths.launcherExecutablePath(),
                Path.of("").toAbsolutePath());
        return r.found() ? r.database() : null;
    }
}
