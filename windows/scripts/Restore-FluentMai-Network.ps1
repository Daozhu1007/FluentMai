param(
    [string]$JournalPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($JournalPath)) {
    $JournalPath = Join-Path $env:LOCALAPPDATA "FluentMai\capture\network-recovery.bin"
}

if (-not (Test-Path -LiteralPath $JournalPath -PathType Leaf)) {
    Write-Output "FluentMai has no pending network recovery journal."
    exit 0
}

$entropy = [Text.Encoding]::UTF8.GetBytes("FluentMai.NetworkRecovery.v1")
$ciphertext = [IO.File]::ReadAllBytes($JournalPath)
$plaintext = [Security.Cryptography.ProtectedData]::Unprotect(
    $ciphertext,
    $entropy,
    [Security.Cryptography.DataProtectionScope]::CurrentUser
)
$record = [Text.Encoding]::UTF8.GetString($plaintext) | ConvertFrom-Json
if ($record.version -ne 1) {
    throw "Unsupported FluentMai network recovery journal version."
}

$hasHelperStartedAt = $record.PSObject.Properties.Name -contains "helper_started_at"
if ($record.helper_pid -and $record.helper_path -and $hasHelperStartedAt -and $null -ne $record.helper_started_at) {
    $process = Get-Process -Id ([int]$record.helper_pid) -ErrorAction SilentlyContinue
    if ($process) {
        $actualPath = ""
        try { $actualPath = $process.Path } catch { $actualPath = "" }
        $actualStartedAt = [DateTimeOffset]::new($process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() / 1000.0
        $sameStart = [Math]::Abs($actualStartedAt - [double]$record.helper_started_at) -le 1.0
        if ($actualPath -and $sameStart -and (
            [IO.Path]::GetFullPath($actualPath) -eq [IO.Path]::GetFullPath([string]$record.helper_path)
        )) {
            Stop-Process -Id $process.Id -Force
            $process.WaitForExit(5000)
        }
    }
}

$internetSettings = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
if (-not (Test-Path -LiteralPath $internetSettings)) {
    New-Item -Path $internetSettings -Force | Out-Null
}

function Restore-RegistryValue {
    param(
        [string]$Name,
        [object]$Value,
        [ValidateSet("DWord", "String")]
        [string]$Type
    )

    if ($null -eq $Value) {
        Remove-ItemProperty -LiteralPath $internetSettings -Name $Name -ErrorAction SilentlyContinue
    }
    else {
        Set-ItemProperty -LiteralPath $internetSettings -Name $Name -Type $Type -Value $Value
    }
}

$snapshot = $record.snapshot
Restore-RegistryValue -Name "ProxyEnable" -Value $snapshot.proxy_enable -Type DWord
Restore-RegistryValue -Name "ProxyServer" -Value $snapshot.proxy_server -Type String
Restore-RegistryValue -Name "AutoConfigURL" -Value $snapshot.auto_config_url -Type String
Restore-RegistryValue -Name "ProxyOverride" -Value $snapshot.proxy_override -Type String
Restore-RegistryValue -Name "AutoDetect" -Value $snapshot.auto_detect -Type DWord

if (-not ("FluentMai.WinInetNotify" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace FluentMai {
    public static class WinInetNotify {
        [DllImport("wininet.dll", SetLastError = true)]
        public static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);
    }
}
"@
}

foreach ($option in @(39, 37)) {
    if (-not [FluentMai.WinInetNotify]::InternetSetOption([IntPtr]::Zero, $option, [IntPtr]::Zero, 0)) {
        throw "WinINET settings notification failed."
    }
}

if ($snapshot.winhttp_dump_b64) {
    $netshScript = Join-Path ([IO.Path]::GetDirectoryName($JournalPath)) "winhttp-recovery.netsh"
    try {
        [IO.File]::WriteAllBytes($netshScript, [Convert]::FromBase64String([string]$snapshot.winhttp_dump_b64))
        & netsh exec $netshScript | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "WinHTTP restoration failed with exit code $LASTEXITCODE. Run this script as administrator."
        }
    }
    finally {
        Remove-Item -LiteralPath $netshScript -Force -ErrorAction SilentlyContinue
    }
}

Remove-Item -LiteralPath $JournalPath -Force
Write-Output "FluentMai network settings were restored from the protected journal."
