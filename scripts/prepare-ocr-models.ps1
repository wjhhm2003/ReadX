param([string]$LocalModelDirectory)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $project 'app/src/ocrBundled/assets/ocr'
$manifest = Get-Content -LiteralPath (Join-Path $assets 'manifest.json') -Raw | ConvertFrom-Json
foreach ($name in @('chi_sim', 'chi_tra', 'eng')) {
    $entry = $manifest.models.$name
    $destination = Join-Path $assets "$name.traineddata"
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $existing = Get-Item -LiteralPath $destination
        $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existing.Length -eq $entry.bytes -and $hash -eq $entry.sha256) {
            Write-Host "$name already verified"
            continue
        }
    }
    $temporary = Join-Path $assets ("model-" + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        if ($LocalModelDirectory) {
            Copy-Item -LiteralPath (Join-Path $LocalModelDirectory "$name.traineddata") -Destination $temporary
        } else {
            $url = "https://raw.githubusercontent.com/$($manifest.repository)/$($manifest.commit)/$name.traineddata"
            Write-Host "Downloading $name from pinned upstream commit $($manifest.commit)"
            Invoke-WebRequest -Uri $url -OutFile $temporary -UseBasicParsing
        }
        $actual = Get-Item -LiteralPath $temporary
        $hash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual.Length -ne $entry.bytes -or $hash -ne $entry.sha256) { throw "Model verification failed: $name" }
        Move-Item -LiteralPath $temporary -Destination $destination -Force
        Write-Host "$name verified: $hash"
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}
Write-Host 'Models ready. Build with: .\gradlew.bat assemblePreview -PbundledOcr=true'
