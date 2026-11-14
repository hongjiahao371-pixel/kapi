# 归账

安卓可以独立安装，NAS 是可选项。当前安卓正式版为 **0.1.183**，Pro 可从 App 内购买。NAS 下载支持为 **0.5.9**。

- [直接下载 Android 0.1.183 正式 APK](https://github.com/hongjiahao371-pixel/kapi/releases/download/android-v0.1.183/guizhang-android-0.1.183.apk)
- [0.1.183 发布说明与 SHA-256 校验文件](https://github.com/hongjiahao371-pixel/kapi/releases/tag/android-v0.1.183)
- [下载、旧内测迁移与爱发电接入说明](kapi-sync/INDEPENDENT-ANDROID.md)
- [NAS 安装、HTTPS、家庭账本与备份](kapi-sync/DEPLOYMENT.md)
- [NAS 0.5.9 变更与验收边界](kapi-sync/RELEASE-0.5.9.md)

部署带 APK 的镜像后，从 NAS 的 `/android/` 或“连接与备份 → 下载安卓 App”直接下载；也可使用上面的 GitHub 入口。下载不需要账本令牌，账本和管理接口仍需原有鉴权。

镜像标签为 `ghcr.io/hongjiahao371-pixel/kapi-nas:0.5.9`，已完成公开匿名层下载与哈希核验，无需登录；部署资料见 NAS Release，离线导出位于对应 Actions 产物。Docker Hub 现有 `jvsheng/guizhang:0.5.5` 保留。升级前备份，并保留 data、tls、原环境变量和家庭身份。

此仓库公开 NAS 与订单服务源码、配置和已签名安卓二进制。最新安卓客户端以 Release 中的正式 APK 为准。

## 官方爱发电主页

打开安卓 App 的「设置 → 归账 Pro」发起购买。月度 ¥6.90（31 天）、年度 ¥69（365 天）、终身 ¥99。月度和年度手动续费。付款后返回归账，App 会自动核对。建议在 App 内保存购买凭据，换机或重装时可恢复权益。

[归账的爱发电主页](https://afdian.com/a/guizhangapp) 供查看创作者信息。单独在主页赞助的订单不会自动绑定到 App。

## 授权与商业使用

采用 [PolyForm Noncommercial 1.0.0](LICENSE)，允许协议范围内的个人及非商业用途；商业使用需另行书面授权。归账保留官方商业发行和收费 Pro 功能的权利。详见 [授权说明](LICENSING.md) 和 [版权声明](NOTICE)。第三方依赖仍遵循各自协议。
