#!/usr/bin/env bash
# Build script (plain JDK, no Gradle/Kotlin needed). Phase 0 + Phase 1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"          # use relative paths so the Windows javac resolves them correctly

OUT="out"
MAIN_CLS="out/classes"
TEST_CLS="out/test-classes"
CP="out/classes;out/test-classes;lib/*"   # ';' = Windows classpath separator

mkdir -p "$MAIN_CLS" "$TEST_CLS"

echo ">> Compiling main sources..."
mapfile -t MAIN_SRCS < <(find src/main/java -name '*.java')
javac -d "$MAIN_CLS" -cp "lib/*" "${MAIN_SRCS[@]}"

echo ">> Compiling test sources..."
mapfile -t TEST_SRCS < <(find src/test/java -name '*.java')
javac -d "$TEST_CLS" -cp "out/classes;lib/*" "${TEST_SRCS[@]}"

echo ">> Running tests (JUnit 5)..."
# JVM -cp expands the lib/* wildcard; junit --class-path lists real dirs only.
# Explicit --select-class is more reliable than --scan-class-path here.
java -cp "lib/*" org.junit.platform.console.ConsoleLauncher \
  --class-path "out/classes;out/test-classes" \
  --select-class "com.vepi.calc.ProductionCalculatorTest" \
  --select-class "com.vepi.calc.AcceptanceTest" \
  --select-class "com.vepi.calc.MultiStageAcceptanceTest" \
  --select-class "com.vepi.sde.SdeRepositoryTest" \
  --select-class "com.vepi.template.TemplateParserTest" \
  --select-class "com.vepi.balance.ProductionBlockCalculatorTest" \
  --select-class "com.vepi.load.LoadOptimizerTest" \
  --select-class "com.vepi.sde.PiTierResolverTest" \
  --select-class "com.vepi.inventory.InventoryTextParserTest" \
  --select-class "com.vepi.flow.FractionTest" \
  --select-class "com.vepi.flow.ProductionFlowSolverTest" \
  --select-class "com.vepi.allocation.MultiPlanetAllocationPlannerTest" \
  --select-class "com.vepi.balancing.InventoryBalanceCalculatorTest" \
  --select-class "com.vepi.balancing.P4BalanceRecipeResolverTest" \
  --select-class "com.vepi.app.PiCalculatorControllerTest" \
  --select-class "com.vepi.app.PiCalculatorControllerAllocationTest" \
  --select-class "com.vepi.app.BalanceInventoryControllerTest" \
  --select-class "com.vepi.app.ApplicationPathsTest" \
  --select-class "com.vepi.ui.FormatsTest" \
  --select-class "com.vepi.ui.CollapsibleSectionTest" \
  --select-class "com.vepi.ui.CopyTextTest" \
  --select-class "com.vepi.ui.CopyShoppingListFormatterTest" \
  --select-class "com.vepi.ui.AllocationReadinessTest" \
  --select-class "com.vepi.ui.BalanceInventoryPanelStateTest" \
  --select-class "com.vepi.ui.MouseWheelForwarderTest" \
  --select-class "com.vepi.ui.UiComponentsTest" \
  --select-class "com.vepi.ui.GuiSmokeTest" \
  --select-class "com.vepi.ui.AllocationFrameSmokeTest" \
  --select-class "com.vepi.ui.AppFrameSmokeTest" \
  --disable-banner \
  --details=tree
