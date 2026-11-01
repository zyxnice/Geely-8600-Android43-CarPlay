#Requires -Version 7.2
<#
.SYNOPSIS
Summarizes one process's Dalvik GC events from an existing threadtime logcat file.
.DESCRIPTION
Read-only, offline analysis. Explicit PID prevents mixing other apps' GC events.
Pauses are the reported stop-the-world segments, not the full concurrent collection time.
No matches means no matching records in this file, not proof that no GC occurred.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LogPath,
    [Parameter(Mandatory)][ValidateRange(1, 2147483647)][int]$ProcessId
)
$ErrorActionPreference = 'Stop'
$events = [Collections.Generic.List[object]]::new()
$gcRecordsWithoutPause = 0
$pipelineRecords = 0
foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $LogPath).Path)) {
    # Android logcat -v threadtime: date time PID TID priority tag: message.
    if ($line -notmatch '^\s*(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s+[VDIWEF]\s+([^:]+):\s*(.*)$') { continue }
    if ([int]$Matches[2] -ne $ProcessId) { continue }
    $timestamp = $Matches[1]
    $tag = $Matches[3].Trim()
    $message = $Matches[4]
    if ($message -match 'pipeline avg/max') { $pipelineRecords++ }
    if ($tag -ne 'dalvikvm' -or $message -notmatch '^GC_') { continue }
    if ($message -notmatch 'paused\s+([\d.]+(?:ms)?(?:\+[\d.]+(?:ms)?)*)\s*ms') {
        $gcRecordsWithoutPause++
        continue
    }
    $pauses = @([regex]::Matches($Matches[1], '[\d.]+') | ForEach-Object {
        [double]::Parse($_.Value, [Globalization.CultureInfo]::InvariantCulture)
    })
    $totalMs = if ($message -match 'total\s+([\d.]+)ms') {
        [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
    } else { $null }
    $events.Add([pscustomobject]@{
        timestamp = $timestamp
        pauseSumMs = ($pauses | Measure-Object -Sum).Sum
        longestPauseMs = ($pauses | Measure-Object -Maximum).Maximum
        collectionTotalMs = $totalMs
        message = $message
    })
}
[pscustomobject]@{
    processId = $ProcessId
    pipelineRecords = $pipelineRecords
    parsedGcEvents = $events.Count
    gcRecordsWithoutPause = $gcRecordsWithoutPause
    pauseSumMs = if ($events.Count) { ($events.pauseSumMs | Measure-Object -Sum).Sum } else { 0 }
    longestPauseMs = if ($events.Count) { ($events.longestPauseMs | Measure-Object -Maximum).Maximum } else { $null }
    evidence = 'Recorded process GC only; heap occupancy and elapsed video time cannot establish GC causality.'
    events = @($events.ToArray())
} | ConvertTo-Json -Depth 4
