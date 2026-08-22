package com.vepi.app;

import com.vepi.load.LoadException;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controller tests: same pipeline as the GUI, no Swing involved.
 * Real IRD template + real SDE must reproduce the Phase 1 acceptance numbers.
 */
class PiCalculatorControllerTest {

    private static PiCalculatorController controller;
    private static String irdJson;

    @BeforeAll
    static void setUp() throws Exception {
        controller = new PiCalculatorController(Path.of("data/sde/pi-sde.db"));
        irdJson = java.nio.file.Files.readString(
                Path.of("data/templates/IntegrityResponseDrones.json"));
    }

    @AfterAll
    static void tearDown() {
        controller.close();
    }

    @Test
    void realIrdTemplate_calculatesPhase1AcceptancePlan() {
        PiCalculatorController.TemplateSummary summary =
                controller.loadTemplate(Path.of("data/templates/IntegrityResponseDrones.json"));

        assertEquals(8, summary.facilityCount());
        assertTrue(summary.displayName().contains("Integrity Response Drones"));
        assertEquals(3600L, summary.basePeriodSeconds());
        assertEquals(0, summary.blockVolumeM3().compareTo(new BigDecimal("432")));
        assertEquals(1, summary.configurationLines().size(), "one production configuration");
        assertTrue(summary.configurationLines().get(0).startsWith("8 x "));
        assertEquals(1, summary.outputLines().size());
        assertTrue(summary.outputLines().get(0).startsWith("Integrity Response Drones x8"));

        assertTrue(controller.isTemplateLoaded());
        assertEquals(new BigDecimal("432"),
                controller.blockVolume().orElseThrow().stripTrailingZeros());

        RecommendedLoadPlan plan = controller.calculate("20000");

        assertEquals(46L, plan.blockCount());
        assertEquals(165600L, plan.runtimeSeconds());
        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("19872")));
        assertEquals(0, plan.remainingCapacity().compareTo(new BigDecimal("128")));

        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(3, byType.size());
        assertEquals(2208L, byType.get(2348L).quantity());
        assertEquals(2208L, byType.get(2366L).quantity());
        assertEquals(2208L, byType.get(9846L).quantity());
        assertEquals(0, byType.get(2348L).volume().compareTo(new BigDecimal("6624")));

        assertEquals(1, plan.expectedOutputs().size());
        assertEquals(2868L, plan.expectedOutputs().get(0).commodity().typeId());
        assertEquals(368L, plan.expectedOutputs().get(0).quantity());
    }

    @Test
    void multiStageDeficitTemplate_showsIntermediateAsExternalInput() {
        controller.loadTemplate(Path.of("data/templates/MultiStageGmbDeficit.json"));

        RecommendedLoadPlan plan = controller.calculate("1701");

        assertEquals(3L, plan.blockCount());
        Map<Long, RecommendedMaterialLoad> byType = plan.materials().stream()
                .collect(Collectors.toMap(RecommendedMaterialLoad::typeId, m -> m));
        assertEquals(6, byType.size());
        assertEquals(54L, byType.get(2348L).quantity(),
                "GMB deficit (18/block x 3 blocks) is an external input");
        assertEquals(24L, plan.expectedOutputs().get(0).quantity());
    }

    @Test
    void zeroCapacity_isLegalAndYieldsZeroPlan() {
        controller.loadTemplate(Path.of("data/templates/IntegrityResponseDrones.json"));
        RecommendedLoadPlan plan = controller.calculate("0");
        assertEquals(0L, plan.blockCount());
        assertEquals(0L, plan.runtimeSeconds());
        assertTrue(plan.materials().stream().allMatch(m -> m.quantity() == 0));
    }

    @Test
    void invalidCapacityText_rejectedByValidator() {
        assertTrue(PiCalculatorController.validateCapacity("abc").isPresent());
        assertTrue(PiCalculatorController.validateCapacity("-1").isPresent());
        assertTrue(PiCalculatorController.validateCapacity("").isPresent());
        assertTrue(PiCalculatorController.validateCapacity("  ").isPresent());
        assertTrue(PiCalculatorController.validateCapacity("1e999999999").isPresent());
        assertTrue(PiCalculatorController.validateCapacity("20000.5").isEmpty());
        assertTrue(PiCalculatorController.validateCapacity("0").isEmpty());

        controller.loadTemplate(Path.of("data/templates/IntegrityResponseDrones.json"));
        assertThrows(LoadException.InvalidCapacity.class, () -> controller.calculate("abc"));
        assertThrows(LoadException.InvalidCapacity.class, () -> controller.calculate("-1"));
    }

    @Test
    void templateWithoutFacilities_isUnsupported() {
        Path noProducers = Path.of("src/test/resources/no-producers.json");
        assertThrows(RuntimeException.class, () -> controller.loadTemplate(noProducers));
    }

    @Test
    void missingTemplateFile_reportsReadableError() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> controller.loadTemplate(Path.of("data/templates/does-not-exist.json")));
        assertFalse(e.getMessage() == null || e.getMessage().isBlank(),
                "error must carry a diagnosable message");
    }

    // ---- pasted-template-text tests (Phase 2 rev: paste is the primary input) ----

    @Test
    void loadTemplateText_realIrdJson() {
        // A: pasted text, no file involved
        PiCalculatorController.TemplateSummary summary = controller.loadTemplateText(irdJson);
        assertEquals(8, summary.facilityCount(), "8 production facilities");
        assertTrue(summary.displayName().contains("Integrity Response Drones"));
        assertEquals(1, summary.outputLines().size());
        assertTrue(summary.outputLines().get(0).startsWith("Integrity Response Drones x8"));
        assertTrue(controller.isTemplateLoaded());

        // the pasted path must reproduce the Phase 1 acceptance plan too
        RecommendedLoadPlan plan = controller.calculate("20000");
        assertEquals(46L, plan.blockCount());
        assertEquals(0, plan.usedCapacity().compareTo(new BigDecimal("19872")));
        assertEquals(368L, plan.expectedOutputs().get(0).quantity());
    }

    @Test
    void loadTemplateText_leadingAndTrailingWhitespace() {
        // B: surrounding whitespace is tolerated
        PiCalculatorController.TemplateSummary summary =
                controller.loadTemplateText("\n\n  " + irdJson + "  \n");
        assertEquals(8, summary.facilityCount());
        assertEquals(0, summary.blockVolumeM3().compareTo(new BigDecimal("432")));
    }

    @Test
    void loadTemplateText_emptyOrBlankIsValidationError() {
        // C: empty / whitespace-only text is a clear validation error, no popup
        for (String blank : new String[]{"", "   ", "\n\n"}) {
            assertThrows(com.vepi.template.TemplateException.InvalidTemplate.class,
                    () -> controller.loadTemplateText(blank),
                    "blank text must be rejected: [" + blank + "]");
        }
    }

    @Test
    void loadTemplateText_invalidJsonThrowsInvalidTemplate() {
        // D: not valid JSON
        assertThrows(com.vepi.template.TemplateException.InvalidTemplate.class,
                () -> controller.loadTemplateText("hello"));
        assertThrows(com.vepi.template.TemplateException.InvalidTemplate.class,
                () -> controller.loadTemplateText("{abc"));
    }

    @Test
    void loadTemplateText_validJsonButNotPiTemplateFails() {
        // E: valid JSON that is not a PI template must fail
        assertThrows(com.vepi.template.TemplateException.class,
                () -> controller.loadTemplateText("{\"hello\":\"world\"}"));
    }

    @Test
    void loadTemplateFile_matchesPastedTextResult() {
        // F: file wrapper and pasted text share one pipeline — same results
        PiCalculatorController.TemplateSummary fromFile =
                controller.loadTemplate(Path.of("data/templates/IntegrityResponseDrones.json"));
        PiCalculatorController.TemplateSummary fromText = controller.loadTemplateText(irdJson);
        assertEquals(fromFile.displayName(), fromText.displayName());
        assertEquals(fromFile.facilityCount(), fromText.facilityCount());
        assertEquals(fromFile.configurationLines(), fromText.configurationLines());
        assertEquals(fromFile.outputLines(), fromText.outputLines());
        assertEquals(fromFile.basePeriodSeconds(), fromText.basePeriodSeconds());
        assertEquals(0, fromFile.blockVolumeM3().compareTo(fromText.blockVolumeM3()));
    }

    @Test
    void clearTemplate_forgetsState() {
        controller.loadTemplateText(irdJson);
        assertTrue(controller.isTemplateLoaded());
        controller.clearTemplate();
        assertFalse(controller.isTemplateLoaded());
        assertThrows(IllegalStateException.class, () -> controller.calculate("20000"));
    }
}
