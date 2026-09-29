param(
    [string]$Python = "python"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$windowsRoot = Join-Path $repoRoot "windows"
$outputRoot = Join-Path $repoRoot "build\windows"
$distRoot = Join-Path $outputRoot "dist"
$workRoot = Join-Path $outputRoot "work"
$helperWorkRoot = Join-Path $outputRoot "helper-work"
$appRoot = Join-Path $distRoot "FluentMai"
$archivePath = Join-Path $outputRoot "FluentMai-windows-portable.zip"
$checksumPath = "$archivePath.sha256"

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

Push-Location $windowsRoot
try {
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --windowed `
        --name FluentMai `
        --add-data "$windowsRoot\assets;assets" `
        --add-data "$windowsRoot\locales;locales" `
        --add-data "$windowsRoot\config.json;." `
        --add-data "$windowsRoot\THIRD_PARTY_NOTICES.md;." `
        --distpath $distRoot `
        --workpath $workRoot `
        --specpath $workRoot `
        main.py
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller exited with code $LASTEXITCODE"
    }

    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --noconsole `
        --name FluentMaiCaptureProxy `
        --distpath $appRoot `
        --workpath $helperWorkRoot `
        --specpath $helperWorkRoot `
        capture_helper/main.py
    if ($LASTEXITCODE -ne 0) {
        throw "Capture helper PyInstaller exited with code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$executable = Join-Path $appRoot "FluentMai.exe"
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Packaged executable not found: $executable"
}
$helperExecutable = Join-Path $appRoot "FluentMaiCaptureProxy.exe"
if (-not (Test-Path -LiteralPath $helperExecutable -PathType Leaf)) {
    throw "Packaged capture helper not found: $helperExecutable"
}

$recoveryScriptsRoot = Join-Path $appRoot "scripts"
New-Item -ItemType Directory -Force -Path $recoveryScriptsRoot | Out-Null
Copy-Item -LiteralPath (Join-Path $windowsRoot "scripts\Restore-FluentMai-Network.ps1") -Destination $recoveryScriptsRoot -Force
Copy-Item -LiteralPath (Join-Path $windowsRoot "scripts\Restore-FluentMai-Network.cmd") -Destination $recoveryScriptsRoot -Force

# Keep a human-visible copy beside the executable as well as PyInstaller's
# bundled copy so the portable archive exposes its current notice status.
Copy-Item -LiteralPath (Join-Path $windowsRoot "THIRD_PARTY_NOTICES.md") -Destination $appRoot -Force

$forbidden = Get-ChildItem -LiteralPath $appRoot -Recurse -File | Where-Object {
    $_.Name -match '(?i)(\.db(?:-shm|-wal)?$|\.sqlite3?$|\.html?$|\.log$|^\.env(?:\.|$)|private[_-]?key|credentials?)'
}
if ($forbidden) {
    $paths = ($forbidden | Select-Object -ExpandProperty FullName) -join [Environment]::NewLine
    throw "Forbidden files found in portable directory:$([Environment]::NewLine)$paths"
}

if (Test-Path -LiteralPath (Join-Path $appRoot "assets\jackets")) {
    throw "Jacket artwork must not be bundled in the Windows artifact"
}

Compress-Archive -Path (Join-Path $appRoot "*") -DestinationPath $archivePath -CompressionLevel Optimal -Force
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Encoding ascii -Value "$hash  $([IO.Path]::GetFileName($archivePath))"

Write-Output "WINDOWS_PORTABLE_DIR=$appRoot"
Write-Output "WINDOWS_PORTABLE_ARCHIVE=$archivePath"
Write-Output "WINDOWS_PORTABLE_SHA256=$hash"
