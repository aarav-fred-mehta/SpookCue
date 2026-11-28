<#
.SYNOPSIS
    Wakes unattended from hibernate, enables Wi-Fi and connects to "R Mehta 5G",
    runs SpookCue's whenjumpscare.com scraper in an isolated clone, pushes any
    new/updated movie files to GitHub, and returns to hibernate if unattended.
#>
[CmdletBinding()]
param(
    [switch]$SkipHibernate,
    [string]$WifiName = "R Mehta 5G",
    [string]$WifiInterface = "Wi-Fi",
    [string]$RepoUrl = "https://github.com/aarav-fred-mehta/SpookCue.git",
    [string]$BaseDir = "$env:LOCALAPPDATA\SpookCueScraper",
    [string]$PythonExe = "C:\Users\aarav\AppData\Local\Programs\Python\Python311\python.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$LogDir = Join-Path $BaseDir "logs"
$RepoDir = Join-Path $BaseDir "repo"
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$LogFile = Join-Path $LogDir ("scrape_{0}.log" -f (Get-Date -Format "yyyy-MM-dd_HHmmss"))
$LatestLog = Join-Path $LogDir "latest.log"

# Keep only last 14 days of logs
Get-ChildItem -Path $LogDir -Filter "scrape_*.log" -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
    Remove-Item -Force -ErrorAction SilentlyContinue

function Write-Log {
    param([string]$Message)
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
    Write-Output $line
}

function Invoke-LoggedCommand {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @ArgumentList 2>&1
        foreach ($item in $output) {
            $text = $item.ToString().TrimEnd()
            if ($text) {
                Write-Log $text
            }
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPref
    }
}

function Capture-NativeOutput {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = (& $FilePath @ArgumentList 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output   = $output
        }
    } finally {
        $ErrorActionPreference = $prevPref
    }
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class SpookCuePower {
    [StructLayout(LayoutKind.Sequential)]
    public struct LASTINPUTINFO {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SetThreadExecutionState(uint esFlags);

    [DllImport("user32.dll")]
    public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    public static uint GetLastInputTick() {
        LASTINPUTINFO lii = new LASTINPUTINFO();
        lii.cbSize = (uint)Marshal.SizeOf(typeof(LASTINPUTINFO));
        if (GetLastInputInfo(ref lii)) {
            return lii.dwTime;
        }
        return 0;
    }
}
"@ -ErrorAction SilentlyContinue

function Enable-WifiAndConnect {
    param(
        [string]$Ssid,
        [string]$InterfaceName
    )

    Write-Log "Ensuring Wi-Fi adapter '$InterfaceName' is enabled..."
    try {
        $adapter = Get-NetAdapter -Name $InterfaceName -ErrorAction SilentlyContinue
        if ($adapter -and ($adapter.AdminStatus -ne "Up" -or $adapter.Status -eq "Disabled")) {
            Enable-NetAdapter -Name $InterfaceName -Confirm:$false -ErrorAction Stop
            Write-Log "Enabled network adapter '$InterfaceName'."
            Start-Sleep -Seconds 3
        }
    } catch {
        Write-Log "Enable-NetAdapter skipped/non-fatal: $($_.Exception.Message)"
    }

    # Turn on Windows Software Wi-Fi Radio if toggled Off
    try {
        Add-Type -AssemblyName System.Runtime.WindowsRuntime
        $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
            Where-Object {
                $_.Name -eq 'AsTask' -and
                $_.GetParameters().Count -eq 1 -and
                $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
            })[0]

        function Await-WinRtOp($WinRtOp, [Type]$ResultType) {
            $netTask = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($WinRtOp))
            $netTask.Wait(-1) | Out-Null
            return $netTask.Result
        }

        [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
        [Windows.Devices.Radios.RadioAccessStatus, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
        [Windows.Devices.Radios.RadioState, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null
        [Windows.Devices.Radios.RadioKind, Windows.System.Devices, ContentType = WindowsRuntime] | Out-Null

        $radios = Await-WinRtOp ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
        foreach ($radio in $radios) {
            if ($radio.Kind -eq [Windows.Devices.Radios.RadioKind]::WiFi -and $radio.State -ne [Windows.Devices.Radios.RadioState]::On) {
                $status = Await-WinRtOp ($radio.SetStateAsync([Windows.Devices.Radios.RadioState]::On)) ([Windows.Devices.Radios.RadioAccessStatus])
                Write-Log "Turned Wi-Fi software radio On (status: $status)."
                Start-Sleep -Seconds 3
            }
        }
    } catch {
        Write-Log "WinRT Wi-Fi radio check note: $($_.Exception.Message)"
    }

    # Ensure auto-connect is enabled on the saved profile and connect to target SSID
    $null = Capture-NativeOutput -FilePath "netsh.exe" -ArgumentList @("wlan", "set", "profileparameter", "name=$Ssid", "connectionmode=auto")

    $deadline = (Get-Date).AddSeconds(45)
    $lastAttempt = [DateTime]::MinValue
    while ((Get-Date) -lt $deadline) {
        $wlanRes = Capture-NativeOutput -FilePath "netsh.exe" -ArgumentList @("wlan", "show", "interfaces")
        $wlanInfo = $wlanRes.Output
        $isConnectedTarget = ($wlanInfo -match "(?im)^\s*State\s*:\s*connected") -and ($wlanInfo -match ("(?im)^\s*SSID\s*:\s*" + [regex]::Escape($Ssid) + "\s*$"))
        if (-not $isConnectedTarget -and ((Get-Date) - $lastAttempt).TotalSeconds -ge 8) {
            Write-Log "Connecting Wi-Fi interface '$InterfaceName' to '$Ssid'..."
            $connRes = Capture-NativeOutput -FilePath "netsh.exe" -ArgumentList @("wlan", "connect", "name=$Ssid", "interface=$InterfaceName")
            if ($connRes.Output.Trim()) {
                Write-Log ("netsh wlan connect: " + $connRes.Output.Trim())
            }
            $lastAttempt = Get-Date
        }

        if ($isConnectedTarget) {
            try {
                [System.Net.Dns]::GetHostAddresses("www.whenjumpscare.com") | Out-Null
                [System.Net.Dns]::GetHostAddresses("github.com") | Out-Null
                Write-Log "Wi-Fi connected to '$Ssid' and DNS resolution succeeded."
                return $true
            } catch {
                Write-Log "Wi-Fi connected; waiting for DNS..."
            }
        }
        Start-Sleep -Seconds 2
    }

    # Fallback check in case Ethernet already provides connectivity while Wi-Fi failed
    try {
        [System.Net.Dns]::GetHostAddresses("www.whenjumpscare.com") | Out-Null
        [System.Net.Dns]::GetHostAddresses("github.com") | Out-Null
        Write-Log "Network reachable via active connection despite Wi-Fi check timeout."
        return $true
    } catch {
        Write-Log "ERROR: Network unreachable after 45s wait."
        return $false
    }
}

$ES_CONTINUOUS = [uint32]::Parse("80000000", [System.Globalization.NumberStyles]::HexNumber)
$ES_SYSTEM_REQUIRED = [uint32]1
$initialLastInputTick = [SpookCuePower]::GetLastInputTick()

Write-Log "=== SpookCue local wake-and-scrape started (PID=$PID) ==="
[void][SpookCuePower]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)

try {
    $netOk = Enable-WifiAndConnect -Ssid $WifiName -InterfaceName $WifiInterface
    if (-not $netOk) {
        throw "Could not establish network connectivity."
    }

    if (-not (Test-Path (Join-Path $RepoDir ".git"))) {
        Write-Log "Cloning repository into isolated runner directory: $RepoDir"
        New-Item -ItemType Directory -Path (Split-Path $RepoDir -Parent) -Force | Out-Null
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("clone", "--branch", "main", $RepoUrl, $RepoDir)
        if ($rc -ne 0) {
            throw "git clone failed with exit code $rc"
        }
    } else {
        Write-Log "Syncing isolated clone at $RepoDir with origin/main..."
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "fetch", "origin", "main")
        if ($rc -ne 0) { throw "git fetch failed ($rc)" }
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "checkout", "main")
        if ($rc -ne 0) { throw "git checkout failed ($rc)" }
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "reset", "--hard", "origin/main")
        if ($rc -ne 0) { throw "git reset failed ($rc)" }
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "clean", "-fd")
        if ($rc -ne 0) { throw "git clean failed ($rc)" }
    }

    if (-not (Test-Path $PythonExe)) {
        $cmdPy = Get-Command python -ErrorAction Stop
        $PythonExe = $cmdPy.Source
    }

    $env:PYTHONPATH = Join-Path $RepoDir "scraper\src"
    Write-Log "Running whenjumpscare.com scraper (--mode whenjumpscare --update-sitemap)..."
    $dataDir = Join-Path $RepoDir "data"
    $rc = Invoke-LoggedCommand -FilePath $PythonExe -ArgumentList @("-m", "spookcue_scraper", "--data-dir", $dataDir, "--mode", "whenjumpscare", "--update-sitemap")
    if ($rc -ne 0) {
        throw "spookcue_scraper exited with code $rc"
    }

    $statusRes = Capture-NativeOutput -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "status", "--porcelain", "--", "data/", "scraper/src/spookcue_scraper/data/whenjumpscare_sitemap.xml")
    if ($statusRes.Output.Trim()) {
        $userName = (Capture-NativeOutput -FilePath "git.exe" -ArgumentList @("config", "--get", "user.name")).Output.Trim()
        $userEmail = (Capture-NativeOutput -FilePath "git.exe" -ArgumentList @("config", "--get", "user.email")).Output.Trim()
        if ($userName) { $null = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "config", "user.name", $userName) }
        if ($userEmail) { $null = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "config", "user.email", $userEmail) }

        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "add", "data/", "scraper/src/spookcue_scraper/data/whenjumpscare_sitemap.xml")
        if ($rc -ne 0) { throw "git add failed ($rc)" }
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "commit", "-m", "chore(data): whenjumpscare local sync")
        if ($rc -ne 0) { throw "git commit failed ($rc)" }

        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "pull", "--rebase", "origin", "main")
        if ($rc -ne 0) { throw "git pull --rebase failed ($rc)" }
        $rc = Invoke-LoggedCommand -FilePath "git.exe" -ArgumentList @("-C", $RepoDir, "push", "origin", "main")
        if ($rc -ne 0) { throw "git push failed ($rc)" }

        Write-Log "Pushed whenjumpscare updates to GitHub (will trigger TMDB sync)."
    } else {
        Write-Log "No whenjumpscare updates found; skipping git commit/push."
    }
} catch {
    Write-Log "ERROR: $($_.Exception.Message)"
} finally {
    [void][SpookCuePower]::SetThreadExecutionState($ES_CONTINUOUS)
    $finalLastInputTick = [SpookCuePower]::GetLastInputTick()
    Copy-Item -Path $LogFile -Destination $LatestLog -Force -ErrorAction SilentlyContinue

    if ($SkipHibernate) {
        Write-Log "SkipHibernate specified; leaving PC awake."
    } elseif ($finalLastInputTick -ne $initialLastInputTick) {
        Write-Log "User input detected during run; leaving PC awake."
    } else {
        Write-Log "Unattended run finished; returning PC to hibernate in 5 seconds..."
        Copy-Item -Path $LogFile -Destination $LatestLog -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
        Start-Process -FilePath "shutdown.exe" -ArgumentList "/h" -WindowStyle Hidden
    }
}
