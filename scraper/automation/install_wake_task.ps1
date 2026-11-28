<#
.SYNOPSIS
    Installs the SpookCue local wake-from-hibernate Scheduled Task (03:00 AM daily,
    runs on both AC and battery) and enables Windows wake timers (RTCWAKE).
#>
[CmdletBinding()]
param(
    [string]$TaskName = "SpookCueDailyWhenJumpScare",
    [string]$TriggerTime = "03:00AM",
    [string]$InstallDir = "$env:LOCALAPPDATA\SpookCueScraper"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$sourceRunner = Join-Path $PSScriptRoot "run_local_whenjumpscare.ps1"
if (-not (Test-Path $sourceRunner)) {
    throw "Cannot find $sourceRunner"
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
$installedRunner = Join-Path $InstallDir "run_local_whenjumpscare.ps1"
Copy-Item -Path $sourceRunner -Destination $installedRunner -Force

$argList = "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$installedRunner`""
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $argList
$trigger = New-ScheduledTaskTrigger -Daily -At $TriggerTime
$settings = New-ScheduledTaskSettingsSet `
    -WakeToRun `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 1)

$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description "Wakes PC from hibernate at 03:00 AM daily, enables Wi-Fi (R Mehta 5G), runs SpookCue whenjumpscare scraper, pushes updates, and returns to hibernate." `
    -Force | Out-Null

Write-Output "Registered Scheduled Task '$TaskName' ($TriggerTime daily, WakeToRun=True, OnBatteries=True)."

# Enable Windows Wake Timers on both AC and DC (requires Administrator privileges on Windows)
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    powercfg /SETACVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
    powercfg /SETDCVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
    powercfg /SETACTIVE SCHEME_CURRENT
    Write-Output "Enabled Windows Wake Timers (RTCWAKE=1) for both AC and Battery."
} else {
    Write-Warning "Wake timers (RTCWAKE) require Administrator privileges to modify via powercfg."
    Write-Output "Run the following command once in an Administrator PowerShell window:"
    Write-Output "  powercfg /SETACVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1; powercfg /SETDCVALUEINDEX SCHEME_CURRENT SUB_SLEEP RTCWAKE 1; powercfg /SETACTIVE SCHEME_CURRENT"
}
