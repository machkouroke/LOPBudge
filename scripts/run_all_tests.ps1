# ==============================================================================
# LOPBudge - Unified Test Suite (JUnit + Maestro)
# ==============================================================================

$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$global:ExitCode = 0

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "🚀 DÉMARRAGE DE LA SUITE DE TESTS COMPLÈTE" -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. EXÉCUTION DES TESTS UNITAIRES (JUnit)
# ==============================================================================
Write-Host "[1/2] Exécution des tests unitaires JUnit..." -ForegroundColor Yellow
if (Test-Path ".\gradlew.bat") { .\gradlew.bat testDebugUnitTest } else { ./gradlew testDebugUnitTest }

if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ ÉCHEC : Les tests unitaires ont échoué."
    $global:ExitCode = 1
} else {
    Write-Host "✅ SUCCÈS : Tests unitaires terminés avec succès." -ForegroundColor Green
}

# 2. EXÉCUTION DES TESTS UI (Maestro)
# ==============================================================================
Write-Host "`n[2/2] Exécution des tests UI Maestro..." -ForegroundColor Yellow
if (Test-Path ".\scripts\run_tests.ps1") {
    & .\scripts\run_tests.ps1
} else {
    Write-Error "❌ Script .\scripts\run_tests.ps1 introuvable."
    $global:ExitCode = 1
}

if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ ÉCHEC : Les tests Maestro ont échoué."
    $global:ExitCode = 1
}

# FINALISATION
# ==============================================================================
$Stopwatch.Stop()
$ElapsedTime = $Stopwatch.Elapsed
$TimeDisplay = [string]::Format("{0:00} min {1:00} sec", $ElapsedTime.TotalMinutes, $ElapsedTime.Seconds)

Write-Host "`n====================================================" -ForegroundColor Cyan
if ($global:ExitCode -eq 0) {
    Write-Host "🏁 BILAN FINAL : TOUS LES TESTS SONT AU VERT ! ✨" -ForegroundColor Green
} else {
    Write-Host "🏁 BILAN FINAL : ÉCHEC DE LA SUITE DE TESTS. ❌" -ForegroundColor Red
}
Write-Host "Temps total : $TimeDisplay" -ForegroundColor Magenta
Write-Host "====================================================" -ForegroundColor Cyan

exit $global:ExitCode
