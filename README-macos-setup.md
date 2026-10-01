# 迁移到 macOS

从 Windows 开发机导出的快照。**2026-09-30 23:52** 导出，代码状态 = 微信自动记账三关真机全通的那一版。

---

## 1. 装依赖（Mac 上还没有的话）

| 东西 | 版本 | 怎么确认 |
|---|---|---|
| JDK | **17**（Temurin / Zulu 都行） | `java -version` |
| Android SDK | platform-tools + API 36 build-tools | `adb version` |
| Go | 任意近期版本 | 只在改 `kapi-sync` 时需要 |

导出包里**已经带了**这些，不用重新下：

- `tools/gradle-9.8.0/` —— Gradle 发行版（`bin/gradle` 是 unix 脚本，`bin/gradle.bat` 是 Windows 的，两个都在）
- `tools/m2/` —— 预取的 Maven 依赖（61 MB）

> **`tools/m2` 不能删。** `settings.gradle.kts` 把它排在所有仓库第一位，而 `g.sh` 强制
> `--offline`。原因写在那个文件的注释里：`kotlin-compiler-embeddable` 有 58 MB，
> Gradle 下载稳定超时，而且失败信息永远指向**最后一个**尝试的仓库（Kotlin 插件的
> GitHub release，连不上），看起来像"仓库不可达"，实际是超时。没有它，第一次
> `./g.sh` 就会掉进这个坑。

## 2. 设环境变量

写进 `~/.zshrc`：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

`g.sh` 会自己找 JDK 和 SDK，找不到时会把上面这两行原样打给你。找不到 Gradle 时
它会告诉你去解压 `tools/gradle-9.8.0-bin.zip`（**没放进导出包**，因为解压版就够了，
zip 有 144 MB）。

## 3. 冒烟构建

```bash
cd ~/kapi
chmod +x g.sh
./g.sh -Tasks ":core:test" -Rerun
./g.sh -Tasks ":core:test", ":app:testDebugUnitTest", ":app:assembleDebug" \
       -Expect "app/build/outputs/apk/debug/app-debug.apk" -Rerun
```

跑完应该是 **227 tests, 0 failures**（core 208 + app 19）。

## 4. Windows 专属的东西已经改掉的

| 文件 | 原来 | 现在 |
|---|---|---|
| `g.ps1` | 唯一的构建入口，路径全写死 | 保留但**用不了**，见 `g.sh` |
| `g.sh` | 不存在 | macOS 入口，7 条踩坑教训逐条对过来了 |
| `tools/seed-panel.py` | `ROOT = r"C:\Users\19819\Documents\kapi"` | 从 `__file__` 推 |
| `tools/seed-panel.py` | 硬依赖 `kapi-sync.exe` | 按平台找 `.exe` / `dist/kapi-sync-linux-*` |
| `tools/seed-panel.py` | `creationflags=0x08000000` | 只在 Windows 上传 |

## 5. 没进导出包的东西（都该重建，不该搬）

- `kapi-android/**/build/`、`.gradle/`、`.kotlin/` —— 机器缓存
- `local.properties` —— 里面是 Android SDK 路径，每台机器不一样
- `kapi-sync/kapi-sync.exe` —— Windows 二进制。`dist/` 里的 `kapi-sync-linux-amd64` 和
  `-arm64` 留着了（NAS 就是跑这个）
- `build-raw-*.log` × 19、`build-last.log` —— 构建日志噪声
- `tools/gradle-9.8.0-bin.zip` —— 解压版已在包里
- `tools/mock-llm.exe` —— Windows 二进制（源码 `mock-llm.go` 在）
- `tools/__pycache__/`

**导出包里没有任何密钥。** NAS 密码走环境变量 `NAS_PW`，面板令牌走
`KAPI_PANEL_TOKEN`，两者都只从环境读、不落文件。NAS 上要跑部署脚本时：

```bash
export NAS_PW='...'      # 别写进 ~/.zshrc，profile 可能被同步
export NAS_USER=jvsheng
python3 tools/nas-deploy.py
```

## 6. 目录

```
kapi/
├─ g.sh            ← macOS 构建入口（先 chmod +x）
├─ g.ps1           ← Windows 入口，Mac 上用不了
├─ kapi-android/   ← Android App（Kotlin + Compose，core/app 两个模块）
├─ kapi-sync/      ← NAS 同步服务（Go），dist/ 里有 linux-amd64/arm64
├─ kapi-nas-sync/  ← NAS 部署技术规格草案
├─ tools/          ← m2 依赖、Gradle 发行版、NAS 脚本
├─ ref/            ← 竞品截图
└─ screenshots/    ← 本项目 UI 截图（暗色/亮色/真实数据），继续做 UI 时用
```

## 7. 换机后要重做的

- **`ANDROID_SERIAL` / adb 设备**：新机器重新 `adb devices`
- **手机上的服务**：重装 App 会**禁掉**无障碍服务，而且
  `settings put secure enabled_accessibility_services` 不会自动恢复，必须重写一次，
  紧跟着还要把 `accessibility_enabled` 总开关写回 1。
  实测这台 realme 上 `install -r` 和 `am force-stop` 都会把无障碍整个清空
  （`enabled_accessibility_services` 变 `null`），所以顺序必须是**先装包、后开权限**，
  验证时不要 `force-stop`。通知监听已于 2026-10-01 整条删除，不需要再 `allow_listener`。
- **`adb devices` 连的是新机器**：当前是 realme RMX3852（`5556272f`），
  原来那台 OPPO PMA110（`3B164G0217700000`）还在，接回去就能继续验真机。
