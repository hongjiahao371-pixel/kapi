# NAS 无界面账单读取器

此目录是与 Android 共用的纯 Kotlin 解析核心的发布快照，不包含手机 UI、APK、账本或服务商配置。
`SOURCE-MANIFEST.json` 记录源文件路径和内容摘要，更新 NAS 读取器时应重新从主工程导出并核对摘要，不要分别维护两套解析逻辑。

需要 Java 17、Gradle 9.8.0：`gradle --no-daemon jar`。完整镜像的 Dockerfile 会自行构建，无需 Android SDK。

依赖许可和 JExcelAPI LGPL 源码位于 licenses/；镜像包含这些许可。JExcelAPI 不曾修改。其余依赖由 Maven Central 获取。
