param(
    [ValidateSet("source", "package")]
    [string]$Mode = "source",
    [string]$Python = "python",
    [string]$Executable = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$windowsRoot = Join-Path $repoRoot "windows"
$smokeRoot = Join-Path $env:TEMP ("FluentMai-Smoke-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $smokeRoot | Out-Null

$previousQtPlatform = $env:QT_QPA_PLATFORM
$previousDbPath = $env:FLUENTMAI_DB_PATH
$previousCoverCache = $env:FLUENTMAI_COVER_CACHE
$previousDataDir = $env:FLUENTMAI_DATA_DIR
$previousCacheDir = $env:FLUENTMAI_CACHE_DIR
$env:QT_QPA_PLATFORM = "offscreen"
$env:FLUENTMAI_DATA_DIR = $smokeRoot
$env:FLUENTMAI_DB_PATH = Join-Path $smokeRoot "smoke.db"
$env:FLUENTMAI_CACHE_DIR = Join-Path $smokeRoot "cache"
$env:FLUENTMAI_COVER_CACHE = Join-Path $smokeRoot "covers"

try {
    if ($Mode -eq "source") {
        $filePath = $Python
        $arguments = @("main.py", "--smoke-test")
        $workingDirectory = $windowsRoot
    }
    else {
        if ([string]::IsNullOrWhiteSpace($Executable)) {
            $Executable = Join-Path $repoRoot "build\windows\dist\FluentMai\FluentMai.exe"
        }
        $filePath = (Resolve-Path -LiteralPath $Executable).Path
        $arguments = @("--smoke-test")
        $workingDirectory = Split-Path -Parent $filePath
    }

    $process = Start-Process `
        -FilePath $filePath `
        -ArgumentList $arguments `
        -WorkingDirectory $workingDirectory `
        -WindowStyle Hidden `
        -PassThru

    if (-not $process.WaitForExit(30000)) {
        Stop-Process -Id $process.Id -Force
        throw "Windows $Mode smoke test did not exit within 30 seconds"
    }
    $process.Refresh()
    if ($process.ExitCode -ne 0) {
        throw "Windows $Mode smoke test exited with code $($process.ExitCode)"
    }

    Write-Output "WINDOWS_SMOKE_MODE=$Mode"
    Write-Output "WINDOWS_SMOKE_EXIT=0"
    Write-Output "WINDOWS_SMOKE_DATA=$smokeRoot"
}
finally {
    $env:QT_QPA_PLATFORM = $previousQtPlatform
    $env:FLUENTMAI_DATA_DIR = $previousDataDir
    $env:FLUENTMAI_DB_PATH = $previousDbPath
    $env:FLUENTMAI_CACHE_DIR = $previousCacheDir
    $env:FLUENTMAI_COVER_CACHE = $previousCoverCache
}
