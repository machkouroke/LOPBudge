# ==============================================================================
# LOPBudge - Build and Run Maestro Cloud Tests (Correction CLI)
# ==============================================================================

# 1. Charger les variables d'environnement (.env)
if (Test-Path ".env") {
    Get-Content .env | ForEach-Object {
        if ($_ -match "^([^#].*?)=(.*)$") {
            [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2])
        }
    }
}

$ApiKey = [System.Environment]::GetEnvironmentVariable("MAESTRO_CLOUD_API_KEY")
if (-not $ApiKey) {
    Write-Error "Clé MAESTRO_CLOUD_API_KEY manquante."
    exit 1
}

# 2. Build du debug APK
Write-Host "[1/3] Build de l'application (Debug)..." -ForegroundColor Cyan
if (Test-Path ".\gradlew.bat") { .\gradlew.bat assembleDebug } else { ./gradlew assembleDebug }

if ($LASTEXITCODE -ne 0) {
    Write-Error "Build échoué."
    exit $LASTEXITCODE
}

$ApkPath = "app/build/outputs/apk/debug/app-debug.apk"
$MaestroFlows = "Maestro/"
$ConfigFile = "config.yaml"

# 3. Lecture de config.yaml et construction stricte des arguments CLI
Write-Host "[2/3] Analyse de $ConfigFile..." -ForegroundColor Cyan

$ExtraArgs = @()
$HasName = $false

if (Test-Path $ConfigFile) {
    $YamlContent = Get-Content $ConfigFile

    foreach ($line in $YamlContent) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }

        if ($trimmed -match "^([a-zA-Z0-9_-]+)\s*:\s*(.*)$") {
            $key = $matches[1]
            $val = $matches[2].Trim('"', "'")

            if ($val) {
                switch ($key) {
                    "name"          { $ExtraArgs += "--name", $val; $HasName = $true }
                    "includeTags"   { $ExtraArgs += "--include-tags", $val }
                    "excludeTags"   { $ExtraArgs += "--exclude-tags", $val }
                    "device-os"     { $ExtraArgs += "--device-os", $val }
                    "device-model"  { $ExtraArgs += "--device-model", $val }
                    "device-locale" { $ExtraArgs += "--device-locale", $val }
                }
            }
        }
    }
}

# Assurances par défaut si le YAML est incomplet
if (-not ($ExtraArgs -contains "--device-os")) {
    $ExtraArgs += "--device-os", "android-36"
}
if (-not ($ExtraArgs -contains "--device-locale")) {
    $ExtraArgs += "--device-locale", "fr_FR"
}

# Nom d'exécution de secours (formaté sans espaces problématiques)
if (-not $HasName) {
    $AutoName = "LOPBudget_Run_$(Get-Date -Format 'yyyyMMdd_HHmm')"
    $ExtraArgs += "--name", $AutoName
}

# 4. Exécution sur Maestro Cloud
Write-Host "[3/3] Upload et exécution sur Maestro Cloud..." -ForegroundColor Cyan

# Affichage de débogage pour vérifier la syntaxe exacte
Write-Host "Arguments appliqués :" -ForegroundColor Gray
Write-Host ($ExtraArgs -join " ") -ForegroundColor DarkGray

maestro cloud --apiKey $ApiKey @ExtraArgs $ApkPath $MaestroFlows

if ($LASTEXITCODE -eq 0) {
    Write-Host "Succès : Exécution terminée sur Maestro Cloud !" -ForegroundColor Green
} else {
    Write-Warning "Échec lors de l'exécution sur Maestro Cloud."
    exit $LASTEXITCODE
}