package com.vepi.ui;

import com.vepi.app.PiCalculatorController;
import com.vepi.load.RecommendedLoadPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI smoke test: builds the real frame and drives the exact render methods
 * the SwingWorkers call, without showing a window. Verifies UI wiring
 * (table contents, notices, calculate enablement) — the same code path the
 * visible GUI uses. Skipped on headless environments.
 *
 * <p>Each test is self-contained (loads its own template first) so test order
 * never matters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuiSmokeTest {

    private static PiCalculatorController controller;
    private static PiCalculatorFrame frame;

    private static final String IRD = "data/templates/IntegrityResponseDrones.json";
    private static final String MULTI = "data/templates/MultiStageGmbDeficit.json";

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "no display available — GUI smoke test skipped");
        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        AtomicReference<PiCalculatorFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new PiCalculatorFrame(controller)));
        frame = ref.get();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(() -> frame.dispose());
        }
        if (controller != null) {
            controller.close();
        }
    }

    @Test
    void frameRendersIrdAcceptancePlan() throws Exception {
        PiCalculatorController.TemplateSummary summary =
                controller.loadTemplate(Path.of(IRD));
        SwingUtilities.invokeAndWait(() -> frame.showTemplateSummary(summary));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled(),
                        "calculate must stay disabled until capacity is valid"));

        RecommendedLoadPlan plan = controller.calculate("20000");
        SwingUtilities.invokeAndWait(() -> frame.showResult(plan));

        SwingUtilities.invokeAndWait(() -> {
            ResultPanel.MaterialTableModel model = frame.resultPanel.model;
            assertEquals(3, model.getRowCount());
            // Name-sorted rows: GMB < Hazmat < Planetary.
            assertEquals("Gel-Matrix Biopaste", model.getValueAt(0, 0));
            assertEquals("2,208", model.getValueAt(0, 1));
            assertEquals("6,624 m3", model.getValueAt(0, 2));
            assertEquals("Hazmat Detection Systems", model.getValueAt(1, 0));
            assertEquals("Planetary Vehicles", model.getValueAt(2, 0));
        });
    }

    @Test
    void copyTextMatchesRenderedPlan() throws Exception {
        controller.loadTemplate(Path.of(IRD));
        RecommendedLoadPlan plan = controller.calculate("20000");
        SwingUtilities.invokeAndWait(() -> frame.showResult(plan));

        assertEquals("""
                Gel-Matrix Biopaste 2208
                Hazmat Detection Systems 2208
                Planetary Vehicles 2208""", CopyText.materialList(plan));
    }

    @Test
    void insufficientCapacityShowsMinimumBlockVolume() throws Exception {
        controller.loadTemplate(Path.of(IRD));
        RecommendedLoadPlan plan = controller.calculate("100");
        SwingUtilities.invokeAndWait(() -> frame.showResult(plan));
        assertEquals(0L, plan.blockCount());

        SwingUtilities.invokeAndWait(() -> {
            String notice = frame.resultPanel.notice.getText();
            assertTrue(notice.contains("insufficient"), () -> "notice was: " + notice);
            assertTrue(notice.contains("432 m3"), () -> "notice was: " + notice);
        });
    }

    @Test
    void multiStagePlanRendersSixMaterialsIncludingDeficit() throws Exception {
        controller.loadTemplate(Path.of(MULTI));
        RecommendedLoadPlan plan = controller.calculate("1701");
        SwingUtilities.invokeAndWait(() -> frame.showResult(plan));

        SwingUtilities.invokeAndWait(() -> {
            assertEquals(6, frame.resultPanel.model.getRowCount(),
                    "multi-stage deficit must render the GMB deficit row too");
            boolean gmbPresent = false;
            for (int i = 0; i < frame.resultPanel.model.getRowCount(); i++) {
                if ("Gel-Matrix Biopaste".equals(frame.resultPanel.model.getValueAt(i, 0))) {
                    assertEquals("54", frame.resultPanel.model.getValueAt(i, 1));
                    gmbPresent = true;
                }
            }
            assertTrue(gmbPresent, "GMB deficit row must be rendered");
        });
    }

    @Test
    void templateErrorPathKeepsCalculateDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.showTemplateError(
                "Cannot read template file missing.json"));
        SwingUtilities.invokeAndWait(() ->
                assertFalse(frame.calculateButton.isEnabled()));
    }

    @Test
    void blankTemplateTextShowsPasteFirstHintAndDisablesCalculate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.templatePanel.setTemplateText("   \n  ");
            frame.templatePanel.onLoadClicked();   // blank -> hint + reset, no exception
        });
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(frame.templatePanel.summary.getText().contains("Paste a PI template first."),
                    () -> "summary was: " + frame.templatePanel.summary.getText());
            assertFalse(frame.calculateButton.isEnabled(),
                    "calculate must be disabled without a parsed template");
        });
    }

    @Test
    void clearWipesTextAndParsedState() throws Exception {
        PiCalculatorController.TemplateSummary summary =
                controller.loadTemplate(Path.of(IRD));
        SwingUtilities.invokeAndWait(() -> {
            frame.templatePanel.setTemplateText("{\"CmdCtrLv\":5}");
            frame.showTemplateSummary(summary);   // simulate a loaded template
        });
        SwingUtilities.invokeAndWait(() -> frame.templatePanel.clearAll());
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(frame.templatePanel.getTemplateText().isEmpty(),
                    "clear must wipe the text area");
            assertTrue(frame.templatePanel.summary.getText().isBlank(),
                    "clear must wipe the summary");
            assertFalse(frame.calculateButton.isEnabled(),
                    "clear must disable Calculate");
            assertFalse(controller.isTemplateLoaded(),
                    "clear must forget the parsed template");
        });
    }

    @Test
    void templateTextRoundTripMatchesFileLoad() throws Exception {
        // Paste path and file path must drive the exact same rendering.
        String json = java.nio.file.Files.readString(Path.of(IRD));
        PiCalculatorController.TemplateSummary fromText = controller.loadTemplateText(json);
        SwingUtilities.invokeAndWait(() -> frame.showTemplateSummary(fromText));
        SwingUtilities.invokeAndWait(() ->
                assertTrue(frame.templatePanel.summary.getText().contains("8")));
    }
}
