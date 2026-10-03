param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
. 'E:\Android\android-dev-env.ps1'
Push-Location (Split-Path $PSScriptRoot -Parent)
try { & .\gradlew.bat @Tasks; if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" } }
finally { Pop-Location }
