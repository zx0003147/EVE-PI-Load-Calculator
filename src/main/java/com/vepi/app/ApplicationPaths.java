package com.vepi.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Central authority for locating application-owned files (currently the SDE
 * SQLite database). This is the <em>only</em> class allowed to know the
 * on-disk layout of a packaged application.
 *
 * <h2>Resolution priority</h2>
 * <ol>
 *   <li><b>Explicit override</b> — {@code --db=<path>} argument wins if given.</li>
 *   <li><b>Packaged app-image</b> — when launched by a jpackage launcher the
 *       JVM property {@code jpackage.app-path} holds the full path of the
 *       launcher executable; the application root is its parent directory and
 *       packaged candidates are searched beneath {@code appRoot}.</li>
 *   <li><b>Development fallback</b> — otherwise candidates are resolved
 *       against the supplied development root (the project directory when
 *       started via {@code run-gui.bat}).</li>
 * </ol>
 *
 * <p>The working directory is never consulted in packaged mode, so double-
 * clicking the EXE from Explorer, a shortcut or any shell working directory
 * resolves the bundled database identically.
 *
 * <p>All path knowledge lives here: no other class may assemble
 * {@code app/data/pi-sde.db}-style paths itself.
 */
public final class ApplicationPaths {

    /** Where a resolved database came from. */
    public enum DbSource {
        /** Explicit {@code --db=} override supplied on the command line. */
        EXPLICIT_OVERRIDE,
        /** Found inside a jpackage app-image layout. */
        PACKAGED_APP_IMAGE,
        /** Development-tree fallback relative to the development root. */
        DEVELOPMENT_FALLBACK
    }

    /**
     * Resolution outcome. {@code database == null} means "not found";
     * {@code searched} then lists every candidate that was attempted so the
     * caller can show a diagnosable error.
     */
    public record SdeResolution(Path database, DbSource source, List<Path> searched) {
        /** True when a usable database file was located. */
        public boolean found() {
            return database != null;
        }
    }

    /** Locations tried under an app-image root, in priority order. */
    static final List<String> PACKAGED_CANDIDATES = List.of(
            "app/data/pi-sde.db",
            "data/sde/pi-sde.db",
            "data/pi-sde.db");

    /** Locations tried under the development root, in priority order. */
    static final List<String> DEVELOPMENT_CANDIDATES = List.of(
            "data/sde/pi-sde.db",
            "data/pi-sde.db");

    private ApplicationPaths() {
    }

    /**
     * Full path of the jpackage launcher executable, or {@code null} when the
     * process was not started through one (plain {@code java} during
     * development).
     */
    public static String launcherExecutablePath() {
        return System.getProperty("jpackage.app-path");
    }

    /**
     * Resolves the SDE database using the documented priority order.
     *
     * @param explicitDb         explicit override path ({@code null} = none)
     * @param launcherExecutable value of {@code jpackage.app-path}
     *                           ({@code null}/blank = not packaged)
     * @param developmentRoot    root used for the development fallback
     *                           ({@code null} = current working directory);
     *                           ignored entirely when packaged
     * @return resolution with the located file or every attempted candidate
     */
    public static SdeResolution resolveSdeDatabase(Path explicitDb,
                                                   String launcherExecutable,
                                                   Path developmentRoot) {
        List<Path> searched = new ArrayList<>();

        if (explicitDb != null && !explicitDb.toString().isBlank()) {
            Path p = explicitDb.toAbsolutePath().normalize();
            searched.add(p);
            return new SdeResolution(Files.isRegularFile(p) ? p : null,
                    DbSource.EXPLICIT_OVERRIDE, searched);
        }

        if (launcherExecutable != null && !launcherExecutable.isBlank()) {
            Path exe = Path.of(launcherExecutable).toAbsolutePath().normalize();
            Path appRoot = exe.getParent() == null ? exe : exe.getParent();
            for (String rel : PACKAGED_CANDIDATES) {
                Path candidate = appRoot.resolve(rel).normalize();
                searched.add(candidate);
                if (Files.isRegularFile(candidate)) {
                    return new SdeResolution(candidate, DbSource.PACKAGED_APP_IMAGE, searched);
                }
            }
            return new SdeResolution(null, DbSource.PACKAGED_APP_IMAGE, searched);
        }

        Path devRoot = developmentRoot == null
                ? Path.of("").toAbsolutePath()
                : developmentRoot.toAbsolutePath().normalize();
        for (String rel : DEVELOPMENT_CANDIDATES) {
            Path candidate = devRoot.resolve(rel).normalize();
            searched.add(candidate);
            if (Files.isRegularFile(candidate)) {
                return new SdeResolution(candidate, DbSource.DEVELOPMENT_FALLBACK, searched);
            }
        }
        return new SdeResolution(null, DbSource.DEVELOPMENT_FALLBACK, searched);
    }
}
