# 安卓独立分发与爱发电购买

从 [Android 0.1.184 Release](https://github.com/hongjiahao371-pixel/kapi/releases/tag/android-v0.1.184) 下载正式 APK 和 SHA-256 校验文件。APK 的包名为 `kapi.ledger`，无需部署 NAS 即可安装使用。

已有 GitHub 正式版可直接覆盖安装。旧内测包名为 `kapi.ledger.debug`，请联系发行者获取对应的覆盖升级包。迁移到独立发行包前，请先保留旧版并导出完整备份；家庭账本使用原 NAS 和原成员身份重新连接。换机前还要在「归账 Pro」中保存购买凭据。

已部署的 NAS 可从「连接与备份 → 下载安卓 App」访问镜像随附的 APK。镜像版本与 GitHub APK 独立更新，最新安卓安装包以此页链接为准。

## 购买与恢复

在安卓 App 打开「设置 → 归账 Pro」，选择月度 ¥6.90（31 天）、年度 ¥69（365 天）或终身 ¥99，核对爱发电结算页金额后付款。月度和年度手动续费。付款后返回归账，由 App 自动核对。单独从爱发电主页赞助的订单无法自动绑定。

在原 App 中使用「保存购买凭据」保存 GZB1 文件，随后在「换机恢复 / 订单帮助」中输入完整凭据恢复。购买凭据应私下保管。若订单长时间未核对到，可在同一页面用原购买凭据配合爱发电订单号核对。基础记账、已有账单、家庭账本访问和数据导出继续可用。

月度或年度 Pro 到期后，已有的个人账本和家庭账本可继续记账、同步和导出。续费核验成功后恢复高级统计等 Pro 功能。

## 发行者订单服务

- `deploy/docker-compose.commerce.yml` 和 `deploy/docker-compose.commerce-live.yml` 用于独立的发行者订单服务。私有配置参考 `deploy/commerce.afdian.env.example`，默认关闭收款。
- 每档使用独立的爱发电方案和 SKU。通过 `COMMERCE_PUBLIC_OFFERS` 显式启用对应的新订单。使用独立 HTTPS 入口转发订单接口，Webhook 路径为 `/v1/commerce/notify/afdian`。API Token、签名私钥和购买凭据只存放在各自的私有环境中。
- 服务端查询平台的实际付款结果，核对方案、金额、SKU、数量和对应订单，再签发权益。返回 App 或收到 Webhook 会触发核对。重复通知和查询复用同一订单。年度和月度按已核验权益计算到期日。
- 发行者可用 `go run ./cmd/kapi-commerce -check-afdian` 做只读连接检查。爱发电公开接口仅推送成功订单，退款需由发行者在平台核实后记录到订单服务。命令参数见 `kapi-commerce -help`。
- 月度已实付核验自动开通和重装恢复。年度、终身已核对正式结算金额，并通过隔离付款结果下的开通、重复核对和重装恢复。年度、终身的真实扣款与实际退款尚未单独验收。

## NAS 部署

镜像 `ghcr.io/hongjiahao371-pixel/kapi-nas:0.5.9` 和 HTTPS、数据持久化配置见 [NAS 部署文档](DEPLOYMENT.md)。独立订单服务使用单独的数据目录。
