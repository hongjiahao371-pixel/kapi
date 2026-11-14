# 归账 NAS 0.5.10

镜像 `jvsheng/guizhang:0.5.10` 支持 `linux/amd64`，在「连接与备份 → 下载安卓 App」和 `/android/` 提供 Android 0.1.184。NAS 会在开放下载前核验发布者签名和 APK 的 SHA-256。安装包和 GitHub Android Release 一致。

[部署说明](DEPLOYMENT.md)提供普通 Compose、HTTPS 叠加配置和 HTTPS 单文件配置。已有部署先备份 `data/`，保留 `.env`、`tls/`、服务名和端口，将现有 Compose 的 `image` 或 `KAPI_IMAGE` 更新为 `jvsheng/guizhang:0.5.10`。在原部署目录执行：

```sh
docker compose pull
docker compose up -d
```

使用多个 `-f` 文件的部署继续使用原来的文件组合。手机沿用原配对和家庭连接。回退可在原配置里恢复原镜像标签，继续挂载同一数据和证书路径。
