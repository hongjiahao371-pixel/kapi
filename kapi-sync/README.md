# kapi-sync

自托管同步服务端。给一个「数据只存在自己 NAS 上」的记账 App 用的那一半。

客户端（Android）还没写。服务端已经能跑、能扛崩溃、能对账。

---

## 现在能做什么

- 配对换 token，10 分钟有效配对码
- 增量拉取 `GET /v1/changes?since=<seq>`
- 增量推送 `POST /v1/push`
- 字段级冲突合并（Lamport 时钟，不信设备墙上时间）
- 崩溃安全：日志先落盘再改内存，`kill -9` 后重放一致
- 重复推送幂等，超时重试安全
- 零外部依赖，静态二进制，`scratch` 镜像

## 跑起来

```bash
docker run -d --name kapi-sync \
  -v /volume1/docker/kapi-sync:/data \
  -p 8787:8787 \
  kapi-sync
```

没有配置文件，没有必填参数，没有首次启动向导。启动日志里会打配对码和局域网地址。

本地开发：

```bash
go run ./cmd/kapi-sync -addr 127.0.0.1:8787 -data ./data
```

## 测

```bash
go test ./...                     # 单元 + 对抗性测试
powershell -File scripts/e2e.ps1  # 起真进程，跑 kill -9 重启那一套
```

E2E 脚本会先检查端口有没有被占——上一次被中断的残留进程会让新服务绑不上端口，而 health 检查会打到旧服务头上，于是每条断言都在对着一份旧数据说谎。

---

## API

| | |
|---|---|
| `GET /v1/health` | 免鉴权。返回 `db_seq` / `rows` / `device_count`。`?pair_code=1` 附带当前配对码 |
| `POST /v1/pair` | `{pair_code, device_name}` → `{device_id, token, cursor}`。token 只返回这一次 |
| `GET /v1/changes` | `?since=<seq>&limit=<1..1000>` → `{changes, cursor, has_more}` |
| `POST /v1/push` | `{device_id, changes[]}` → `{applied, rejected, cursor}` |
| `GET /v1/row` | `?entity=&id=` 查单行，排查「这条到底谁改的」用 |
| `POST /v1/pair/code` | 需要鉴权。轮换配对码 |

鉴权：`Authorization: Bearer <token>`，除 `/v1/health` 和 `/v1/pair` 外都要。

### 变更格式

```json
{
  "change_id": "phone:t1:10",
  "entity": "txn",
  "entity_id": "t1",
  "op": "upsert",
  "data": { "amount_minor": 3200, "note": "早餐" },
  "fv": {
    "amount_minor": { "l": 10, "d": "phone" },
    "note":         { "l": 10, "d": "phone" }
  },
  "lamport": 10,
  "origin": "phone"
}
```

`fv` 是字段级版本戳，`l` 是 Lamport 计数，`d` 是设备 id。**`lamport` 不传或为 0 的 upsert 会被拒绝。**

没有 `fv` 的老客户端仍然能同步，只是退化成行级粒度（一台设备改任何字段都会覆盖整行）。

---

## 三条不能破的规则

写在代码里，也写在测试里。

### 1. 合并层永远不 import `time`

设备时钟会漂，用户还能手动改。拿墙上时间做「最后写入者胜」，合并结果会静默错乱，且**不报任何错**。`TestMergePackageNeverReadsTheClock` 用 `go/parser` 扫 import 列表强制这一点。

### 2. 墓碑是派生值，不是累积值

**行处于删除态 ⟺ 删除戳严格压过所有内容戳的最大值。**

不是「删了就置一个布尔位」。第一版就是这么写的，然后 720 种乱序测试里有两种顺序给出了不同答案：内容到 lamport 33 的行，墓碑戳还停在 16，于是一个 lamport 30 的删除被判赢并杀掉了它，反过来的顺序却复活了它。同一批变更，两种答案，零报错。

现在 `Deleted` 由 `recomputeState()` 算出来，不存、不累积、不可能漂移。撤销删除也顺带免费了。

### 3. 金额按字节原样透传

`data` 的值是 `json.RawMessage`，不是 `any`。

解进 `map[string]any` 会把所有数字变成 float64，于是 `amount_minor` 重启前是 `int`、重启后是 `float64`——一个持久化数据的静默类型变更，对金额来说正是 schema 明令禁止的浮点污染。`TestValuesRoundTripWithoutContamination` 用超过 2^53 的整数和 `0.1` 把它钉死。

---

## 已知取舍

**全量拒绝的推送也会进日志。** 一次字段全输的推送照样分配 seq 并落盘，所以 seq 增长会比「有效变更数」多。这是故意的——那是审计轨迹，而且如果 seq 只随合并结果走，客户端的游标算术就要依赖合并结果，那是个更糟的耦合。

**`replay()` 一次性读整个日志。** 个人账本几 MB，无所谓；超过 512MB 会打一条警告。压实是 v2 的事。

**`seen_change` 集合只增不减。** 每次改动几个字节，个人用量可忽略。

**传输层没做 TLS。** 局域网内是明文 + bearer token。配对向导里明说适用范围。见设计文档 §7。

## 还没写的

- mDNS 广播发现（配对成功率的关键，见设计文档 §8）
- 二维码配对
- 附件通道（`GET /v1/blob` 的接口留了坑但没实现）
- 冲突的用户可见 UI 决策（服务端已经返回 `rejected`，客户端怎么呈现是另一回事）
- 日志压实

## 文档

`../kapi-nas-sync/DRAFT-tech-spec.md` —— 完整技术方案。
注意文档 §3.2 和 §5 是初稿，**实现已经偏离**：SQLite 换成了 append-only 日志，墓碑规则改成了派生式。文档这两节需要回填。
