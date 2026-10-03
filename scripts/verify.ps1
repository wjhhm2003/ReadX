param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
. 'E:\Android\android-dev-env.ps1'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat assembleDebug testDebugUnitTest lintDebug assemblePreview
    if ($LASTEXITCODE -ne 0) { throw "Build/static checks failed: $LASTEXITCODE" }
    if ($DeviceTests) {
        # Keep R8 compilation and emulator UI tests in separate invocations.
        & .\gradlew.bat connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Device tests failed: $LASTEXITCODE" }
    }
} finally { Pop-Location }
