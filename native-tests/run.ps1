#Requires -Version 7.0
<#
.SYNOPSIS
    构建并运行 CS11 userspace lwIP 桥的主机测试（ASan）。

.DESCRIPTION
    用 MSYS2 的 clang 编 bridge/sys_arch.c + bridge/cs11_netif.c + vendored lwIP，跑 native-tests/tests.c。
    不需要车机、不需要平板；ASan 直接给行号。

    lwIP 的 tcpip 线程和邮箱活到进程退出，所以 ASan 的 leak 检测关掉（内存错误仍会报）。

.EXAMPLE
    .\run.ps1
    .\run.ps1 -Rebuild
#>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [string]$BuildDir = 'native-tests\build'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Find-Tool([string]$name, [string[]]$candidates) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw "找不到 $name（试过 PATH 与 $($candidates -join ', ')）"
}

$clang = Find-Tool 'clang' @(
    'D:\Dev Environment\msys64\clang64\bin\clang.exe',
    'C:\msys64\clang64\bin\clang.exe'
)
$cmake = Find-Tool 'cmake' @(
    'D:\Dev Environment\msys64\mingw64\bin\cmake.exe',
    'C:\msys64\mingw64\bin\cmake.exe'
)
$ninja = Find-Tool 'ninja' @(
    'D:\Dev Environment\msys64\mingw64\bin\ninja.exe',
    'C:\msys64\mingw64\bin\ninja.exe'
)

$clangBin = Split-Path -Parent $clang
$env:PATH = "$clangBin;$(Split-Path -Parent $ninja);$env:PATH"

# ASan and winpthreads are DLLs next to the toolchain; the runtime must be on PATH to run the exe.
$asanRoot = Join-Path (Split-Path -Parent $clangBin) 'lib\clang'
if (Test-Path $asanRoot) {
    $asanDirs = Get-ChildItem -Path $asanRoot -Recurse -Filter 'clang_rt.asan_dynamic-x86_64.dll' -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty DirectoryName -Unique
    if ($asanDirs) { $env:PATH = "$($asanDirs -join ';');$env:PATH" }
}

$buildPath = if ([System.IO.Path]::IsPathRooted($BuildDir)) { $BuildDir } else { Join-Path $repo $BuildDir }
if ($Rebuild -and (Test-Path $buildPath)) { Remove-Item -Recurse -Force $buildPath }

"clang : $clang"
"cmake : $cmake"
"build : $buildPath"

& $cmake -S (Join-Path $repo 'native-tests') -B $buildPath -G Ninja `
    -DCMAKE_C_COMPILER="$clang" `
    -DCMAKE_BUILD_TYPE=Debug
if ($LASTEXITCODE -ne 0) { throw "cmake configure 失败（$LASTEXITCODE）" }

& $cmake --build $buildPath
if ($LASTEXITCODE -ne 0) { throw "构建失败（$LASTEXITCODE）" }

$exe = Join-Path $buildPath 'cs11_lwip_tests.exe'
if (-not (Test-Path $exe)) { throw "找不到可执行文件: $exe" }

$env:ASAN_OPTIONS = 'detect_leaks=0:abort_on_error=1:strict_string_checks=1'
"`n运行 $exe"
& $exe
$code = $LASTEXITCODE
"`nexit=$code"
if ($code -ne 0) { throw "测试失败（exit=$code）" }
