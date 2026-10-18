#Requires -Version 7.0
<#
.SYNOPSIS
    把当前构建的带符号 .so 归档到 _artifacts\symbols\<git sha>\。

.DESCRIPTION
    为什么必须这么做：tombstone 里的地址只在**产生它的那次构建**里有效。代码一改、库一重编，
    同样的地址指向完全不同的函数——本轮就踩过：用新库解旧地址，`0xf889` 被解成 `tcpip_init`，
    而崩溃时那里是 `tcpip_thread_handle_msg+40`。

    所以每次编完原生库就归档一次；崩溃后按 meta.txt 里的 sha 找回来符号化。

.EXAMPLE
    .\archive-native-symbols.ps1
#>
[CmdletBinding()]
param(
    [string]$OutRoot = '_artifacts\symbols'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$sha = (& git -C $repo rev-parse --short HEAD 2>&1) -join ''
if (-not $sha) { throw '拿不到 git sha（是不是不在仓库里？）' }
$dirty = (& git -C $repo status --porcelain 2>&1 | Measure-Object).Count -gt 0
$label = if ($dirty) { "$sha-dirty" } else { $sha }

$dest = if ([System.IO.Path]::IsPathRooted($OutRoot)) {
    Join-Path $OutRoot $label
} else {
    Join-Path $repo (Join-Path $OutRoot $label)
}
New-Item -ItemType Directory -Path $dest -Force | Out-Null

# CMake 的构建目录带一个哈希（如 Debug\122a2m6u），扫出来而不是写死
$cxxRoot = Join-Path $repo 'shared\build\intermediates\cxx'
if (-not (Test-Path $cxxRoot)) { throw "找不到 $cxxRoot —— 先跑一次 assembleStandaloneDebug" }

$libs = Get-ChildItem $cxxRoot -Recurse -Filter 'liblwip_bridge.so' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\obj\\' }

if (-not $libs) { throw "没找到带符号的 liblwip_bridge.so（在 $cxxRoot 下的 obj 目录里）" }

"归档到: $dest"
foreach ($lib in $libs) {
    $abi = $lib.Directory.Name
    $target = Join-Path $dest "$abi-liblwip_bridge.so"
    Copy-Item $lib.FullName $target -Force
    "  $abi  $([math]::Round($lib.Length / 1KB)) KB  →  $(Split-Path -Leaf $target)"
}

@(
    "sha        = $sha$(if ($dirty) { ' (dirty: 工作区有未提交改动)' } else { '' })"
    "time       = $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    "source     = $cxxRoot"
    "note       = 符号化时用这里的 .so，不要用构建目录里的（会被下次构建覆盖）"
    "example    = llvm-symbolizer --obj=<abi>-liblwip_bridge.so --functions=linkage 0x1f521"
) | Out-File -FilePath (Join-Path $dest 'meta.txt') -Encoding utf8

"`n完成。符号化示例："
$sym = Join-Path $env:ANDROID_HOME 'ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-symbolizer.exe'
if (Test-Path $sym) {
    "  & `"$sym`" --obj=`"$(Join-Path $dest 'armeabi-v7a-liblwip_bridge.so')`" --functions=linkage 0x1f521"
} else {
    "  未找到 llvm-symbolizer（检查 NDK 路径）"
}
