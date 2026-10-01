#!/usr/bin/env bash
# 固定构建/测试入口（macOS / Linux）。g.ps1 的等价物，踩过的坑逐条对过来了：
#
#  1. **不要从外面把输出重定向到 build-last.log。** 脚本内部要写同一个文件，
#     外层 `> build-last.log` 先抢占了句柄 → 内部写日志失败 → 整条管道被掐断 →
#     gradle 被中途杀掉、APK 根本没重新生成，而退出码是**空的**、脚本退化成 exit 0。
#     表现就是"改了源码、编译报成功、装到手机上代码没变"。日志用带时间戳的独立文件，
#     从物理上杜绝抢句柄。
#  2. **光看退出码不够，必须校验产物。** 用 -Expect <路径>，构建后检查它的 mtime
#     是否新于构建开始时间——Gradle 因为缓存判定 up-to-date 而没重编时，退出码照样是 0。
#     这一条不是洁癖：g.ps1 那边的退出码曾经因为管道被打断而**是空的**，
#     `exit $code` 退化成 `exit 0`。
#  3. **设备测试结果 XML 在编译失败时不会更新**，目录里躺着上一轮的报告。调用方必须
#     核对 mtime，否则会把上一轮的数据当成本轮结论。
#  4. **必须 cd 到 kapi-android。** 不然 gradle 会在当前目录找 settings.gradle.kts
#     然后报 "Directory ... does not contain a Gradle build"，那句话和真实原因长得不像。
#  5. **-Rerun 透传 --rerun-tasks。** 没有它的时候 Gradle 会因为缓存判定测试任务
#     UP-TO-DATE、**根本不执行测试**，而退出码照样是 0：于是"227 个测试全绿"
#     可能是三天前那一次的报告。核对计数和 mtime 是必要的，但重跑一次更省事。
#  6. **--offline 改成按需开启（2026-10-01）。** 原来是写死的，理由是依赖全预取在
#     tools/m2。但在 macOS 这台机器上实测 tools/m2 **只有 5 个 Kotlin 2.4.20 的 jar**，
#     没有 AGP / AndroidX / Compose，版本还和要求的 Kotlin 2.3.21 对不上——带着
#     --offline 必然死在 `Plugin [id: 'com.android.application' ...] was not found`。
#     本机网络快（repo1 1.3s / dl.google 0.6s），改联网后 :core:test 208 tests 全绿。
#     真需要离线时加 -Offline（前提是先补全 tools/m2）。详见下方 ARGV 处的注释。
#  7. **任务名必须用 -Tasks 显式传，不要写位置参数。** Gradle 的 task path 以 ":" 开头，
#     bash 的位置参数绑定同样会歧义。
#  8. **空数组展开必须写 `${arr[@]+"${arr[@]}"}`。** macOS 的 /bin/bash 是 3.2，
#     `set -u` 下展开空数组会直接退出（bash 4.4 才修）。见下方 ARGV 处。
#  9. **建议 export GRADLE_USER_HOME="$PWD/.gradle-home"（别用 ~/.gradle）。**
#     2026-10-01 实测 ~/.gradle/caches 在构建中途被外部清掉（3.9 GB 消失），
#     daemon 随即留下 "Daemon registry ... became unreadable. Expiring daemon."，
#     所有 execution worker 停在 Object.wait() 死等——表现是编译卡住不动，
#     但 CPU 占用 0.8%，用 `sample`/`jstack` 能看到线程全空闲。别误判成"编译慢"。
#
# 用法：
#   ./g.sh -Tasks ":core:test"
#   ./g.sh -Tasks ":app:assembleDebug" -Expect "app/build/outputs/apk/debug/app-debug.apk"
#   ./g.sh -Tasks ":core:test" -Rerun
#   ./g.sh -Tasks ":core:test" -Offline        # 仅在确认 tools/m2 已补全时用
set -uo pipefail

EXPECT=""
RERUN=0
OFFLINE=0
TASKS=()
GRADLE_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    -Expect)     EXPECT="$2"; shift 2 ;;
    -Rerun)      RERUN=1; shift ;;
    -Offline)    OFFLINE=1; shift ;;
    -Tasks)      shift; while [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; do TASKS+=("$1"); shift; done ;;
    -GradleArgs) shift; while [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; do GRADLE_ARGS+=("$1"); shift; done ;;
    *)           GRADLE_ARGS+=("$1"); shift ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"

# ---- JDK 17：优先用环境变量，其次 /usr/libexec/java_home（macOS 自带），最后 PATH 里的 java
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then
    JH="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -n "$JH" ] && export JAVA_HOME="$JH"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "$JAVA_HOME" ]; then
  echo "FAIL: 找不到 JDK 17。装 Temurin 17 或 Zulu 17，然后："
  echo "      export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"
  exit 1
fi

# ---- Android SDK：ANDROID_HOME / ANDROID_SDK_ROOT / macOS 默认位置
#
# 候选条件是 `[ -d "$cand" ] || [ -L "$cand" ]` 而不是单 `[ -d ]`：
# 断掉的符号链接对 `[ -d ]` 返回 false，于是外置盘没挂载时 ANDROID_HOME 压根不会被设上，
# 后面拿到的 $SDK 是空串，"断链"和"SDK 不存在"就混成同一条消息了。多认一个 `-L`
# 是为了让下面那段能拿到**路径本身**，从而把两者分开报。
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for cand in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/share/android-sdk"; do
    { [ -d "$cand" ] || [ -L "$cand" ]; } && export ANDROID_HOME="$cand" && break
  done
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

# ---- 断链检测：SDK 现在是**指向外置盘的符号链接**（2026-10-01 迁移）
#
# 必须放在下面那个"找不到 SDK"的判断**之前**。`[ -d ]` 对断掉的符号链接返回 false，
# 所以顺序反过来就成了：断链 → 先命中"找不到 Android SDK" → 打印"装 Android Studio
# 或 commandline-tools" → 人真的会去重装一个根本没坏的 SDK。SDK 文件好好地在外置盘上，
# 只是盘没挂。
#
# 两种情况的处置完全不同，所以要先分辨：
#   链接在、目标不在  = 外置盘没挂载
#   链接都不在        = SDK 真的没了
if [ -n "$SDK" ] && [ -L "$SDK" ] && [ ! -d "$SDK" ]; then
  echo "FAIL: Android SDK 是个断掉的符号链接 —— 外置盘没挂载。"
  echo "      $SDK -> $(readlink "$SDK" 2>/dev/null)"
  echo "      插上外置盘，或手动挂载："
  echo "        diskutil mount UUID 44B83C49-C5AC-4D8D-B22D-EDAB7A5D509E"
  echo "      确认：ls $SDK/platform-tools/adb"
  exit 1
fi

if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "FAIL: 找不到 Android SDK。装 Android Studio 或 commandline-tools，然后："
  echo "      export ANDROID_HOME=\$HOME/Library/Android/sdk"
  echo "      还需要 platform-tools（adb）和 API 36 的 build-tools。"
  exit 1
fi

# 挂载了也要确认内容真的在。挂载点存在但卷是空的（少见的坏盘状态），
# 那样 platform-tools 不存在，报错会退化成 gradle 自己那句难懂的报错。
if [ ! -x "$SDK/platform-tools/adb" ]; then
  echo "FAIL: $SDK 存在但里面没有 platform-tools/adb —— 卷挂上了，内容不对。"
  echo "      可能是外置盘没插紧，或者挂到了错误的卷。"
  exit 1
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

# ---- Gradle：用仓库自带的那份解压发行版（tools/gradle-9.8.0/bin/gradle）
# 项目**没有 gradle wrapper**，所以这份是唯一入口。发行版里同时有 bin/gradle(unix)
# 和 bin/gradle.bat(windows)，跨平台通用。
GRADLE="$ROOT/tools/gradle-9.8.0/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  echo "FAIL: 缺 $GRADLE"
  echo "      解压 tools/gradle-9.8.0-bin.zip 到 tools/ 下，或直接编辑本文件里的 GRADLE 变量。"
  exit 1
fi
chmod +x "$GRADLE" 2>/dev/null || true

if [ ${#TASKS[@]} -eq 0 ]; then TASKS=("assembleDebug"); fi

LOG="$ROOT/build-last.log"
STAMP="$(date +%Y%m%d-%H%M%S)"
RAW="$ROOT/build-raw-$STAMP.log"

# 空数组的展开写法在 bash 3.2 下会炸。
#
# macOS 自带的 /bin/bash 是 3.2.57，而 `${arr[@]}` 在数组为空时按 `set -u` 判定为
# "unbound variable" 直接退出（bash 4.4 才把空数组当成合法展开）。所以
# `ARGV=("${TASKS[@]}" "${GRADLE_ARGS[@]}" ...)` 在不传任何额外参数时——也就是
# 最常见的 `./g.sh -Tasks ":core:test"`——会在这一行以
# `GRADLE_ARGS[@]: unbound variable` 死掉，一条 gradle 都没跑。
#
# `${arr[@]+"${arr[@]}"}` 是这个问题的标准解法：数组为空时整个展开消失，
# 非空时行为和 `"${arr[@]}"` 完全一样。TASKS 同理（虽然上面保证它非空，
# 但别的地方也可能展开它）。
ARGV=("${TASKS[@]+"${TASKS[@]}"}" "${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}" "--console=plain")

# --offline 改成**按需开启**，不再是默认。
#
# 原来是写死的 --offline，理由是 tools/m2 预取了全部依赖。但 2026-10-01 在这台
# Mac 上实测：tools/m2 里**只有 5 个 jar**（org.jetbrains.kotlin:*:2.4.20），
# 没有 AGP、没有 AndroidX、没有 Compose，版本还和 build.gradle.kts 要求的
# Kotlin 2.3.21 对不上。带着 --offline 必然死在
# `Plugin [id: 'com.android.application', version: '9.4.1'] was not found`。
#
# 也就是说"预取依赖避开下载超时"这个前提在这台机器上根本不成立，而 --offline
# 把唯一能走通的路堵死了。实测本机网络很快（repo1.maven.org 1.3s、
# dl.google.com 0.6s），联网解析 11 分钟就把冷缓存填满，之后 `:core:test`
# 208 tests 全绿。
#
# 仍然保留 -Offline 开关：真遇到网络不通的场合（比如出差、机场）还能切回去，
# 前提是先把 tools/m2 补全。
[ "$OFFLINE" = "1" ] && ARGV+=("--offline")

[ "$RERUN" = "1" ] && ARGV+=("--rerun-tasks")

T0="$(date +%s)"

# 注意：必须 cd 进 kapi-android，而且**不能**让外层重定向抢 build-last.log。
cd "$ROOT/kapi-android" || exit 1
"$GRADLE" "${ARGV[@]}" > "$RAW" 2>&1
CODE=$?
cd "$ROOT" || exit 1

cp -f "$RAW" "$LOG"

if [ "$CODE" -ne 0 ]; then
  echo "--------- 失败，尾部 60 行 ---------"
  tail -n 60 "$RAW"
fi

# 产物校验：编译"成功"但产物没更新，等于没编译。
if [ -n "$EXPECT" ]; then
  case "$EXPECT" in /*) P="$EXPECT" ;; *) P="$ROOT/kapi-android/$EXPECT" ;; esac
  if [ ! -e "$P" ]; then
    echo "FAIL: 产物不存在 $P"
    exit 1
  fi
  MT="$(stat -f %m "$P" 2>/dev/null || stat -c %Y "$P")"
  MT_HUMAN="$(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$P" 2>/dev/null || date -r "$MT" '+%Y-%m-%d %H:%M:%S')"
  if [ "$MT" -lt "$T0" ]; then
    echo "FAIL: 产物比构建还旧，Gradle 判定 up-to-date 没重编。"
    echo "  产物 mtime = $MT_HUMAN"
    echo "  构建开始   = $(date -r "$T0" '+%Y-%m-%d %H:%M:%S')"
    echo "  产物       = $P"
    echo "  上加 -Rerun 重来一次。"
    exit 1
  fi
  echo "产物已更新: $P  ($MT_HUMAN)"
fi

echo "exit=$CODE  log=$RAW"
exit $CODE
