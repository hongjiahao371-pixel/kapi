# 自托管记账 App —— 技术方案 (DRAFT)

> 状态:服务端**已实现并通过端到端验证**(29/29,含 `kill -9` 重启)。客户端未开工。
> 实现位置:`../kapi-sync/`,测试:`go test ./...` + `scripts/e2e.ps1`。
>
> **本文档已与实现对齐的三处偏离,都标了「实现已偏离初稿」:**
> §3.2 SQLite → append-only 日志 + 内存索引
> §5.3 墓碑从累积式改为派生式
> §5.4(新增)字段值按字节透传,不用 `any`
>
> 每一处偏离的原因都是实现过程中撞到了具体问题,不是重新讨论了方向。

---

## 0. 配置与结论

| 决策项 | 选择 | 关键收益 |
|---|---|---|
| 平台 | Android 优先 | 无证书重签、后台任务可控、通知栏/悬浮球可用 |
| 分发 | GitHub 侧载(小圈子) | 不必适配 99% 无 NAS 的大众 |
| 同步 | 离线写 → 回家批量推拉 | 无需常驻连接、无需组网、无需公网暴露 NAS |

**这个组合的价值**:同步从"实时性"问题降维成"最终一致"问题。工程量约为实时方案的三分之一,且能提供良好的用户体验。

**代价(需在产品里明说)**:多设备之间存在同步延迟;NAS 不可用时 App 仍完全可用。

---

## 1. 技术选型

**客户端**
- Kotlin + Jetpack Compose
- Room(SQLite)—— 本地库是 App 内唯一真相源
- WorkManager —— 同步调度(网络约束由系统保证)
- Hilt 或手写 DI(v1 可先手写,别过度设计)

**服务端(NAS 上)**
- **Go**,编译为单静态二进制
- 理由:15MB 单文件、零运行时依赖、交叉编译干净、`scratch` 基础镜像即可、`net/http` + `modernc.org/sqlite`(纯 Go,无 cgo)或直接 `mattn/go-sqlite3`(交叉编译麻烦,建议前者)
- 单容器,挂载一个目录:`/data` (SQLite + 附件)

**为什么不用 WebDAV**:WebDAV 在移动端性能差、只能整文件读写、无法做增量拉取。弱网下体验差一个级别。

---

## 2. 架构

```
┌─────────────── Android App ────────────────┐
│  UI (Compose)                              │
│  ─────────────────────────────────         │
│  Domain / UseCase                          │
│  ─────────────────────────────────         │
│  Room (SQLite)  ◄── 唯一真相源, 永远可读    │
│    ├── txn / ledger / category / account   │
│    ├── outbox       (待推送变更)            │
│    ├── cursor       (last_seq)             │
│    └── lamport_counter                      │
│  ─────────────────────────────────         │
│  SyncEngine (纯 Kotlin, 可 JVM 单测)        │
└──────────────┬──────────────────────────────┘
               │  HTTPS + Bearer Token
               │  触发:WorkManager / 手动 / 启动 / 回前台
┌──────────────▼──────────────────────────────┐
│  NAS · 同步服务 (单容器, Go)                │
│    REST API  +  SQLite(权威库, 单写者)       │
│    附件静态目录 /data/blobs/<shard>/<id>    │
└─────────────────────────────────────────────┘
```

**铁律**

1. **读永远走本地**,永不因网络阻塞 UI。
2. **写永远是本地事务**,先落库 + 写 outbox,不依赖网络。
3. **NAS 是唯一权威**,客户端是缓存。这砍掉了 CRDT / 多主冲突的复杂度。
4. **不要用 Syncthing 同步 SQLite 文件**。两个设备同时改 → 数据库损坏且不报错,三天后才发现。同步的必须是**行级事件**,不是文件字节。

---

## 3. 数据模型

客户端与服务端**共用同一份 schema**(服务端启动时自动 migrate,客户端打包同版本 DDL)。

### 3.1 核心表

```sql
-- 金额一律用「分」为单位的整数。任何情况下不得用 REAL/FLOAT 存钱。
-- 方向:正数 = 收入,负数 = 支出。避免额外 direction 字段带来的不一致。

CREATE TABLE ledger (
  id          TEXT PRIMARY KEY,   -- UUIDv7(时间有序,索引友好)
  name        TEXT NOT NULL,
  type        TEXT NOT NULL,      -- 'personal' | 'shared'
  created_at  INTEGER NOT NULL,   -- epoch ms
  updated_at  INTEGER NOT NULL,
  deleted_at  INTEGER,            -- 墓碑,不做物理删除
  lamport     INTEGER NOT NULL
);

CREATE TABLE account (
  id            TEXT PRIMARY KEY,
  ledger_id     TEXT NOT NULL,
  name          TEXT NOT NULL,
  type          TEXT NOT NULL,    -- cash/debit/credit/investment/loan
  balance_minor INTEGER NOT NULL DEFAULT 0,
  currency      TEXT NOT NULL DEFAULT 'CNY',
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted_at    INTEGER,
  lamport       INTEGER NOT NULL
);

CREATE TABLE category (
  id          TEXT PRIMARY KEY,
  ledger_id   TEXT NOT NULL,
  parent_id   TEXT,
  name        TEXT NOT NULL,
  icon        TEXT,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  deleted_at  INTEGER,
  lamport     INTEGER NOT NULL
);

CREATE TABLE txn (
  id               TEXT PRIMARY KEY,
  ledger_id        TEXT NOT NULL,
  amount_minor     INTEGER NOT NULL,   -- 分为单位,正收入负支出
  account_id       TEXT NOT NULL,
  to_account_id    TEXT,               -- 转账的转入账户
  category_id      TEXT,
  occurred_at      INTEGER NOT NULL,   -- 业务发生时间(用户可编辑)
  note             TEXT,
  merchant         TEXT,
  tags             TEXT,               -- JSON array
  attachment_count INTEGER NOT NULL DEFAULT 0,  -- 冗余计数
  -- ── 同步元数据 ──
  fv               TEXT NOT NULL,      -- 字段级 Lamport: {"amount":{"l":42,"d":"a1b2"}, ...}
  lamport          INTEGER NOT NULL,
  deleted_at       INTEGER,
  synced           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_txn_occurred ON txn(occurred_at DESC);
CREATE INDEX idx_txn_synced   ON txn(synced, lamport);
```

### 3.2 服务端存储

**实现已偏离初稿:不用 SQLite,改用 append-only 日志 + 内存索引。**

原因:这个服务端对持久化数据只做四件事——追加一条变更、读一行、读日志尾部、查某个 `change_id` 有没有见过。前三件在内存里是 O(1)/O(n) 切片操作,一个个人账本几万行也就几 MB。数据库引擎会换来 CGO 或 30MB 纯 Go 依赖、一套 schema 迁移、一套损坏文件恢复流程,而这些全都换不来任何东西。

**目录布局**

```
/data/
  log.jsonl    追加写的合并后快照,一行一个 JSON
  meta.json    设备列表(只存 token 哈希)+ 当前配对码
```

**log.jsonl 每行**

```json
{
  "seq": 128374,
  "entity": "txn",
  "entity_id": "0192...",
  "op": "upsert",
  "change_id": "phone:t1:10",
  "origin": "phone",
  "created_at": 1759260000000,
  "snapshot": { }
}
```

**关键决策:日志存合并后的快照,不存原始变更。** 于是重放就是「每行取最后一个快照」,`/changes` 返回的也正好是客户端要的东西,重启时完全不需要再跑一遍合并逻辑。

**落盘顺序:整批 `Write` + `fsync` 之后才改内存。** 日志因此永远不落后于内存——崩溃可以丢内存状态,但丢不了已提交状态。客户端在崩溃后重试,`change_id` 去重,既不丢也不重复。

**撕裂写 vs. 损坏,判据是结尾有没有换行符。** 崩溃发生在 `Write` 中途,留下的最后一行必然缺换行(换行符就在被截断的那次写里),这是撕裂写的唯一形态。而一个以换行结尾的文件说明每次写都完成了,里面任何解析不了的行都是真损坏,必须报错。

> 初稿把「最后一行解析失败」一律当撕裂写丢弃,结果是每次启动都静默截断真正的损坏日志,并丢掉坏行之后的所有变更,且不报任何错。已修正,并有 `TestCorruptionBeforeFinalLineIsReported` 守着。

**`change_id` 去重表**在内存里,启动时从日志重建。全丢的推送**照样分配 seq 并落盘**——那是审计轨迹,而且如果 seq 只随合并结果走,客户端的游标算术就要依赖合并结果,那是个更糟的耦合。

**已知边界**:整份日志一次性读入(>512MB 告警),`seen` 集合只增不减。压实是 v2 的事。

---

## 4. 同步协议

### 4.1 配对

用户先在 NAS 上通过管理页(或 `docker logs`)拿到一个 6 位配对码,App 内输入或扫码。

```
POST /v1/pair
  { "pair_code": "123456", "device_name": "Pixel 8" }
→ 200 { "device_id": "dev_a1b2", "token": "<64 hex>", "server_time": 1759..., "cursor": 0 }
→ 409 { "error": "pair_code_invalid" }
```

Token 只在配对时返回一次,服务端只存 `sha256(token)`。App 存入 EncryptedSharedPreferences。

### 4.2 健康检查 / 连接自检

```
GET /v1/health
→ 200 { "ok": true, "server_version": "0.1.0", "db_seq": 128374, "device_count": 2 }
```

**这个接口是配对体验的关键**,不是可选项。App 配对页要能一键跑通「能否解析 NAS 地址 → 端口是否通 → 证书是否 OK → token 是否有效 → pair_code 是否正确」五步,并给出**人话错误**。见 §8。

### 4.3 拉取增量

```
GET /v1/changes?since=<seq>&limit=500
→ 200 {
    "changes": [
      { "seq": 128301, "entity":"txn", "entity_id":"0192...", "op":"upsert",
        "payload":{ /* 合并后的完整行快照 */ }, "origin_device":"dev_b3" }
    ],
    "cursor": "128301",
    "has_more": true
  }
```

### 4.4 推送变更

```
POST /v1/push
  { "device_id":"dev_a1b2",
    "changes":[ { "change_id":"<uuid>", "entity":"txn", "entity_id":"0192...",
                  "op":"upsert", "data":{...}, "fv":{...}, "lamport":120 } ] }
→ 200 {
    "applied":  ["<change_id>", ... ],
    "rejected": [ { "change_id":"...", "reason":"conflict",
                    "server_data":{...} } ],   // v1 极少触发,仅用于诊断
    "cursor":   "128400"
  }
```

**幂等**:服务端先查 `seen_change`,命中则直接返回 applied,不做业务处理。这让「客户端超时重试」和「用户手动重复点同步」都变得安全。

### 4.5 同步顺序:**必须先拉后推**

```
1. GET /v1/changes?since=cursor        → 合并到本地;lamport 吸收远端最大值
2. lamport_counter = max(所有已知 lamport) + 1   // 抬高基准
3. POST /v1/push (outbox 全量)
4. cursor = 响应中的 cursor
5. outbox 标记 synced=1(在 3 成功之后才清)
```

为什么不能反:先推的话,你推上去的变更会被广播回来,拉取时又拿一遍(浪费但无害);更关键的是,你没吸收远端 lamport 就推送,自己的 lamport 可能小于已知的远端变更,合并结果不可预测。

**为什么 outbox 在推送成功后才清**:同步失败必须能重试。如果先清后推,断网就丢数据——这是最经典的丢账 bug。

### 4.6 附件(独立通道,默认关闭)

**账单列表接口永远不返回附件内容或元数据列表**,只返回 `attachment_count` 这个冗余整数。否则一张 5 万笔的账单表会把附件元数据也拉下来。

```
GET /v1/blob/<txn_id>/<index>   → 图片字节(带 Cache-Control: immutable)
```

建议策略(v1):**附件默认不同步**,设置里独立开关;开启后仅同步最近 30 天的。这是存储和带宽的黑洞,别混进主通道。

---

## 5. 冲突合并

### 5.1 用 Lamport 时钟,不信任设备墙上时钟

这是最容易埋雷的地方。`occurred_at`(业务时间)是用户可编辑的;如果你拿设备墙上时钟做「最后写入者胜」,用户改了手机时间、系统时钟漂移,合并结果直接错乱,而且**没有报错**。

方案:每个设备维护 `lamport_counter`

- 本地每次修改:`lamport_counter++`,写入该行
- 收到远端变更:`lamport_counter = max(lamport_counter, remote_lamport)`
- 比较:高 `lamport` 胜;相等时按 `device_id` 字典序决定(保证全序,合并结果与到达顺序无关)

### 5.2 字段级 LWW

`fv` 存 `{字段: {"l": lamport, "d": device_id}}`。合并某一字段时,取 `fv` 中 lamport 更大的那个值。

好处:设备 B 只是**只读**或只改了备注,不会跟设备 A 改的金额打架。绝大多数情况零冲突,用户无感。

### 5.3 删改冲突:墓碑是派生值

**实现已偏离初稿。初稿写的是「冲突时以删除为准」,那是个会漂移的状态。**

正确的不变式只有一条:

> **行处于删除态 ⟺ 删除戳严格压过所有内容戳的最大值**

`Deleted` 由 `recomputeState()` 算出来,**不存、不累积**。于是:

- 删除后有更晚的编辑 → 编辑戳压过墓碑戳 → **自动复活**(撤销删除因此免费)
- 编辑后有更晚的删除 → 墓碑戳压过内容戳 → 存活不了
- 离线设备对着已删除的行发来陈旧编辑 → 编辑戳更低 → 墓碑赢,行保持删除;但**该编辑的字段值仍然被合并进去**,用户再改一次时数据没丢

这条规则把「复活」和「杀死」从两段代码压成一个比较,也让 `DelFV` 回到它该有的身份:一个**历史事实**(那次删除操作发生在第几号),而不是指向当前状态的指针。

> 初稿的写法是「删除赢了才推进 DelFV」。于是内容已经到 lamport 33 的行,墓碑戳还停在 16,一个 lamport 30 的删除被判赢并杀掉了它;而同一批变更换个到达顺序,33 复活了它。**同一批变更,两种答案,零报错。** 是 720 种乱序收敛测试抓出来的,不是读代码看出来的。回归守卫:`TestContentNewerThanTombstoneWins`。

### 5.4 值按字节透传

`data` 的字段值类型是 `json.RawMessage`,不是 `any`。

解进 `map[string]any` 会让所有数字变成 float64,于是 `amount_minor` **重启前是 `int`、重启后是 `float64`**——一个持久化数据的静默类型变更,而且恰好是 §3 开头明令禁止的浮点污染。客户端任何 `.(int)` 断言在重启后都会炸,而且不重启时一切正常。

服务端根本不解释这些值,只负责合并戳和转发字节,所以原样保留就是正解,顺带还省掉了转换。测试用超过 2^53 的整数和 `0.1` 把这条钉死:`TestValuesRoundTripWithoutContamination`。

---

## 6. 同步触发时机

| 场景 | 机制 |
|---|---|
| **回家连上 WiFi(主路径)** | `WorkManager` + `Constraints(NetworkType.UNMETERED)`,并注册 `NetworkCallback` 在 WiFi 可用时 `enqueueUniqueWork("sync", KEEP)`。这是**主力**,占日常同步 90% 以上 |
| App 启动 / 回前台 | 立即跑一次(不设约束) |
| 用户手动下拉 | 立即跑一次,带进度反馈 |
| 记账后 5 分钟 | 延迟任务,顺手推一把(在外面也能同步,如果网络可用) |

`enqueueUniqueWork` 保证不会并发跑出两个同步。

---

## 7. 安全模型

**不追求端到端加密,明说。** 理由:账单要支持服务端统计/搜索/分类,这就要求服务端能解密,与 E2EE 互斥。做一个"看起来很安全实际不是"的产品,用户查一次 App Store 隐私标签就全崩了。

实际措施:

1. **传输**:HTTPS。NAS 侧用内网自签证书,App 内置**证书固定(pinning)** —— 但自签证书每台 NAS 不同,所以改为:首次配对时读取服务端公钥,后续用该公钥做应用层加密(等价于自定义 TOFU)。
2. **局域网 ≠ 可信**。App 默认**不信任**任何网络,配对前所有请求走明文 + token(仅限局域网,文档中明示)。
3. **鉴权**:每设备一个 token,服务端只存 `sha256`。支持吊销单设备。
4. **Token 存储**:Android EncryptedSharedPreferences(Keystore 支撑)。
5. **不采集**:不上报任何账单数据到外部。要做崩溃收集就自建端点,或干脆先不做。

---

## 8. 配对体验 —— 这才是真正会杀死项目的东西

不是同步协议。**任何一个普通用户输错 IP、搞不懂端口映射、在自签证书上卡住,他就在这一步永久流失,而且不会再给第二次机会。**

必须做到的:

1. **首次启动不要求配 NAS**。所有记账功能本地完整可用,配对是可选的进阶设置,可以在「设置 → 同步」里随时做。**绝不能出现一个空白的「请先连接服务器」首屏。**
2. **NAS 侧零配置启动**。`docker run -v ~/kapi:/data -p 8787:8787 <image>` 即可,不能要求写配置文件。容器启动时自己在 `/data` 建库、打印配对码。
3. **连接自检向导**,五步逐条显示,每条给可执行的下一步:

   | 步骤 | 失败时给的话 |
   |---|---|
   | 解析地址 | 「这个地址看起来不像网址,试试 `192.168.1.10:8787`」 |
   | TCP 连通 | 「连不上。请确认:① NAS 是否开机 ② 端口映射是否生效(执行 `docker ps` 看 PORTS 列) ③ 手机和 NAS 是否在同一个 WiFi」 |
   | HTTP 握手 | 「连上了但没有响应。检查容器是否崩溃:`docker logs <容器名>`」 |
   | 鉴权 | 「token 无效,重新配对一次」 |
   | 配对码 | 「配对码是 6 位数字,在 NAS 日志或管理页能看到,默认 10 分钟过期」 |

4. **局域网地址智能探测**:mDNS / UDP 广播广播一个 `kapi-sync/v1` beacon,让 App 在「添加服务器」时能自动发现同网段的 NAS,用户不用输 IP。**这一条能把配对成功率从一半拉到九成。**
5. **二维码配对**:NAS 管理页显示一个含 `{host, port, pair_code}` 的二维码,App 相机扫一下就配好。给非技术用户的主路径。

---

## 9. 部署与分发

**NAS 镜像**
- 多架构 `linux/amd64,linux/arm64`(NAS 常见的群晖 DS、威联通、瑞芯微、飞牛、绿联)
- 基础镜像 `scratch` 或 `gcr.io/distroless/static`,只放一个静态二进制
- `VOLUME /data`,非 root 运行

**Android 分发**
- GitHub Actions 构建,签名密钥存 repo Secrets
- Release 页放 APK + `update.json`(`{version, url, sha256, notes, mandatory}`)
- App 内「检查更新」读该 JSON;建议走应用内下载 + 引导安装(需要 `REQUEST_INSTALL_PACKAGES` 权限及对应处理)
- 崩溃收集:v1 建议**先不做**。小圈子用户会自己反馈,引入 Sentry 等等于引入一个必须自己运维的服务

**iOS 后续(不在 v1)**
- 侧载需处理 15 天证书重签,建议配 `AltStore`/Sideloadly 流程
- 数据层、同步协议、冲突规则**完全复用**,只重写平台适配层
- 注意:那时才需要处理本地网络权限弹窗问题,建议届时强制走 Tailscale 隧道

---

## 10. v1 范围

**做**

- 手动记账(金额、分类、账户、日期、备注、商户、标签)
- 分类树(可自定义二级)
- 多账本(仅个人账本;**共享账本不在 v1**)
- 基础统计(月度收支、分类占比、时间趋势)
- 账单搜索(性能要留够余量,见 §11)
- NAS 同步(配对 + 增量推拉 + 冲突合并)
- 手动导出(SQLite 文件 / CSV)—— 逃生通道,不可省

**不做(明确推迟)**

- 共享账本多人协作(冲突模型完全不同,需要成员权限与 CRDT)
- 银行卡/信用卡账单自动抓取
- 无障碍自动记账、通知栏读取、悬浮球(失败率高、无法远程调试,极易造成「第一次失败即永久流失」)
- 语音记账、截图识别(可作为 v2 的独立模块,识别层与同步层解耦)
- AI 分类、AI 报告
- 复杂借贷关系、报销、退款

---

## 11. 已知坑清单

来自同类产品的真实用户反馈,提前规避:

1. **搜索/历史列表加载慢** —— 有同类 App 因为这个被用户直接骂「不如以前」。本地库要建好索引,查询放后台线程,首屏只渲染当月。
2. **时间戳错误** —— 凌晨 2 点的订单被标成当日 22:22。`occurred_at` 的默认值必须取**用户当前时间**,不是「今天 00:00」或某个固定时刻;且编辑后要立刻重算到正确的位置。
3. **自动分类不准** —— 如果 v1 做规则分类,宁可少分类目也不要错分类。错分类比不分类更伤信任。
4. **无二次确认** —— 如果 v2 做自动识别,必须给一个**可撤销的短窗口**(建议 3 秒,竞品一木/飞鸭都这么做),不要直接静默入库。
5. **账单导入重叠** —— 支付宝按固定周期导入会与已有数据重叠,且重叠项删除会提示「未查询到」。若 v2 做导入,必须做去重。
6. **更新后性能回退** —— 新版本在 8/27、8/29、9/1 连续三次都只写「优化记账速度」。说明性能是持续问题,不是一次性任务。**把搜索和列表查询的耗时写进 CI 基准测试。**
7. **登录态丢失** —— 提醒用户:本 App 无账号体系,数据在本地 + NAS,换设备靠同步,不要设计「退出登录」这种会让人误以为数据没了的入口。
8. **升级破坏数据** —— 每次 schema 迁移必须写往返测试:`Room 迁移测试`(官方 `MigrationTestHelper`)+ 服务端自动 migrate 的同一份 DDL。v1 阶段没有真实用户数据的时候就把迁移测试搭好,上线后再补是来不及的。

---

## 12. 里程碑建议

| 阶段 | 产出 | 判据 |
|---|---|---|
| M0 | 本地 App:手动记账 + 分类 + 统计 + 导出 | 单机用户能用它记满一个月 |
| M1 | NAS 服务:建库 + 配对 + 增删改查 API | `curl` 能完成配对与推拉 |
| M2 | SyncEngine + WorkManager 触发 + 冲突合并 | 断网 3 天后能完整补同步,数据零丢失、零重复 |
| M3 | 配对体验打磨 + mDNS 发现 + 二维码配对 | **10 个不懂技术的用户,8 个能在 3 分钟内配对成功** |
| M4 | 小圈子发放 + 收集反馈 | 3 个以上用户连续使用超过 30 天 |

M3 的判据不是「做完了」,是那个百分比。M0 和 M1 可以并行——服务端是纯 Go,写起来很快,不影响客户端迭代节奏。
