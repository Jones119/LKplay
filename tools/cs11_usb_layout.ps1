# Dumps the iPhone's USB layout on the CS11 so two ports (and two debugging states) can be compared.
#
# Why this exists: our userspace NCM bring-up needs one device whose raw descriptors carry both USBMUX and
# NCM, and on this car the phone exposes two Apple device instances (observed: 2-1.1 and 2-1.4, with cdc_ncm
# bound to 2-1.4:6.3). Which instance carries what - and whether the port or USB debugging changes that - is
# exactly what decides how the wired path must be fixed, so it is measured rather than guessed.
#
# House rules learned on this ROM: its sh has no working `for` and no `basename`, so every remote command
# here is a single flat sequence joined with `;`, and all looping happens on the Windows side.
#
# Usage:  pwsh -File tools\cs11_usb_layout.ps1 [-Serial 0123456789ABCDEF] [-Label "usb1-debug-on"]
param(
    [string]$Serial = '0123456789ABCDEF',
    [string]$Label = 'current',
    [string]$Adb = 'd:\Dev Environment\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'

function Sh([string]$command) {
    $out = & $Adb -s $Serial shell $command 2>&1
    return (($out | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

# One-line fields: any value with embedded newlines (the ROM's ls output often has them) is flattened.
function One([string]$text) { return (($text -split "`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ' ' }

function Value([string]$path) {
    $v = Sh "cat $path 2>/dev/null"
    if ($v -match 'No such file') { return '' }
    return (($v -split "`n")[0]).Trim()
}

Write-Output "=== USB layout ($Label) ==="
Write-Output ("  car usb config : " + (One (Sh "getprop sys.usb.config")))
Write-Output ("  netdevs        : " + (One (Sh "ls /sys/class/net")))
Write-Output ("  cdc_ncm bound  : " + (One (Sh "ls /sys/bus/usb/drivers/cdc_ncm 2>/dev/null | grep :")))

$nodes = (Sh "ls /sys/bus/usb/devices/") -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$devices = @()
foreach ($node in $nodes) {
    if ($node -match ':') { continue }
    $vid = Value "/sys/bus/usb/devices/$node/idVendor"
    if ($vid -ne '05ac') { continue }
    $devices += $node
    $product = Value "/sys/bus/usb/devices/$node/idProduct"
    $ncfg = Value "/sys/bus/usb/devices/$node/bNumConfigurations"
    $cur = Value "/sys/bus/usb/devices/$node/bConfigurationValue"
    Write-Output ""
    Write-Output ("  [APPLE DEVICE] $node  pid=$product numConfigs=$ncfg currentConfig=$cur")
    foreach ($iface in ($nodes | Where-Object { $_ -like "$node`:*" })) {
        $cls = Value "/sys/bus/usb/devices/$iface/bInterfaceClass"
        $sub = Value "/sys/bus/usb/devices/$iface/bInterfaceSubClass"
        $prt = Value "/sys/bus/usb/devices/$iface/bInterfaceProtocol"
        $drv = Sh "ls -l /sys/bus/usb/devices/$iface/driver 2>/dev/null"
        $drvName = if ($drv -match '->.*?([A-Za-z0-9_]+)$') { $Matches[1] } else { 'none' }
        # 0x0a = CDC-Data, 0x0d = NCM (CDC-NCM is usually 0x0a/0x0d with subclass 0x00 and protocol 0x01)
        $kind = switch ($cls) {
            '0a' { 'CDC-Data' }
            '02' { 'CDC-Control' }
            'ff' { 'vendor (USBMUX / CarPlay)' }
            '01' { 'audio' }
            '03' { 'HID' }
            '08' { 'mass-storage' }
            '09' { 'hub' }
            default { "class $cls" }
        }
        Write-Output ("      $iface  $kind  sub=$sub proto=$prt driver=$drvName")
    }
}

if ($devices.Count -eq 0) { Write-Output "  (no Apple device enumerated)" }

Write-Output ""
Write-Output "  netdev -> device links:"
foreach ($net in @('usb0', 'usb1')) {
    $link = Sh "ls -l /sys/class/net/$net/device 2>/dev/null"
    Write-Output ("    $net : " + $link)
}

Write-Output ""
Write-Output "  app's own NCM lines (last 6):"
$log = Sh "logcat -d -v time -s xcertplay-usb:* 2>/dev/null"
$ncm = ($log -split "`n") | Where-Object { $_ -match 'userspace NCM|NCM candidate|wired opening|transport attached' } | Select-Object -Last 6
if ($ncm) { $ncm | ForEach-Object { "    " + $_.Trim() } } else { Write-Output "    (none)" }
