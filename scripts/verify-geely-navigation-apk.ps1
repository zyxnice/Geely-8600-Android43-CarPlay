#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ApkPath,
    [Parameter(Mandatory)][string] $SdkDirectory,
    [Parameter(Mandatory)][ValidateSet('Observe', 'Disabled')][string] $Mode,
    [switch] $SourceOnly,
    [string] $BuildToolsVersion = '37.0.0'
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$toolsDirectory = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion"
$aapt = Join-Path $toolsDirectory 'aapt2.exe'
$badging = (& $aapt dump badging $resolvedApk) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK badging' }
if ($badging -notmatch "(?m)^(?:minSdkVersion|sdkVersion):'18'$" -or
    $badging -notmatch "native-code:.*'armeabi-v7a'") { throw 'API18/ARMv7 contract missing' }
$manifest = (& $aapt dump xmltree $resolvedApk --file AndroidManifest.xml) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect packaged manifest' }
$metadata = 'com.shilapi.xcertplay.GEELY_NAVIGATION_OBSERVE'
if ($Mode -eq 'Observe') {
    if ($badging -notmatch "package: name='com\.shihab\.diplay\.repair1\.geely8600'" -or
        $badging -notmatch "versionName='[^']*H53-USB1-NAVOBS1'") { throw 'Wrong observation variant/version' }
    if ($manifest -notmatch '(?s)GEELY_NAVIGATION_OBSERVE[^\n]*\n[^\n]*android:value[^\n]*(?:=true|0xffffffff)') {
        throw 'Observation metadata is not explicitly true'
    }
} elseif ($manifest.Contains($metadata)) { throw 'Ordinary build must not declare navigation observation metadata' }

$signature = (& cmd.exe /d /c (Join-Path $toolsDirectory 'apksigner.bat') verify --verbose --min-sdk-version 18 $resolvedApk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $signature -notmatch 'Verified using v1 scheme \(JAR signing\): true') {
    throw 'APK does not verify with the API18 v1 signing scheme'
}
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
try {
    if ($SourceOnly -and @($archive.Entries | Where-Object { $_.FullName -like 'assets/offline-mfi/*' }).Count -gt 0) {
        throw 'Source-only verification APK unexpectedly contains runtime authentication assets'
    }
    $required = @('Iap2RouteState;', 'GeelyNavigationObserver;', 'GeelyNavigationObservation;', 'GeelyNavigationFrame;')
    $found = [Collections.Generic.HashSet[string]]::new()
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
        $stream = $entry.Open()
        $buffer = [IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() }
        finally { $stream.Dispose(); $buffer.Dispose() }
        if ($bytes.Length -lt 8 -or [Convert]::ToHexString($bytes[0..7]) -ne '6465780A30333500') {
            throw "Application DEX is not API18-compatible DEX035: $($entry.FullName)"
        }
        $dexText = [Text.Encoding]::ASCII.GetString($bytes)
        foreach ($name in $required) { if ($dexText.Contains("/hud/$name")) { [void] $found.Add($name) } }
    }
    foreach ($name in $required) { if (-not $found.Contains($name)) { throw "Navigation implementation absent from final DEX: $name" } }
} finally { $archive.Dispose() }
Write-Output "Verified mode=$Mode, API18/ARMv7, v1 signature, DEX035 navigation classes; SourceOnly=$SourceOnly. No vehicle behavior verified."
Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256
