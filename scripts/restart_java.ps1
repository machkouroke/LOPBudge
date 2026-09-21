# Permet de rédémarrer java en cas de connexion non établi
Get-Process java -ErrorAction SilentlyContinue | Select-Object Id, ProcessName
Stop-Process -Name java -Force -ErrorAction SilentlyContinue