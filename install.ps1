param([string]$Tiles='E135_N35')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if (Test-Path '.\gradlew.bat') { $gradle='.\gradlew.bat' } else { $gradle='gradle' }
& $gradle ':app:assembleDebug' "-PbrouterTiles=$Tiles"
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& adb install -r '.\app\build\outputs\apk\debug\app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'Install failed' }
