#!/usr/bin/env pwsh
# End-to-end smoke test against a real kapi-sync process.
#
# Exercises the path that matters and cannot be proven by unit tests alone:
#   pair -> push -> pull -> kill -9 -> restart -> pull -> verify
# The kill -9 in the middle is the point. A clean shutdown proves nothing about
# crash safety, and crash safety is the entire product promise.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root   = Split-Path -Parent $PSScriptRoot
$bin    = Join-Path $root 'kapi-sync.exe'
$tmp    = Join-Path ([System.IO.Path]::GetTempPath()) ("kapi-e2e-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$data   = Join-Path $tmp 'data'
$port   = 18787
$base   = "http://127.0.0.1:$port"

New-Item -ItemType Directory -Force -Path $data | Out-Null
$pass = 0; $fail = 0
function Check($name, $cond, $detail = '') {
  if ($cond) { Write-Host "  PASS  $name" -ForegroundColor Green; $script:pass++ }
  else       { Write-Host "  FAIL  $name  $detail" -ForegroundColor Red; $script:fail++ }
}
function Start-Server {
  $p = Start-Process -FilePath $bin -ArgumentList @('-addr', "127.0.0.1:$port", '-data', $data) `
       -RedirectStandardOutput (Join-Path $tmp 'out.log') -RedirectStandardError (Join-Path $tmp 'err.log') `
       -PassThru -WindowStyle Hidden
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 150
    if ($p.HasExited) { throw "server exited immediately: $(Get-Content (Join-Path $tmp 'err.log') -Raw)" }
    try { $null = Invoke-RestMethod "$base/v1/health" -TimeoutSec 2; return $p } catch {}
  }
  throw "server did not come up; stderr: $(Get-Content (Join-Path $tmp 'err.log') -Raw)"
}

# A leftover process from an interrupted run will hold the port, the new
# server will fail to bind, and the health check will silently succeed against
# the OLD server — which is still holding the previous run's data. That turns
# every assertion into a lie. Refuse to start.
$busy = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
  throw "端口 $port 已被 PID $($busy.OwningProcess) 占用。先结束它再跑本脚本。"
}
function Api($path, $body, $token) {
  $h = @{}
  if ($token) { $h['Authorization'] = "Bearer $token" }
  $p = @{ Uri = "$base$path"; Method = 'POST'; Headers = $h; ContentType = 'application/json; charset=utf-8' }
  if ($null -ne $body) {
    # Windows PowerShell 5.1 sends a [string] body as ANSI unless it is given
    # bytes, which silently turns 早餐 into ??. Convert explicitly.
    $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
  }
  Invoke-RestMethod @p
}

Write-Host "`n== 1. 启动 ==" -ForegroundColor Cyan
$proc = Start-Server
$health = Invoke-RestMethod "$base/v1/health"
Check "health ok" $health.ok
Check "初始 seq = 0" ($health.db_seq -eq 0) "got $($health.db_seq)"

$code = (Invoke-RestMethod "$base/v1/health?pair_code=1").pair_code
Check "配对码是 6 位数字" ($code -match '^\d{6}$') "got '$code'"

Write-Host "`n== 2. 鉴权 ==" -ForegroundColor Cyan
try { Api '/v1/push' @{ changes = @() } 'wrong-token' | Out-Null; Check "错误 token 被拒" $false }
catch { Check "错误 token 被拒" ($_.Exception.Response.StatusCode.value__ -eq 401) }

Write-Host "`n== 3. 配对 ==" -ForegroundColor Cyan
$pair = Api '/v1/pair' @{ pair_code = $code; device_name = 'Pixel 8' } $null
Check "拿到 token" ($pair.token.Length -eq 64) "len=$($pair.token.Length)"
Check "拿到 device_id" ($pair.device_id -like 'dev_*')
$tok = $pair.token
try { Api '/v1/pair' @{ pair_code = '000000'; device_name = 'x' } $null | Out-Null; Check "错误配对码被拒" $false }
catch { Check "错误配对码被拒" ($_.Exception.Response.StatusCode.value__ -eq 403) }

Write-Host "`n== 4. 推送(手机记了两笔)==" -ForegroundColor Cyan
$push1 = Api '/v1/push' @{ device_id = $pair.device_id; changes = @(
  @{ change_id='p1'; entity='txn'; entity_id='t1'; op='upsert'; lamport=10; origin='phone'
     data=@{ amount_minor=3200; note='早餐'; category_id='c_food' }
     fv=@{ amount_minor=@{l=10;d='phone'}; note=@{l=10;d='phone'}; category_id=@{l=10;d='phone'} } },
  @{ change_id='p2'; entity='txn'; entity_id='t2'; op='upsert'; lamport=12; origin='phone'
     data=@{ amount_minor=1580; note='地铁'; category_id='c_transit' }
     fv=@{ amount_minor=@{l=12;d='phone'}; note=@{l=12;d='phone'}; category_id=@{l=12;d='phone'} } }
) } $tok
Check "两笔都被接受" ($push1.applied.Count -eq 2) "got $($push1.applied)"
Check "无冲突" ($push1.rejected.Count -eq 0)
Check "cursor = 2" ($push1.cursor -eq 2) "got $($push1.cursor)"

Write-Host "`n== 5. 幂等:同一批重推 ==" -ForegroundColor Cyan
$push2 = Api '/v1/push' @{ device_id = $pair.device_id; changes = @(
  @{ change_id='p1'; entity='txn'; entity_id='t1'; op='upsert'; lamport=10; origin='phone'
     data=@{ amount_minor=3200; note='早餐'; category_id='c_food' } }
) } $tok
Check "cursor 没有前进" ($push2.cursor -eq 2) "got $($push2.cursor)"
Check "重复项报为 applied" ($push2.applied -contains 'p1')

Write-Host "`n== 6. 第二台设备(平板)改金额 ==" -ForegroundColor Cyan
$code2 = (Invoke-RestMethod "$base/v1/health?pair_code=1").pair_code
$pair2 = Api '/v1/pair' @{ pair_code = $code2; device_name = 'Tablet' } $null
$tok2 = $pair2.token
$push3 = Api '/v1/push' @{ device_id = $pair2.device_id; changes = @(
  @{ change_id='tab1'; entity='txn'; entity_id='t1'; op='upsert'; lamport=14; origin='tablet'
     data=@{ amount_minor=2800 }
     fv=@{ amount_minor=@{l=14;d='tablet'} } }
) } $tok2
Check "平板的修正被接受" ($push3.rejected.Count -eq 0) "got $($push3.rejected | ConvertTo-Json -Compress)"

Write-Host "`n== 7. 增量拉取 ==" -ForegroundColor Cyan
$all = Invoke-RestMethod "$base/v1/changes?since=0&limit=500" -Headers @{ Authorization = "Bearer $tok" }
Check "拉到 3 条变更" ($all.changes.Count -eq 3) "got $($all.changes.Count)"
$page = Invoke-RestMethod "$base/v1/changes?since=0&limit=2" -Headers @{ Authorization = "Bearer $tok" }
Check "分页 limit 生效" ($page.changes.Count -eq 2) "got $($page.changes.Count)"
Check "has_more 正确" ($page.has_more -eq $true)
$tail = Invoke-RestMethod "$base/v1/changes?since=$($page.cursor)&limit=500" -Headers @{ Authorization = "Bearer $tok" }
Check "续拉只剩 1 条" ($tail.changes.Count -eq 1) "got $($tail.changes.Count)"

$row = Invoke-RestMethod "$base/v1/row?entity=txn&id=t1" -Headers @{ Authorization = "Bearer $tok" }
Check "t1 金额是平板的 2800" ($row.values.amount_minor -eq 2800) "got $($row.values.amount_minor)"
Check "t1 备注仍是手机的早餐" ($row.values.note -eq '早餐') "got $($row.values.note)"
Check "t1 分类保留" ($row.values.category_id -eq 'c_food')

Write-Host "`n== 8. 冲突:过期设备覆盖 ==" -ForegroundColor Cyan
$stale = Api '/v1/push' @{ device_id = $pair2.device_id; changes = @(
  @{ change_id='stale1'; entity='txn'; entity_id='t1'; op='upsert'; lamport=5; origin='tablet'
     data=@{ amount_minor=999 }
     fv=@{ amount_minor=@{l=5;d='tablet'} } }
) } $tok2
Check "过期推送被拒" ($stale.rejected.Count -ge 1) "got $($stale.rejected.Count)"
Check "拒绝原因含金额字段" (($stale.rejected[0].fields -contains 'amount_minor'))
$row2 = Invoke-RestMethod "$base/v1/row?entity=txn&id=t1" -Headers @{ Authorization = "Bearer $tok" }
Check "金额未被污染" ($row2.values.amount_minor -eq 2800) "got $($row2.values.amount_minor)"

Write-Host "`n== 9. kill -9 后重启 ==" -ForegroundColor Cyan
Stop-Process -Id $proc.Id -Force
Start-Sleep -Milliseconds 400
$proc = Start-Server
$h2 = Invoke-RestMethod "$base/v1/health"
# seq 4, not 3: the stale push in step 8 lost every field but is still recorded
# in the log, on purpose — it is the audit trail of a rejected edit, and
# dropping it would make the client's cursor arithmetic depend on merge outcomes.
Check "重启后 seq 保持" ($h2.db_seq -eq 4) "got $($h2.db_seq)"
Check "重启后设备数保持" ($h2.device_count -eq 2) "got $($h2.device_count)"
Check "旧 token 重启后仍有效" ($null -ne (Invoke-RestMethod "$base/v1/changes?since=0&limit=1" -Headers @{ Authorization = "Bearer $tok" }))

$row3 = Invoke-RestMethod "$base/v1/row?entity=txn&id=t1" -Headers @{ Authorization = "Bearer $tok" }
Check "重启后金额逐字节一致" ($row3.values.amount_minor -eq 2800) "got $($row3.values.amount_minor)"
Check "重启后中文未乱码" ($row3.values.note -eq '早餐') "got $($row3.values.note)"

Write-Host "`n== 10. 崩溃重放幂等 ==" -ForegroundColor Cyan
$after = Api '/v1/push' @{ device_id = $pair.device_id; changes = @(
  @{ change_id='p1'; entity='txn'; entity_id='t1'; op='upsert'; lamport=10; origin='phone'
     data=@{ amount_minor=3200; note='早餐'; category_id='c_food' } }
) } $tok
Check "崩溃前的推送不会被重复应用" ($after.cursor -eq 4) "got $($after.cursor)"

Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue

Write-Host "`n=== $pass passed, $fail failed ===" -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
exit $(if ($fail -eq 0) { 0 } else { 1 })
