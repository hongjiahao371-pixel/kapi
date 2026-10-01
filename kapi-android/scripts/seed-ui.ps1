# 在模拟器上手工灌测试数据。
#
# 存在的理由：账单列表和统计页的视觉问题（颜色区分度、条形图比例、空状态
# 位置）只有在一屏有 10 条以上、跨 3 个以上分类时才暴露得出来。空库截图
# 好看不代表有数据时好看。
#
# 用法：先装好 APK 并启动一次，然后
#   powershell -File scripts/seed-ui.ps1
param(
  [string]$Adb = "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'

function Tap([int]$x, [int]$y) {
  & $Adb shell input tap $x $y | Out-Null
  Start-Sleep -Milliseconds 420
}

# 每笔: 金额(数字串) | 分类 chip 的 x | 说明
$script = @(
  @{ keys = "23.50";  cat = 114;  note = "" },
  @{ keys = "128.00"; cat = 250;  note = "" },
  @{ keys = "56.80";  cat = 114;  note = "" },
  @{ keys = "8800.00"; cat = 390;  note = "" },
  @{ keys = "15.00";  cat = 528;  note = "" },
  @{ keys = "299.00"; cat = 665;  note = "" },
  @{ keys = "42.00";  cat = 803;  note = "" }
)

# 账本页右下角 FAB
Tap 964 2215

foreach ($e in $script) {
  foreach ($ch in $e.keys.ToCharArray()) {
    $x = switch ($ch.ToString()) {
      "1" { 180 } "2" { 540 } "3" { 900 }
      "4" { 180 } "5" { 540 } "6" { 900 }
      "7" { 180 } "8" { 540 } "9" { 900 }
      "0" { 540 } "." { 180 }
      default { 540 }
    }
    # 键盘行 y: 1-3 行 1918/1975/2033，"." 和 ⌫ 那一行 2090
    $y = switch ($ch.ToString()) {
      "1" { 1918 } "2" { 1918 } "3" { 1918 }
      "4" { 1975 } "5" { 1975 } "6" { 1975 }
      "7" { 2033 } "8" { 2033 } "9" { 2033 }
      default { 2090 }
    }
    Tap $x $y
  }
  Tap $e.cat 550     # 分类 chip
  Tap 540 1790      # 保存
  Tap 964 2215      # 下一笔的 FAB
}

& $Adb shell screencap -p /sdcard/seeded.png | Out-Null
Write-Host "done — 截图见 seeded.png"
