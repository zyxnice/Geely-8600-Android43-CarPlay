#requires -Version 7.0
<#
.SYNOPSIS
Keeps a local SSH data tunnel to the car's dedicated loopback relay available.
.DESCRIPTION
This runs only SSH -N -T data forwarding; server administration stays in Cloud
Assistant. HostKeyFile must contain the independently verified server key.
The remote reverse listener must already be provided by the car's SSH client.
Create LogDirectory/stop.request to stop this supervisor and its current child.
No Windows login/startup task or server shell is created.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RemoteHost,
    [Parameter(Mandatory)][string]$RemoteUser,
    [Parameter(Mandatory)][string]$PrivateKeyFile,
    [Parameter(Mandatory)][string]$HostKeyFile,
    [Parameter(Mandatory)][string]$LogDirectory,
    [ValidateRange(1024,65535)][int]$LocalPort=15556,
    [ValidateRange(1024,65535)][int]$RemotePort=15556
)
$ErrorActionPreference='Stop'
$ssh=Join-Path $env:WINDIR 'System32/OpenSSH/ssh.exe'
foreach($path in @($ssh,$PrivateKeyFile,$HostKeyFile)) {
    if(-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file missing: $path" }
}
if($RemoteHost -notmatch '^[a-zA-Z0-9.-]+$' -or $RemoteUser -notmatch '^[a-zA-Z0-9_-]+$') {
    throw 'Unexpected SSH destination syntax'
}
$LogDirectory=[IO.Path]::GetFullPath($LogDirectory)
[IO.Directory]::CreateDirectory($LogDirectory) | Out-Null
$stopFile=Join-Path $LogDirectory 'stop.request'
if(Test-Path -LiteralPath $stopFile) { throw "Remove the previous stop request before starting: $stopFile" }
$mutex=[Threading.Mutex]::new($false,"Local\GeelyCarAdbTunnel$LocalPort")
$owned=$false
$child=$null
function Write-TunnelEvent([string]$Message) {
    $line="$(Get-Date -Format o) $Message"
    Add-Content -LiteralPath (Join-Path $LogDirectory 'supervisor.log') -Value $line -Encoding utf8
    Write-Output $line
}
try {
    try { $owned=$mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned=$true }
    if(-not $owned) { throw "Another supervisor owns local port $LocalPort" }
    if(Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Local port $LocalPort already has a listener; inspect before starting"
    }
    $PID | Set-Content -LiteralPath (Join-Path $LogDirectory 'supervisor.pid') -Encoding ascii
    $attempt=0
    while(-not (Test-Path -LiteralPath $stopFile)) {
        $attempt++
        $stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
        $start=[Diagnostics.ProcessStartInfo]::new($ssh)
        $start.UseShellExecute=$false
        $start.CreateNoWindow=$true
        # SSH writes errors directly to its own file; no pipe can fill and block it.
        foreach($argument in @('-F','NUL','-N','-T','-p','22','-E',(Join-Path $LogDirectory "ssh-$stamp.log"),
            '-i',[IO.Path]::GetFullPath($PrivateKeyFile),
            '-o',"UserKnownHostsFile=$([IO.Path]::GetFullPath($HostKeyFile))",
            '-o','GlobalKnownHostsFile=NUL','-o','IdentityAgent=none',
            '-o','StrictHostKeyChecking=yes','-o','IdentitiesOnly=yes','-o','BatchMode=yes',
            '-o','ConnectTimeout=10','-o','ConnectionAttempts=1','-o','ExitOnForwardFailure=yes',
            '-o','ServerAliveInterval=15','-o','ServerAliveCountMax=3',
            '-L',"127.0.0.1:${LocalPort}:127.0.0.1:${RemotePort}","$RemoteUser@$RemoteHost")) {
            $start.ArgumentList.Add($argument)
        }
        $child=[Diagnostics.Process]::Start($start)
        $child.Id | Set-Content -LiteralPath (Join-Path $LogDirectory 'ssh.pid') -Encoding ascii
        Write-TunnelEvent "Attempt=$attempt SSH_PID=$($child.Id) local=127.0.0.1:$LocalPort"
        while(-not $child.WaitForExit(1000)) {
            if(Test-Path -LiteralPath $stopFile) { $child.Kill(); $child.WaitForExit(); break }
        }
        Write-TunnelEvent "SSH_PID=$($child.Id) exited=$($child.ExitCode)"
        $child.Dispose()
        $child=$null
        if(Test-Path -LiteralPath $stopFile) { break }
        # Retry the same pinned endpoint; never fall back to passwords or a new host.
        Start-Sleep -Seconds 5
    }
} finally {
    if($null -ne $child) {
        if(-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }
        $child.Dispose()
    }
    if($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
