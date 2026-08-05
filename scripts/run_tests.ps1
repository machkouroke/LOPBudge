# ==============================================================================
# LOPBudge - Automated Build, Deploy, and Maestro Test Script (FIXED & ROBUST)
# ==============================================================================
# CONFIGURATION
# ==============================================================================
$AvdName       = "Maestro_Test_API_36"
$SysImage      = "system-images;android-36;google_apis_playstore;x86_64"
$ApkPath       = "app/build/outputs/apk/debug/app-debug.apk"
$MaestroFlows  = "Maestro/"
$BootTimeout   = 420
$EmulatorPath  = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$AvdManager    = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat"
$Serial        = "emulator-5554" # Port standard pour le premier émulateur lancé

# ==============================================================================
# 0. PRÉPARATION DE L'ÉMULATEUR
# ==============================================================================
Write-Host "[0/5] Vérification de l'AVD '$AvdName'..." -ForegroundColor Cyan

$avdList = & $AvdManager list avd
if ($avdList -notmatch "Name: $AvdName") {
    Write-Host "L'AVD '$AvdName' n'existe pas. Création..." -ForegroundColor Yellow
    echo "no" | & $AvdManager create avd -n $AvdName -k $SysImage --device "pixel_6" --force
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Échec de la création de l'AVD."
        exit $LASTEXITCODE
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
    Write-Host "[2/5] Démarrage de l'émulateur (Clean State + GPU)..." -ForegroundColor Cyan

    # On tue toute instance précédente sur ce port pour être sûr
    adb -s $Serial emu kill 2>$null | Out-Null
    Start-Sleep -Seconds 2

    # Lancement ciblé
    $emulatorProcess = Start-Process -FilePath $EmulatorPath -ArgumentList "-avd $AvdName -wipe-data -gpu host -no-snapshot -no-audio -port 5554" -PassThru -NoNewWindow

    # ==========================================================================
    # 3. ATTENTE DU DÉMARRAGE (TRIPLE CHECK CIBLÉ)
    # ==========================================================================
    Write-Host "[3/5] Attente du boot complet sur $Serial..." -ForegroundColor Cyan
    $ready = $false
    $elapsed = 0

    # On attend que ADB voit le port spécifique
    Write-Host "Recherche du device $Serial..." -ForegroundColor DarkGray
    while ($elapsed -lt 60) {
        $devices = adb devices
        if ($devices -match $Serial) { break }
        Start-Sleep -Seconds 5
        $elapsed += 5
    }

    adb -s $Serial wait-for-device
    $elapsed = 0

    while (-not $ready -and $elapsed -lt $BootTimeout) {
        Start-Sleep -Seconds 10
        $elapsed += 10

        # On cible EXCLUSIVEMENT notre émulateur pour les checks
        $bootStatus = (adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
        $pmStatus = (adb -s $Serial shell pm list packages android 2>$null)

        if ($bootStatus -eq "1" -and $pmStatus -match "package:android") {
            $ready = $true
        } else {
            Write-Host "Système en cours de chargement... ($elapsed / $BootTimeout s)" -ForegroundColor Yellow
        }
    }

    if (-not $ready) { throw "L'émulateur $Serial n'a pas répondu." }

    Write-Host "Émulateur prêt !" -ForegroundColor Green
    Start-Sleep -Seconds 5 # Buffer pour l'UI

    # ==========================================================================
    # 4. INSTALLATION CIBLÉE
    # ==========================================================================
    Write-Host "[4/5] Installation de l'APK sur $Serial..." -ForegroundColor Cyan
    adb -s $Serial install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "Échec de l'installation." }

    # ==========================================================================
    # 5. TESTS MAESTRO
    # ==========================================================================
    Write-Host "[5/5] Exécution des tests Maestro..." -ForegroundColor Cyan

    # Maestro utilisera l'émulateur actif (il est plus malin que adb pour ça,
    # mais on a préparé le terrain en ciblant le bon)
    maestro test $MaestroFlows

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Succès : Tous les tests sont passés !" -ForegroundColor Green
    } else {
        Write-Warning "Échec : Certains tests Maestro ont échoué."
        $global:ExitCode = 1
    }

} catch {
    Write-Error "Erreur : $_"
    $global:ExitCode = 1
} finally {
    # ==========================================================================
    # 6. NETTOYAGE
    # ==========================================================================
    Write-Host "Fermeture de l'émulateur $Serial..." -ForegroundColor Cyan
    adb -s $Serial emu kill 2>$null | Out-Null
    Start-Sleep -Seconds 5
    if ($emulatorProcess -and -not $emulatorProcess.HasExited) {
        Stop-Process -Id $emulatorProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Terminé." -ForegroundColor Green
    if ($global:ExitCode -ne 0) { exit $global:ExitCode }
}
