#!/usr/bin/env bash
# 可读性探针：拉起一个页面 → 等 kapi 无障碍服务读屏 → 打印判定结论。
#
# 用法: tools/probe-a11y.sh <package> <activity> [等待秒数=6]
# 依赖: adb 在 PATH；kapi.ledger.debug 的无障碍服务已启用并绑定。
#
# 判读：
#   「N 个节点」N≥10            → 该 App 向无障碍暴露界面（读屏可用）
#   「1 个节点，没有任何金额」反复 → 该 App 不暴露（realme 微信 10-01 的现象）
set -uo pipefail
PKG="${1:?用法: probe-a11y.sh <package> <activity> [秒]}"
ACT="${2:?用法: probe-a11y.sh <package> <activity> [秒]}"
WAIT="${3:-6}"
ADB="$(command -v adb || echo ~/Library/Android/sdk/platform-tools/adb)"

"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/$ACT" >/dev/null || { echo "拉起失败：$PKG/$ACT"; exit 1; }
sleep "$WAIT"

echo "== $PKG 判定行（最近 6 条）=="
"$ADB" logcat -d -s KapiA11y 2>/dev/null | grep -vE "systemui|inputmethod" | tail -6
N=$("$ADB" logcat -d -s KapiA11y 2>/dev/null | grep -oE "[0-9]+ 个节点" | tail -1 | grep -oE "[0-9]+" || echo 0)
echo "== 结论 =="
if [ "${N:-0}" -ge 10 ]; then
  echo "可读：读到 $N 个节点，读屏路线可用"
else
  echo "不可读：最多读到 ${N:-0} 个节点（若反复出现 1 节点 = App 不向无障碍暴露界面）"
fi
