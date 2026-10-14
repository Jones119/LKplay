# Cross-checks the JNI names the Kotlin side expects against the symbols the C side defines.
#
# A mismatch is invisible until the car runs it: System.loadLibrary succeeds, the first call throws
# UnsatisfiedLinkError, and the wired bring-up fails with nothing pointing at the naming. The check is
# mechanical, so it runs here instead of costing a car session.
#
# Usage:  pwsh -File tools/check_jni_symbols.ps1
param(
    [string]$Repo = 'D:\Development\DiAuto-DiPlay\CarPlay-CS11',
    [string]$KotlinClass = 'com/shilapi/xcertplay/transport/UserSpaceNetworkNative',
    [string]$NativeFile = 'shared/src/main/cpp/bridge/cs11_lwip_jni.c'
)

$ErrorActionPreference = 'Stop'
$kotlinFile = Join-Path $Repo 'shared/src/main/java/com/shilapi/xcertplay/transport/UserSpaceNetworkNative.kt'
$nativePath = Join-Path $Repo $NativeFile

if (-not (Test-Path $kotlinFile)) { throw "not found: $kotlinFile" }
if (-not (Test-Path $nativePath)) { throw "not found: $nativePath" }

# Every `external override fun name(` in the object is a JVM method the library must provide.
$expected = Select-String -Path $kotlinFile -Pattern 'external\s+override\s+fun\s+([A-Za-z0-9_]+)\s*\(' -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

# JNI symbol form: Java_<package with _ >_<Class>_<method>
$prefix = 'Java_' + ($KotlinClass -replace '/', '_') + '_'
$declared = Select-String -Path $nativePath -Pattern ([regex]::Escape($prefix) + '([A-Za-z0-9_]+)\s*\(') -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

$missing = $expected | Where-Object { $_ -notin $declared }
$extra = $declared | Where-Object { $_ -notin $expected }

Write-Output ("Kotlin external functions : {0}" -f $expected.Count)
Write-Output ("C JNI symbols declared    : {0}" -f $declared.Count)
if ($missing) { Write-Output ("MISSING in C (would throw UnsatisfiedLinkError): " + ($missing -join ', ')) }
if ($extra) { Write-Output ("UNUSED C symbols: " + ($extra -join ', ')) }

if ($missing -or $extra) {
    Write-Output 'FAIL'
    exit 1
}

Write-Output ("OK - every {0} name matches: {1}" -f $expected.Count, ($expected -join ', '))
exit 0
