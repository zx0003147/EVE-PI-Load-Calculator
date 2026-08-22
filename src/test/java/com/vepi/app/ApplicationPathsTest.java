package com.vepi.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path-resolution tests for {@link ApplicationPaths}. Uses the injectable
 * {@code resolveSdeDatabase(explicit, launcherExecutable, developmentRoot)}
 * signature so no global {@code System} properties are mutated.
 */
class ApplicationPathsTest {

    @TempDir
    Path tmp;

    private Path writeDb(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "fake-sde");
        return p;
    }

    // ---- A. Explicit override wins ----
    @Test
    void explicitOverrideWinsOverEverything() throws Exception {
        Path explicit = writeDb(tmp, "custom/override.db");
        // Even a bogus launcher path and a bogus dev root must not win.
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                explicit,
                "C:\\Portable\\App\\App.exe",
                tmp.resolve("nonexistent-dev"));
        assertTrue(r.found());
        assertEquals(ApplicationPaths.DbSource.EXPLICIT_OVERRIDE, r.source());
        assertEquals(explicit.toAbsolutePath().normalize(), r.database());
    }

    @Test
    void explicitOverrideMissingDbReportsNotFound() {
        Path missing = tmp.resolve("nope.db");
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                missing, null, tmp);
        assertFalse(r.found());
        assertNull(r.database());
        assertEquals(ApplicationPaths.DbSource.EXPLICIT_OVERRIDE, r.source());
    }

    // ---- B. Packaged launcher path ----
    @Test
    void packagedLauncherResolvesAppDataDb() throws Exception {
        Path appRoot = tmp.resolve("EVE PI Load Calculator");
        Path db = writeDb(appRoot, "app/data/pi-sde.db");
        String launcher = appRoot.resolve("EVE PI Load Calculator.exe").toString();

        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, launcher, tmp.resolve("irrelevant-dev-root"));
        assertTrue(r.found());
        assertEquals(ApplicationPaths.DbSource.PACKAGED_APP_IMAGE, r.source());
        assertEquals(db.toAbsolutePath().normalize(), r.database());
    }

    // ---- C. user.dir independence ----
    @Test
    void packagedResolutionIgnoresWorkingDirectory() throws Exception {
        Path appRoot = tmp.resolve("PortableApp");
        writeDb(appRoot, "app/data/pi-sde.db");
        String launcher = appRoot.resolve("App.exe").toString();
        // developmentRoot simulates a totally different working directory.
        Path otherCwd = tmp.resolve("somewhere-else");
        Files.createDirectories(otherCwd);

        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, launcher, otherCwd);
        assertTrue(r.found());
        assertEquals(ApplicationPaths.DbSource.PACKAGED_APP_IMAGE, r.source());
        // Must point at the app-image's own db, not the other cwd.
        assertTrue(r.database().toString().contains("PortableApp"));
        assertFalse(r.database().toString().contains("somewhere-else"));
    }

    @Test
    void packagedMissingDbReportsSearchedCandidates() {
        Path appRoot = tmp.resolve("EmptyApp");
        String launcher = appRoot.resolve("App.exe").toString();
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, launcher, tmp);
        assertFalse(r.found());
        assertEquals(ApplicationPaths.DbSource.PACKAGED_APP_IMAGE, r.source());
        assertNotNull(r.searched());
        assertTrue(r.searched().size() >= 1);
        // Every candidate must live under the app root, never the cwd.
        for (Path c : r.searched()) {
            assertTrue(c.toString().contains("EmptyApp"), "candidate escaped app root: " + c);
        }
    }

    // ---- D. Development fallback ----
    @Test
    void developmentFallbackFindsProjectDb() throws Exception {
        Path devRoot = tmp.resolve("project");
        Path db = writeDb(devRoot, "data/sde/pi-sde.db");
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, null, devRoot);
        assertTrue(r.found());
        assertEquals(ApplicationPaths.DbSource.DEVELOPMENT_FALLBACK, r.source());
        assertEquals(db.toAbsolutePath().normalize(), r.database());
    }

    @Test
    void developmentFallbackAlsoTriesDataRoot() throws Exception {
        Path devRoot = tmp.resolve("project");
        Path db = writeDb(devRoot, "data/pi-sde.db");
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, null, devRoot);
        assertTrue(r.found());
        assertEquals(ApplicationPaths.DbSource.DEVELOPMENT_FALLBACK, r.source());
        assertEquals(db.toAbsolutePath().normalize(), r.database());
    }

    // ---- E. Missing DB produces clear error ----
    @Test
    void missingDbEverywhereReportsNotFound() {
        Path devRoot = tmp.resolve("empty-project");
        ApplicationPaths.SdeResolution r = ApplicationPaths.resolveSdeDatabase(
                null, null, devRoot);
        assertFalse(r.found());
        assertNull(r.database());
        assertEquals(ApplicationPaths.DbSource.DEVELOPMENT_FALLBACK, r.source());
        assertFalse(r.searched().isEmpty());
    }
}
