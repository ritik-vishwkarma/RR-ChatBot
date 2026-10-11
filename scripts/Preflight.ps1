[CmdletBinding()]
param(
    [string]$DatabaseHost = '127.0.0.1',
    [int]$DatabasePort = 5432,
    [int]$AppPort = 8080,
    [int]$AiStubPort = 8090
)

$ErrorActionPreference = 'Continue'
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check([string]$Name, [bool]$Passed, [string]$Detail) {
    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $checks.Add([pscustomobject]@{ Status = $status; Check = $Name; Detail = $Detail }) | Out-Null
    Write-Host ("{0,-4} {1} - {2}" -f $status, $Name, $Detail)
}

$java = Get-Command java -ErrorAction SilentlyContinue
Add-Check 'Java executable' ($null -ne $java) $(if ($java) { $java.Source } else { 'java not found in PATH' })
if ($java) {
    $versionText = (& java -version 2>&1 | Out-String)
    $versionMatch = [regex]::Match($versionText, 'version "(?<major>\d+)')
    $major = if ($versionMatch.Success) { [int]$versionMatch.Groups['major'].Value } else { 0 }
    Add-Check 'Java 17+' ($major -ge 17) $(if ($major -ge 17) { "detected Java major version $major" } else { "could not confirm Java 17+; output: $($versionText.Trim())" })
}
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
Add-Check 'Maven executable' ($null -ne $mvn) $(if ($mvn) { $mvn.Source } else { 'mvn not found in PATH; install Maven or add it to PATH' })
$python = Get-Command python -ErrorAction SilentlyContinue
Add-Check 'Python executable for local AI stub' ($null -ne $python) $(if ($python) { $python.Source } else { 'python not found in PATH' })

foreach ($port in @(@{ Name = 'PostgreSQL'; Host = $DatabaseHost; Port = $DatabasePort }, @{ Name = 'Spring Boot port'; Host = '127.0.0.1'; Port = $AppPort }, @{ Name = 'AI stub port'; Host = '127.0.0.1'; Port = $AiStubPort })) {
    $available = $false
    try {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        $async = $tcp.BeginConnect($port.Host, $port.Port, $null, $null)
        $available = $async.AsyncWaitHandle.WaitOne(1200, $false) -and $tcp.Connected
        $tcp.Close()
    } catch { $available = $false }
    $meaning = if ($port.Name -eq 'PostgreSQL') { 'a service is listening; confirm it is the intended test database' } else { 'a service is listening; it may conflict with the app/stub you need to start' }
    $detail = if ($available) { $meaning } else { "nothing listening at $($port.Host):$($port.Port) (expected until service is started)" }
    # App and AI ports being unused is normal before startup; only PostgreSQL is a hard prerequisite.
    $passed = if ($port.Name -eq 'PostgreSQL') { $available } else { -not $available }
    Add-Check "$($port.Name) port preflight" $passed $detail
}

if (-not (Test-Path 'pom.xml')) {
    Add-Check 'Run from project root' $false 'Open PowerShell in the folder containing pom.xml before running this script.'
} else {
    Add-Check 'Run from project root' $true (Get-Location).Path
}

if ($checks.Status -contains 'FAIL') {
    Write-Host 'Preflight has failed checks. Resolve them before starting the test run.' -ForegroundColor Red
    exit 1
}
Write-Host 'Preflight checks passed. Port checks only establish reachability; they do not validate credentials/schema.' -ForegroundColor Green
