# EVE PI Load Calculator - portable Windows release build.
#
# Produces dist\EVE-PI-Load-Calculator-v<version>-win-x64.zip containing a
# self-contained jpackage app-image (bundled Java runtime, no installer).
#
# Steps: safe clean -> full test suite -> production jar -> staging ->
# jpackage app-image -> README -> smoke tests (independent dir + cwd) ->
# ZIP -> extract-and-launch verification -> SHA-256 -> summary.
#
# Any failing step aborts the release with a non-zero exit code.

#requires -Version 5.1
# ErrorActionPreference stays at Continue because Windows PowerShell 5.1 turns
# native-command stderr output (e.g. JVM warnings) into terminating errors
# under Stop. Every critical step is guarded by an explicit exit-code or
# existence check instead.
$ErrorActionPreference = "Continue"
Set-StrictMode -Version Latest

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$AppName     = "EVE PI Load Calculator"
$MainClass   = "com.vepi.app.DesktopMain"
$VersionFile = Join-Path $ProjectRoot "VERSION"
$DistDir     = Join-Path $ProjectRoot "dist"
$AppImageDir = Join-Path $DistDir  "app-image"
$PackageInput = Join-Path $ProjectRoot "out\package-input"
$ExtractTestDir = Join-Path $ProjectRoot "out\portable-extract-test"
$WindowProbe = Join-Path $ProjectRoot "tools\window_probe.py"

function Fail([string]$Step, [string]$Message) {
    Write-Host ""
    Write-Host "RELEASE FAILED at step: $Step" -ForegroundColor Red
    Write-Host "  $Message" -ForegroundColor Red
    exit 1
}

# --- 1. read single version source -------------------------------------------
if (-not (Test-Path $VersionFile)) { Fail "version" "VERSION file missing" }
$Version = (Get-Content $VersionFile -Raw).Trim()
if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "version" "invalid version '$Version'" }
Write-Host "== EVE PI Load Calculator portable release v$Version =="

# Stop leftover instances of our own app so later file copies never hit a
# lock (exact image name only; unrelated processes are never touched).
Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

# --- 2. safe clean of OUR OWN release dirs only ------------------------------
foreach ($dir in @($DistDir, $PackageInput, $ExtractTestDir)) {
    $full = [System.IO.Path]::GetFullPath($dir)
    if (-not $full.StartsWith($ProjectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Fail "clean" "refusing to clean outside project root: $full"
    }
    if (Test-Path $full) {
        Remove-Item -Recurse -Force -Confirm:$false $full
        if (Test-Path $full) { Fail "clean" "could not remove $full (file locked?)" }
    }
}

# --- 3. full regression suite ------------------------------------------------
# Executed natively on Windows with the SAME inputs as build.sh: the test
# class list is parsed out of build.sh so that build.sh stays the single
# source of truth for what constitutes the full regression suite.
Write-Host ">> [1/9] Running full test suite..."
New-Item -ItemType Directory -Force "out\classes", "out\test-classes" | Out-Null

$mainSrcs = @(Get-ChildItem (Join-Path $ProjectRoot "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
& javac -d "out\classes" -cp "lib/*" $mainSrcs 2>&1 | Out-File "out\release-main-compile.log" -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    Get-Content "out\release-main-compile.log" | Select-Object -First 20 | Write-Host
    Fail "tests" "main compilation failed"
}

$testSrcs = @(Get-ChildItem (Join-Path $ProjectRoot "src\test\java") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
& javac -d "out\test-classes" -cp "out/classes;lib/*" $testSrcs 2>&1 | Out-File "out\release-test-compile.log" -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    Get-Content "out\release-test-compile.log" | Select-Object -First 20 | Write-Host
    Fail "tests" "test compilation failed"
}

$selectClasses = @(Select-String -Path (Join-Path $ProjectRoot "build.sh") `
        -Pattern '--select-class\s+"([^"]+)"' |
    ForEach-Object { $_.Matches[0].Groups[1].Value })
if ($selectClasses.Count -le 0) { Fail "tests" "no --select-class entries found in build.sh" }
$selectArgs = foreach ($c in $selectClasses) { "--select-class"; $c }

$testLog = Join-Path $ProjectRoot "out\release-tests.log"
& java -cp "lib/*" org.junit.platform.console.ConsoleLauncher `
    --class-path "out/classes;out/test-classes" `
    @selectArgs `
    --disable-banner --details=summary 2>&1 | Out-File -FilePath $testLog -Encoding utf8
if ($LASTEXITCODE -ne 0) { Fail "tests" "ConsoleLauncher exited with $LASTEXITCODE" }
$logText = Get-Content $testLog -Raw
$found = [regex]::Match($logText, '\[\s*(\d+) tests found').Groups[1].Value
$passed = [regex]::Match($logText, '\[\s*(\d+) tests successful').Groups[1].Value
$failed = [regex]::Match($logText, '\[\s*(\d+) tests failed').Groups[1].Value
Write-Host "   tests found=$found passed=$passed failed=$failed"
if ([int]$found -le 0) { Fail "tests" "no tests were executed" }
if ([int]$failed -ne 0 -or [int]$passed -ne [int]$found) {
    Fail "tests" "regression not green: found=$found passed=$passed failed=$failed"
}

# --- 4. production jar + staging ---------------------------------------------
Write-Host ">> [2/9] Building production JAR and staging input..."
New-Item -ItemType Directory -Force (Join-Path $PackageInput "data") | Out-Null
& jar --create --file (Join-Path $PackageInput "eve-pi-load-calculator.jar") -C "out\classes" .
if ($LASTEXITCODE -ne 0) { Fail "jar" "jar creation failed" }
Copy-Item (Join-Path $ProjectRoot "lib\gson.jar")        $PackageInput -Force
Copy-Item (Join-Path $ProjectRoot "lib\sqlite-jdbc.jar") $PackageInput -Force
Copy-Item (Join-Path $ProjectRoot "data\sde\pi-sde.db")  (Join-Path $PackageInput "data\pi-sde.db") -Force
foreach ($f in @("eve-pi-load-calculator.jar", "gson.jar", "sqlite-jdbc.jar", "data\pi-sde.db")) {
    if (-not (Test-Path (Join-Path $PackageInput $f))) { Fail "staging" "missing staged file $f" }
}
# Guard: no test-only artifacts may enter the package input.
if (Get-ChildItem $PackageInput -Filter "*junit*" -Recurse) { Fail "staging" "JUnit jar must not be packaged" }

# --- 5. jpackage app-image ----------------------------------------------------
Write-Host ">> [3/9] Running jpackage --type app-image..."
& jpackage --type app-image `
    --name $AppName `
    --input $PackageInput `
    --main-jar "eve-pi-load-calculator.jar" `
    --main-class $MainClass `
    --app-version $Version `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --dest $AppImageDir
if ($LASTEXITCODE -ne 0) { Fail "jpackage" "exit code $LASTEXITCODE" }

# --- 6. README -----------------------------------------------------------------
$appRoot = Join-Path $AppImageDir $AppName
$exe = Join-Path $appRoot "$AppName.exe"
Copy-Item (Join-Path $ProjectRoot "packaging\README.txt") (Join-Path $appRoot "README.txt") -Force

# --- 7. smoke test the app-image ----------------------------------------------
Write-Host ">> [4/9] Smoke testing app-image..."
if (-not (Test-Path $exe))          { Fail "smoke" "launcher exe missing: $exe" }
if (-not (Test-Path (Join-Path $appRoot "runtime")))      { Fail "smoke" "bundled runtime missing" }
if (-not (Test-Path (Join-Path $appRoot "app\data\pi-sde.db"))) { Fail "smoke" "bundled pi-sde.db missing" }

# Launch from an independent directory with a different working directory,
# then verify the main window is visible (not a small error dialog).
$proc = Start-Process -FilePath $exe -WorkingDirectory "C:\" -PassThru
Start-Sleep -Seconds 12
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py) {
    & python $WindowProbe --name "$AppName.exe" --min-width 1000
    if ($LASTEXITCODE -ne 0) {
        Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force
        Fail "smoke" "window probe failed after launching from C:\ (exit $LASTEXITCODE)"
    }
} else {
    if ($proc.HasExited) {
        Fail "smoke" "launcher exited immediately (python unavailable for deeper check)"
    }
    Write-Host "   python not found; used process-alive check only"
}
Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1
Write-Host "   app-image smoke PASS"

# --- 8. create ZIP with a single top-level folder -------------------------------
Write-Host ">> [5/9] Creating ZIP..."
$ProgressPreference = "SilentlyContinue"   # quiet + much faster for big archives
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression   # ZipArchiveMode lives here on .NET Framework
$zipTop = Join-Path $DistDir ("EVE-PI-Load-Calculator-v{0}" -f $Version)
Copy-Item $appRoot $zipTop -Recurse
$ZipPath = Join-Path $DistDir ("EVE-PI-Load-Calculator-v{0}-win-x64.zip" -f $Version)
if (Test-Path $ZipPath) { Remove-Item -Force -Confirm:$false $ZipPath }
# Build the archive entry-by-entry with forward slashes: ZipFile::CreateFromDirectory
# on .NET Framework writes '\' separators, which non-Windows unzip tools treat
# as part of the file name.
$topName = "EVE-PI-Load-Calculator-v{0}" -f $Version
$zip = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $zipTop -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($zipTop.Length).TrimStart('\', '/').Replace('\', '/')
        $entryName = "{0}/{1}" -f $topName, $relative
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $_.FullName, $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Remove-Item -Recurse -Force -Confirm:$false $zipTop
if (-not (Test-Path $ZipPath)) { Fail "zip" "zip was not created" }

# --- 9. extract-and-launch verification ------------------------------------------
Write-Host ">> [6/9] Extracting ZIP into clean dir and re-launching..."
Expand-Archive -Path $ZipPath -DestinationPath $ExtractTestDir -Force
$extractedExe = Join-Path $ExtractTestDir ("EVE-PI-Load-Calculator-v{0}\{1}.exe" -f $Version, $AppName)
if (-not (Test-Path $extractedExe)) { Fail "extract-test" "extracted launcher missing: $extractedExe" }
$proc2 = Start-Process -FilePath $extractedExe -WorkingDirectory $env:USERPROFILE -PassThru
Start-Sleep -Seconds 12
if ($py) {
    & python $WindowProbe --name "$AppName.exe" --min-width 1000
    if ($LASTEXITCODE -ne 0) {
        Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force
        Fail "extract-test" "window probe failed on extracted copy (exit $LASTEXITCODE)"
    }
} else {
    if ($proc2.HasExited) { Fail "extract-test" "extracted launcher exited immediately" }
}
Get-Process -Name $AppName -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1
Write-Host "   extracted-copy smoke PASS"

# --- 10. SHA-256 + summary --------------------------------------------------------
Write-Host ">> [7/9] Computing SHA-256..."
$hash = (Get-FileHash $ZipPath -Algorithm SHA256).Hash.ToLower()
$zipSizeMB = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
$imageSizeMB = [math]::Round(((Get-ChildItem $appRoot -Recurse | Measure-Object Length -Sum).Sum) / 1MB, 1)

Write-Host ">> [8/9] Verifying ZIP contents are clean..."
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
    $bad = $zip.Entries | Where-Object {
        $_.FullName -match '(^|/)(src|test|tools|out|screenshots)(/|$)' -or
        $_.Name -match 'junit|\.log$|\.png$|\.gz$'
    }
    if ($bad) {
        $bad | ForEach-Object { Write-Host "   UNEXPECTED ENTRY: $($_.FullName)" }
        Fail "zip-content" "development files leaked into the ZIP"
    }
    $names = $zip.Entries | ForEach-Object { $_.FullName } |
        Where-Object { $_ -match '^[^/]+/' } |
        ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique
    Write-Host ("   top-level entries in ZIP: " + ($names -join ", "))
    if (@($names).Count -ne 1) { Fail "zip-content" "ZIP must contain exactly one top-level directory" }
} finally {
    $zip.Dispose()
}

Write-Host ""
Write-Host ">> [9/9] RELEASE SUMMARY" -ForegroundColor Green
Write-Host "    version       : $Version"
Write-Host "    tests         : found=$found passed=$passed failed=$failed"
Write-Host "    app-image dir : $appRoot ($imageSizeMB MB)"
Write-Host "    zip           : $ZipPath"
Write-Host "    zip size      : $zipSizeMB MB"
Write-Host "    sha256        : $hash"
Write-Host ""
Write-Host "PORTABLE RELEASE OK"
