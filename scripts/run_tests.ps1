# ==============================================================================
# LOPBudge - Automated Build, Deploy, and Maestro Test Script (WITH REPORTING)
# ==============================================================================
# CONFIGURATION
# ==============================================================================
$AvdName       = "Maestro_Test_API_36"
$SysImage      = "system-images;android-36;google_apis_playstore;x86_64"
$ApkPath       = "app/build/outputs/apk/debug/app-debug.apk"
$MaestroFlows  = "Maestro/"
$BootTimeout   = 240 # Augmenté légèrement pour la stabilité
$EmulatorPath  = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$AvdManager    = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat"
$Serial        = "emulator-5554"
$PackageName   = "com.lop.budget"
$OutputDir     = "maestro_results"

# ==============================================================================
# 0. PRÉPARATION DE L'ÉMULATEUR
# ==============================================================================
Write-Host "[0/5] Vérification de l'AVD '$AvdName'..." -ForegroundColor Cyan

$avdList = & $AvdManager list avd
if ($avdList -notmatch "Name: $AvdName") {
    Write-Host "Création de l'AVD '$AvdName' (Pixel 6)..." -ForegroundColor Yellow
    echo "no" | & $AvdManager create avd -n $AvdName -k $SysImage --device "pixel_6" --force

    $configPath = "$HOME\.android\avd\$AvdName.avd\config.ini"
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
if ($LASTEXITCODE -ne 0) { Write-Error "Build échoué."; exit $LASTEXITCODE }

$emulatorProcess = $null
$global:ExitCode = 0

try {
    # ==========================================================================
    # 2. DÉMARRAGE DE L'ÉMULATEUR
    # ==========================================================================
    Write-Host "[2/5] Démarrage de l'émulateur (Quick Boot + GPU)..." -ForegroundColor Cyan

    $devices = adb devices
    if ($devices -notmatch $Serial) {
        $emulatorProcess = Start-Process -FilePath $EmulatorPath -ArgumentList "-avd $AvdName -gpu host -no-snapshot-save -no-audio -port 5554" -PassThru -NoNewWindow
    }

    # ==========================================================================
    # 3. ATTENTE DU DÉMARRAGE
    # ==========================================================================
    Write-Host "[3/5] Attente du signal Android sur $Serial..." -ForegroundColor Cyan
    $ready = $false
    $elapsed = 0

    adb -s $Serial wait-for-device
    while (-not $ready -and $elapsed -lt $BootTimeout) {
        Start-Sleep -Seconds 5
        $elapsed += 5
        $bootStatus = (adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
        if ($bootStatus -eq "1") { $ready = $true }
        else { Write-Host "Démarrage en cours... ($elapsed s)" -ForegroundColor Yellow }
    }

    # ==========================================================================
    # 4. INSTALLATION ET RESET DATA
    # ==========================================================================
    Write-Host "[4/5] Installation et réinitialisation des données..." -ForegroundColor Cyan

    # On vide le répertoire de résultats avant de commencer
    if (Test-Path $OutputDir) { Remove-Item "$OutputDir\*" -Recurse -Force }
    else { New-Item -ItemType Directory -Path $OutputDir }

    adb -s $Serial shell pm clear $PackageName 2>$null
    adb -s $Serial install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "Échec de l'installation." }

    # ==========================================================================
    # 5. TESTS MAESTRO (AVEC CAPTURES D'ÉCRAN & RAPPORTS)
    # ==========================================================================
    Write-Host "[5/5] Exécution des tests Maestro (Rapports activés)..." -ForegroundColor Cyan

    # Maestro génère maintenant un rapport HTML détaillé et des captures en cas d'erreur
    # --format HTML-DETAILED : Génère un rapport avec les étapes détaillées
    # --output : Définit le fichier du rapport
    # --test-output-dir : Définit où stocker les captures d'écran/logs de debug
    maestro --device $Serial test `
        --format HTML-DETAILED `
        --output "$OutputDir/report.html" `
        --test-output-dir $OutputDir `
        $MaestroFlows

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Succès : Tous les tests sont passés !" -ForegroundColor Green
    } else {
        Write-Warning "Échec : Certains tests Maestro ont échoué. Consultez le rapport dans '$OutputDir/report.html'."
        $global:ExitCode = 1
    }

} catch {
    Write-Error "Erreur : $_"
    $global:ExitCode = 1
} finally {
    # ==========================================================================
    # 6. NETTOYAGE
    # ==========================================================================
    Write-Host "Fermeture de l'émulateur..." -ForegroundColor Cyan
    adb -s $Serial emu kill 2>$null | Out-Null
    Start-Sleep -Seconds 3
    if ($emulatorProcess -and -not $emulatorProcess.HasExited) {
        Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Terminé." -ForegroundColor Green
    if ($global:ExitCode -ne 0) { exit $global:ExitCode }
}
