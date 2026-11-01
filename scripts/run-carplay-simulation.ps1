#Requires -Version 7.2
<#
.SYNOPSIS
Runs the local CarPlay protocol peer and virtual-time queue pressure matrix; no ADB or vehicle access.
.DESCRIPTION
Uses the existing Gradle/JUnit/Robolectric toolchain. Writes a unique evidence directory per invocation.
Protocol tests seed post-pairing test keys; virtual codec numbers are model results, not hardware FPS.
#>
[CmdletBinding()]
param(
    [ValidateRange(1,20)][int]$Repeats = 1,
    [string]$JavaHome,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome) {
    $bundled = Join-Path (Split-Path $repo -Parent) 'analysis/build-tools/jdk-25.0.3+9'
    $JavaHome = if (Test-Path -LiteralPath (Join-Path $bundled 'bin/java.exe')) { $bundled } else { $env:JAVA_HOME }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Supply -JavaHome for the repository JDK 25 toolchain.'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repo ('build/reports/carplay-local-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory to preserve prior evidence.' }
$null = New-Item -ItemType Directory -Path $OutputDirectory
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$savedJava = $env:JAVA_HOME
$savedPath = $env:PATH
$savedHost = $env:HOST_OS
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    $env:HOST_OS = 'windows'
    $head = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot record repository revision.' }
    $sourceFiles = Get-ChildItem shared/src/main,shared/src/test -File -Recurse |
        Where-Object { $_.Extension -in '.kt','.java' }
    $sourceFiles | ForEach-Object {
        [pscustomobject]@{ path = [IO.Path]::GetRelativePath($repo,$_.FullName); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'source-sha256.json') -Encoding utf8
    & git status --short | Set-Content -LiteralPath (Join-Path $OutputDirectory 'worktree-status.txt') -Encoding utf8
    $summaries = @()
    for ($iteration = 1; $iteration -le $Repeats; $iteration++) {
        $runDirectory = Join-Path $OutputDirectory "run-$iteration"
        $null = New-Item -ItemType Directory -Path $runDirectory
        $started = Get-Date
        & .\gradlew.bat :shared:testDebugUnitTest --tests '*CarPlayInteractionSimulationTest' --tests '*VideoPipelineSimulationTest' --rerun-tasks --no-daemon --max-workers=2 "-Dorg.gradle.java.installations.paths=$JavaHome" *> (Join-Path $runDirectory 'gradle.log')
        if ($LASTEXITCODE -ne 0) { throw "Simulation failed; inspect $runDirectory/gradle.log" }
        $suitePaths = @(
            'shared/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.airplay.CarPlayInteractionSimulationTest.xml',
            'shared/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.media.VideoPipelineSimulationTest.xml'
        )
        $tests = 0; $failures = 0; $skipped = 0
        foreach ($suitePath in $suitePaths) {
            $file = Get-Item -LiteralPath $suitePath
            if ($file.LastWriteTime -lt $started.AddSeconds(-2)) { throw "Stale test evidence: $suitePath" }
            [xml]$xml = Get-Content -LiteralPath $suitePath -Raw -Encoding utf8
            $tests += [int]$xml.testsuite.tests
            $failures += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
            $skipped += [int]$xml.testsuite.skipped
            Copy-Item -LiteralPath $suitePath -Destination $runDirectory
        }
        if ($tests -lt 8 -or $failures -ne 0 -or $skipped -ne 0) { throw 'Required simulation cases did not all pass.' }
        Copy-Item -LiteralPath shared/build/reports/carplay-simulation/virtual-pressure.csv -Destination $runDirectory
        $summaries += [pscustomobject]@{ run=$iteration; tests=$tests; failures=$failures; skipped=$skipped }
        Write-Output "Run $iteration passed: $tests tests. Evidence: $runDirectory"
    }
    [pscustomobject]@{
        revision=$head; completedAt=(Get-Date -Format o); runs=$summaries
        scope='Post-pairing encrypted protocol sockets and synthetic virtual-time pressure; not physical display or car acceptance.'
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
    Write-Output "Simulation evidence: $OutputDirectory"
} finally {
    Pop-Location
    $env:JAVA_HOME = $savedJava
    $env:PATH = $savedPath
    $env:HOST_OS = $savedHost
}
