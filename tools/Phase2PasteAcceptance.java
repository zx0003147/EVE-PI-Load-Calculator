import com.vepi.app.PiCalculatorController;
import com.vepi.load.RecommendedLoadPlan;
import com.vepi.load.RecommendedMaterialLoad;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 2 rev acceptance: PASTE flow (no file involved on the parse path).
 * Simulates: copy IRD template JSON -> paste -> Load Template -> 20000 m3 -> Calculate.
 */
public final class Phase2PasteAcceptance {
    public static void main(String[] args) throws Exception {
        // 1. "Copy" the real template text (as the user would from the game/notes)
        String clipboard = Files.readString(Path.of("data/templates/IntegrityResponseDrones.json"));

        try (PiCalculatorController c = new PiCalculatorController(Path.of("data/sde/pi-sde.db"))) {
            // 2. Paste-with-whitespace + Load Template (paste path, single parse(String) pipeline)
            PiCalculatorController.TemplateSummary s =
                    c.loadTemplateText("\n\n  " + clipboard + "  \n");

            System.out.println("== Template (via pasted text) ==");
            System.out.println("Template: " + s.displayName());
            System.out.println("Production facilities: " + s.facilityCount());
            for (String line : s.configurationLines()) System.out.println("  " + line);
            for (String line : s.outputLines()) System.out.println("Output: " + line);
            System.out.println("Base period: " + s.basePeriodSeconds() + " s, block volume: "
                    + s.blockVolumeM3().stripTrailingZeros().toPlainString() + " m3");

            // 3. Capacity 20000 -> Calculate
            RecommendedLoadPlan plan = c.calculate("20000");
            System.out.println();
            System.out.println("== Recommended Load (20000 m3) ==");
            for (RecommendedMaterialLoad m : plan.materials()) {
                System.out.println(m.commodity().name() + " | " + m.quantity()
                        + " | " + m.volume().stripTrailingZeros().toPlainString() + " m3");
            }
            System.out.println("Used: " + plan.usedCapacity().stripTrailingZeros().toPlainString() + " m3");
            System.out.println("Remaining: " + plan.remainingCapacity().stripTrailingZeros().toPlainString() + " m3");
            System.out.println("Runtime: " + (plan.runtimeSeconds() / 3600) + " h ("
                    + plan.runtimeSeconds() + " s)");
            System.out.println("Output: " + plan.expectedOutputs().get(0).quantity()
                    + " " + plan.expectedOutputs().get(0).commodity().name());

            // 4. Error paths (paste-specific)
            System.out.println();
            System.out.println("== Error paths ==");
            for (String bad : new String[]{"", "   ", "hello", "{abc", "{\"hello\":\"world\"}"}) {
                try {
                    c.loadTemplateText(bad);
                    System.out.println("[" + bad.replace("\n", "\\n") + "] -> NO ERROR (BUG)");
                } catch (RuntimeException e) {
                    System.out.println("[" + (bad.isEmpty() ? "(empty)" : bad) + "] -> "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }

            // 5. File wrapper consistency
            PiCalculatorController.TemplateSummary fromFile =
                    c.loadTemplate(Path.of("data/templates/IntegrityResponseDrones.json"));
            boolean same = fromFile.displayName().equals(s.displayName())
                    && fromFile.facilityCount() == s.facilityCount()
                    && fromFile.outputLines().equals(s.outputLines());
            System.out.println();
            System.out.println("Open File path == paste path: " + same);
        }
    }
}
