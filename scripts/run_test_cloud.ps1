# ==============================================================================
# LOPBudge - Build and Run Maestro Cloud Tests
# ==============================================================================
# Nécessite un fichier .env à la racine avec MAESTRO_CLOUD_API_KEY
# ==============================================================================

# 1. Charger les variables d'environnement
if (Test-Path ".env") {
    Get-Content .env | ForEach-Object {
        if ($_ -match "^([^#].*?)=(.*)$") {
            [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2])
        }
    }
}

$ApiKey = [System.Environment]::GetEnvironmentVariable("MAESTRO_CLOUD_API_KEY")

if (-not $ApiKey) {
    Write-Error "Clé MAESTRO_CLOUD_API_KEY manquante dans .env ou variables d'environnement."
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

# 3. Envoi sur Maestro Cloud
Write-Host "[2/3] Upload et exécution sur Maestro Cloud via config.yaml..." -ForegroundColor Cyan

# On utilise uniquement le fichier de configuration pour les paramètres d'appareil
# Maestro lira automatiquement Maestro/config.yaml pour appId, OS, locale et model
maestro cloud --apiKey $ApiKey --config Maestro/config.yaml $ApkPath $MaestroFlows

if ($LASTEXITCODE -eq 0) {
    Write-Host "[3/3] Succès : Les tests Cloud sont terminés !" -ForegroundColor Green
} else {
    Write-Warning "[3/3] Échec ou erreurs pendant les tests Cloud."
    exit $LASTEXITCODE
}

