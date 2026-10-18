# 归账 NAS 0.5.4 部署

## 运行现成镜像

GitHub Release 附 linux/amd64 镜像压缩包及 SHA256SUMS。校验后 `docker load -i guizhang-nas-0.5.4-linux-amd64.docker.tar.gz`，镜像标签 `kapi-sync:0.5.4`。镜像不含账本、手机配对令牌、面板令牌或 TLS 私钥。

在一个独立部署目录保存以下文件（不要覆盖已有部署）：

```yaml
services:
  kapi-sync:
    image: kapi-sync:0.5.4
    container_name: kapi-sync
    restart: unless-stopped
    ports:
      - "8787:8787"
    volumes:
      - ./data:/data
    environment:
      KAPI_PANEL_TOKEN: "${KAPI_PANEL_TOKEN:-}"
```

启动 `docker compose up -d`，访问 `http://NAS地址:8787/panel/`。自动生成的面板令牌在 `data/panel-token`，通过 NAS 私有终端读取，不要发给其他家庭成员。本人多设备配对和家庭成员邀请不是同一个凭据。

## HTTPS

先准备服务器证书与私钥，可使用受信任域名证书，也可用本项目脚本为自有 NAS 生成 CA：

```sh
sh scripts/tls-init.sh ./tls 192.168.1.10
```

`tls/ca.key` 只能保存在 NAS 上，不挂载给容器。只读挂载 `server.crt` 和 `server.key`，增加监听 `KAPI_TLS_ADDR=0.0.0.0:8789`、`KAPI_TLS_CERT=/run/guizhang-tls/server.crt`、`KAPI_TLS_KEY=/run/guizhang-tls/server.key`，映射局域网地址的 8789。完整叠加文件见 `deploy/docker-compose.https.yml`；复制到部署目录并设置 `KAPI_TLS_BIND` 为你自己的 NAS 局域网 IP 后，使用 `docker compose -f docker-compose.yml -f docker-compose.https.yml up -d`。示例地址不是可用于其他用户的公共服务器。

安卓 0.1.114：设置 → NAS 同步 → 安全传输，导入公开 `ca.crt`，核对可信 NAS 终端提供的 SHA-256 指纹，校验并启用 HTTPS。保留原 HTTP 逻辑地址和配对，实际请求走验证后的 HTTPS，不更换家庭空间身份。校验失败不降回 HTTP。浏览器、iOS、鸿蒙不会自动信任自有 CA，应配置可信证书；不要跳过浏览器证书警告。

HTTP 8787 暂时保留旧设备兼容，尚未迁移设备仍是明文。双端口迁移期间不启用 HSTS，以免浏览器将旧 HTTP 8787 强制改成错误的 HTTPS 8787。不要把这些端口直接映射到公网。

自有 CA 默认10年，服务器证书1年。续期保留同一 CA，再运行脚本并重启服务；更换 CA 必须重新核对设备信任。

## 家庭、备份与回退

- 个人、每位成员的私有空间、家庭共享空间各自使用独立令牌、游标和日志。家庭成员平等，每人只能修改自己录入的家庭账单；服务器文件管理员仍必须可信。
- 创建家庭不会自动分享旧个人记录。不要把本人设备配对码当家庭邀请码。
- 完整迁移必须停机备份整个 `data/`、`.env`、`tls/` 和 Compose。单个空间 ZIP 不包含全部家庭及 TLS 配置。
- 升级不应清库、重新配对或改空间 URL。回退镜像与运行配置时保留当前数据，避免覆盖新写入。
- 新版禁止 URL 中放 token/access_token/api_key；面板使用请求头令牌与浏览器会话存储；认证失败按真实来源限流。旧日志及旧备份中的凭据不会被升级自动擦除。

## 从源码构建

仓库根目录：`docker build -t kapi-sync:0.5.4 kapi-sync`。依赖官方 Go、Gradle/JDK 与 Temurin 基础镜像；读取器仅在导入时启动，不常驻运行。独立构建入口包含源文件摘要与第三方许可。当前发布的现成镜像只验收 linux/amd64；不要将可交叉编译等同于 ARM NAS 真机验收。
