# 归账

安卓可以独立安装，NAS 是可选项。当前独立分发准备版为 **Android 0.1.172**，NAS 下载支持为 **0.5.9**；购买功能保持关闭，内测已有权益保留。

- [安卓 APK 下载与校验文件](https://github.com/hongjiahao371-pixel/kapi/releases/tag/android-v0.1.172)
- [下载、旧内测迁移与爱发电接入说明](kapi-sync/INDEPENDENT-ANDROID.md)
- [NAS 安装、HTTPS、家庭账本与备份](kapi-sync/DEPLOYMENT.md)
- [NAS 0.5.9 变更与验收边界](kapi-sync/RELEASE-0.5.9.md)

部署带 APK 的镜像后，从 NAS 的 `/android/` 或“连接与备份 → 下载安卓 App”直接下载；也可使用上面的 GitHub 入口。下载不需要账本令牌，账本和管理接口仍需原有鉴权。

镜像标签为 `ghcr.io/hongjiahao371-pixel/kapi-nas:0.5.9`，已完成公开匿名层下载与哈希核验，无需登录；部署资料见 NAS Release，离线导出位于对应 Actions 产物。Docker Hub 现有 `jvsheng/guizhang:0.5.5` 保留。升级前备份，并保留 data、tls、原环境变量和家庭身份。

此仓库公开 NAS 与订单服务源码、配置和已签名安卓二进制。保留的早期手机客户端源码不对应最新 APK；iOS、鸿蒙不属于本次独立分发验收。

## 官方爱发电主页

[归账的爱发电主页](https://afdian.com/a/guizhangapp) 由本项目发行者维护，用于创作者身份认证和后续 Pro 购买。新定价草稿为月度 6.90 元、年度 69 元、终身 99 元；当前商品隐藏且不可购买，App 收款关闭。月度／年度有效期、续期及真实交易尚未完成，不属于现有 APK 或镜像已验收的能力。请等 App 正式开放后的订单入口，不要单独赞助后期待自动激活。内测已有权益保持不变。

## 授权与商业使用

采用 [PolyForm Noncommercial 1.0.0](LICENSE)，允许协议范围内的个人及非商业用途；商业使用需另行书面授权。归账保留官方商业发行和收费 Pro 功能的权利。详见 [授权说明](LICENSING.md) 和 [版权声明](NOTICE)。第三方依赖仍遵循各自协议。
