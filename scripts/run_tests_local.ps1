# ==============================================================================
# LOPBudge - Automated Build, Deploy, and Maestro Test Script
# ==============================================================================
# PARAMÈTRES
# ==============================================================================
param (
    [switch]$Close # Option pour fermer l'émulateur à la fin
)

# ==============================================================================
# CONFIGURATION
# ==============================================================================
$AvdName       = "Maestro_Test_API_36"
$SysImage      = "system-images;android-36;google_apis_playstore;x86_64"
$ApkPath       = "app/build/outputs/apk/debug/app-debug.apk"
$MaestroFlows  = "Maestro/"
$BootTimeout   = 180
$EmulatorPath  = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$AvdManager    = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat"
$Serial        = "emulator-5554"
$PackageName   = "com.lop.budget"
$OutputDir     = "maestro_results"
$AvdDir        = "$HOME\.android\avd\$AvdName.avd"

# --- HELPER : Chronomètre ---
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

# ==============================================================================
# 0. PRÉPARATION DE L'AVD (Si manquant)
# ==============================================================================
$avdList = & $AvdManager list avd
if ($avdList -notmatch "Name: $AvdName") {
    Write-Host "[0/5] Création de l'AVD '$AvdName'..." -ForegroundColor Cyan
    echo "no" | & $AvdManager create avd -n $AvdName -k $SysImage --device "pixel_6" --force
    $configPath = "$AvdDir\config.ini"
    if (Test-Path $configPath) {
        $config = Get-Content $configPath
        $config = $config -replace "hw.ramSize=.*", "hw.ramSize=3072"
        $config = $config -replace "vm.heapSize=.*", "vm.heapSize=512"
        $config += "`nhw.gpu.enabled=yes`nhw.gpu.mode=host"
        Set-Content $configPath $config
    }
}

# ==============================================================================
# 1. BUILD DU PROJET
# ==============================================================================
Write-Host "[1/5] Build de l'application..." -ForegroundColor Cyan
if (Test-Path ".\gradlew.bat") { .\gradlew.bat assembleDebug } else { ./gradlew assembleDebug }
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build échoué."
    $Stopwatch.Stop()
    exit $LASTEXITCODE
}

$global:ExitCode = 0

try {
    # ==========================================================================
    # 2. GESTION INTELLIGENTE DE L'ÉMULATEUR
    # ==========================================================================
    $devices = adb devices
    if ($devices -match $Serial) {
        Write-Host "[2/5] L'émulateur est déjà actif. Utilisation du mode INSTANT-RELOAD." -ForegroundColor Green
    } else {
        Write-Host "[2/5] Démarrage de l'émulateur (Ceci ne sera fait qu'une fois)..." -ForegroundColor Cyan

        if (Test-Path $AvdDir) { Get-ChildItem -Path $AvdDir -Filter "*.lock" -Recurse | Remove-Item -Force -ErrorAction SilentlyContinue }

        Start-Process -FilePath $EmulatorPath -ArgumentList "-avd $AvdName -gpu host -no-snapshot-save -no-audio -port 5554" -NoNewWindow

        Write-Host "[3/5] Attente du boot Android..." -ForegroundColor Cyan
        adb -s $Serial wait-for-device
        $ready = $false
        $elapsed = 0
        while (-not $ready -and $elapsed -lt $BootTimeout) {
            Start-Sleep -Seconds 5
            $elapsed += 5
            if ((adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim() -eq "1") { $ready = $true }
        }
    }

    # ==========================================================================
    # 4. INSTALLATION ET CLEAN STATE EXPRESS
    # ==========================================================================
    Write-Host "[4/5] Réinitialisation et installation sur $Serial..." -ForegroundColor Cyan

    if (Test-Path $OutputDir) { Remove-Item "$OutputDir\*" -Recurse -Force }
    else { New-Item -ItemType Directory -Path $OutputDir }

    adb -s $Serial shell pm clear $PackageName 2>$null
    adb -s $Serial install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "Échec de l'installation." }

    # ==========================================================================
    # 5. TESTS MAESTRO
    # ==========================================================================
    Write-Host "[5/5] Exécution des tests Maestro..." -ForegroundColor Cyan
    maestro --device $Serial test --format HTML-DETAILED --output "$OutputDir/report.html" --test-output-dir $OutputDir $MaestroFlows

    if ($LASTEXITCODE -eq 0) { Write-Host "Succès : Tous les tests sont passés !" -ForegroundColor Green }
    else { Write-Warning "Échec : Consultez '$OutputDir/report.html'." ; $global:ExitCode = 1 }

} catch {
    Write-Error "Erreur : $_"
    $global:ExitCode = 1
} finally {
    # ==========================================================================
    # 6. NETTOYAGE / FERMETURE OPTIONNELLE
    # ==========================================================================
    if ($Close) {
        Write-Host "Fermeture demandée de l'émulateur..." -ForegroundColor Cyan
        adb -s $Serial emu kill 2>$null | Out-Null
        Start-Sleep -Seconds 3
        Get-Process qemu-system-x86_64, emulator -ErrorAction SilentlyContinue | Stop-Process -Force
        Write-Host "Émulateur fermé." -ForegroundColor DarkGray
    } else {
        Write-Host "L'émulateur reste ouvert (utiliser -Close pour le fermer)." -ForegroundColor Yellow
    }

    $Stopwatch.Stop()
    $ElapsedTime = $Stopwatch.Elapsed
    $TimeDisplay = [string]::Format("{0:00} min {1:00} sec", $ElapsedTime.TotalMinutes, $ElapsedTime.Seconds)

    Write-Host "--- TEST TERMINÉ ---" -ForegroundColor Cyan
    Write-Host "Temps total d'exécution : $TimeDisplay" -ForegroundColor Magenta

    if ($global:ExitCode -ne 0) { exit $global:ExitCode }
}
