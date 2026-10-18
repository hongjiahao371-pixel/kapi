# 归账

本轮更新先发布 **NAS / Docker 0.5.4**。Android 安装包由内测组织者单独分发，iOS 与鸿蒙尚不在本次 GitHub 发布的验收范围内。仓库仍保留早期客户端源码，不能把它们当成本轮手机安装包对应版本。

- [NAS 功能与接口](kapi-sync/README.md)
- [安装、HTTPS、家庭账本与备份](kapi-sync/DEPLOYMENT.md)
- [Docker 0.5.4 发布说明](kapi-sync/RELEASE-0.5.4.md)

Docker Hub 公开镜像为 [`jvsheng/guizhang:0.5.4`](https://hub.docker.com/r/jvsheng/guizhang)，无需登录即可拉取。离线镜像与校验文件也位于 GitHub Releases。请先备份已有数据，按指南升级；源码、镜像和示例配置不含任何人的账本或可用令牌。

## 授权与商业使用

采用 [PolyForm Noncommercial 1.0.0](LICENSE)，允许协议范围内的个人及非商业用途；商业使用需另行书面授权。归账保留官方商业发行和收费 Pro 功能的权利。详见 [授权说明](LICENSING.md) 和 [版权声明](NOTICE)。第三方依赖仍遵循各自协议。
