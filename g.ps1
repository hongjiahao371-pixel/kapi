# 固定构建/测试入口。之前踩过的坑都在这里封掉：
# 1. $ErrorActionPreference='Stop' 遇到 gradle 写 stderr 会在重定向完成前抛 NativeCommandError，
#    结果日志是空的、错误被吞成一行 `gradle.bat :`。这里在调用期间临时降成 Continue。
# 2. `| Select-String` 之类的管道会让 $LASTEXITCODE 变 0，Gradle 明明 BUILD FAILED 也报绿。
#    所以日志一律先落盘，再用单独的 Select-String 读，退出码只认 $LASTEXITCODE。
# 3. 少了这里的 Set-Location，gradle 会在 workspace 目录里找 settings.gradle.kts 然后报
#    "Directory ... does not contain a Gradle build"，而那句话和真实原因长得不像。
# 4. 设备测试结果 XML 在**编译失败时不会更新**，目录里躺着上一轮的报告。调用方必须
#    核对 mtime，否则会把上一轮的数据当成本轮结论。
# 5. **不要从外面把输出重定向到 build-last.log。** 脚本内部第 27 行要写同一个文件，
#    外层 `*>` 先抢占了句柄 → 内部 Out-File 抛 IOException → 整条管道被掐断 →
#    gradle 被中途杀掉、APK 根本没重新生成，而 $LASTEXITCODE 是**空的**，
#    `exit $code` 退化成 exit 0。表现就是"改了源码、编译报成功、装到手机上代码没变"，
#    白查两小时。日志改用带时间戳的独立文件，从物理上杜绝抢句柄。
# 6. **光看退出码不够，必须校验产物。** 加 -Expect <路径>，构建后检查它的 mtime
#    是否新于构建开始时间——Gradle 因为缓存判定 up-to-date 而没重编时，退出码照样是 0。
# 7. 任务名必须用 **-Tasks 显式传**，不要写位置参数。Gradle 的 task path 以
#    ":" 开头（:core:test），PowerShell 的位置参数绑定会把裸的 ":core:test"
#    错配到 -Expect 上，报出来的是
#    "FAIL: 产物不存在 ...\:core:test"——一个和真实问题毫无关系的错误，
#    而 gradle 根本没被调用过。写成 -Tasks ":core:test" 就不会有这层歧义。
# 用法：
#   .\g.ps1 -Tasks ":core:test"
#   .\g.ps1 -Tasks ":app:assembleDebug" -Expect "app\build\outputs\apk\debug\app-debug.apk"
param(
    [string]$Expect = "",
    [string[]]$Tasks = @(),
    [switch]$Rerun,
    # 原样透传给 gradle 的参数。设备测试要往 instrumentation 传服务端地址：
    #   -GradleArgs "-Pandroid.testInstrumentationRunnerArguments.baseUrl=http://192.168.5.10:8787"
    [string[]]$GradleArgs = @()
)

$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "$env:USERPROFILE\AppData\Local\Android\Sdk"
$gradle = "C:\Users\19819\Documents\kapi\tools\gradle-9.8.0\bin\gradle.bat"
$root = "C:\Users\19819\Documents\kapi"
# PowerShell 里脚本和调用方**共享**会话状态，所以裸 Set-Location 会把调用方的
# 当前目录也一起改掉。表现是：连续跑两次 g.ps1，第二次 $PWD 已经在
# kapi-android 里，&$PWD\g.ps1 找不到文件，第二次构建根本没跑。
# 于是必须 Push/Pop 成对。
Push-Location "$root\kapi-android"

$log = "$root\build-last.log"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$raw = "$root\build-raw-$stamp.log"
# 任务名以 ":" 开头（Gradle 的 task path 语法），PowerShell 的位置参数绑定
# 会把它们错配到 -Expect 上，于是 "g.ps1 :core:test" 报的是
# "产物不存在 ...\:core:test" —— 一个和真实问题毫无关系的错误。
# 只认显式的 -Tasks，不做位置参数兜底，宁可让人多打六个字符。
if ($Tasks.Count -eq 0) { $Tasks = @("assembleDebug") }
$Tasks = @($Tasks | Where-Object { $_ -and $_ -notmatch '^-' })
if ($Tasks.Count -eq 0) { $Tasks = @("assembleDebug") }
$argv = @($Tasks) + @($GradleArgs) + @("--offline", "--console=plain")
# -Rerun 透传 --rerun-tasks。没有它的时候 Gradle 会因为缓存判定测试任务
# UP-TO-DATE、**根本不执行测试**，而退出码照样是 0：于是"117 个测试全绿"
# 可能是三天前那一次的报告。核对 tests 计数和 mtime 是必要的，但重跑一次更省事。
if ($Rerun) { $argv += "--rerun-tasks" }

$t0 = Get-Date
& $gradle @argv *>&1 | Out-File -FilePath $raw -Encoding UTF8
$code = $LASTEXITCODE
Pop-Location
# 原始日志单独留一份；build-last.log 是给人看的摘要副本。
Copy-Item $raw $log -Force

if ([string]::IsNullOrWhiteSpace("$code")) {
    Write-Output "FAIL: gradle 没有返回退出码（管道被中断，构建多半没跑完）"
    Write-Output "raw=$raw"
    Get-Content $raw -Encoding UTF8 | Select-Object -Last 40
    exit 1
}

if ($code -ne 0) {
    Write-Output "--------- 失败，尾部 60 行 ---------"
    Get-Content $raw -Encoding UTF8 | Select-Object -Last 60
}

# 产物校验：编译"成功"但产物没更新，等于没编译。
if ($Expect -ne "") {
    $p = $Expect
    if (-not [System.IO.Path]::IsPathRooted($p)) { $p = Join-Path "$root\kapi-android" $Expect }
    if (-not (Test-Path $p)) {
        Write-Output "FAIL: 产物不存在 $p"
        exit 1
    }
    $mt = (Get-Item $p).LastWriteTime
    if ($mt -lt $t0) {
        Write-Output ("FAIL: 产物比构建还旧，Gradle 判定 up-to-date 没重编。`n" +
                      "  产物 mtime = $mt`n  构建开始 = $t0`n  产物 = $p")
        exit 1
    }
    Write-Output "产物已更新: $p  ($mt)"
}

Write-Output "exit=$code  log=$raw"
exit $code
