$ErrorActionPreference = 'Stop'
$adb  = "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$emu  = "$env:USERPROFILE\AppData\Local\Android\Sdk\emulator\emulator.exe"
$apk  = "C:\Users\19819\Documents\kapi\kapi-android\app\build\outputs\apk\debug\app-debug.apk"

Write-Output "[1] kill old emulator"
& $adb emu kill 2>&1 | Out-Null
Start-Sleep -Seconds 6
Get-Process -Name qemu-system-x86_64*, emulator -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

Write-Output "[2] boot with host GPU"
Start-Process -FilePath $emu `
    -ArgumentList '-avd','Medium_Phone_API_36.1','-gpu','host','-no-snapshot-load','-no-boot-anim' `
    -WindowStyle Hidden

Write-Output "[3] wait for boot"
$deadline = (Get-Date).AddMinutes(6)
$booted = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 8
    $b = (& $adb shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
    if ("$b".Trim() -eq "1") { $booted = $true; break }
}
if (-not $booted) { Write-Output "BOOT_FAILED"; exit 1 }
Write-Output "[4] booted"

Write-Output "[5] gpu info"
(& $adb shell getprop debug.hwui.renderer)
(& $adb shell dumpsys SurfaceFlinger 2>$null | Select-String -Pattern "^GLES:" | Select-Object -First 1)

Write-Output "[6] install + launch"
& $adb install -r -t $apk | Out-Null
& $adb shell am start -n kapi.ledger.debug/kapi.ledger.ui.MainActivity | Out-Null
Start-Sleep -Seconds 8
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png "C:\Users\19819\Documents\kapi\n38-hwglass.png" | Out-Null
Write-Output "DONE"
