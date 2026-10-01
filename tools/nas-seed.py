"""往 NAS 上的 kapi-sync 灌一批账单，走的是**真实的同步协议**（配对 + push）。

不是直接写文件，也不是调面板自己的接口——只有走一遍 /v1/pair 和 /v1/push，
才能证明"手机同步上来的数据，面板确实读得到"这条链路是通的。

用法: python tools/nas-seed.py <host> <panel-token>
"""
import datetime
import json
import sys
import urllib.request

host = sys.argv[1] if len(sys.argv) > 1 else "192.168.5.10:8787"
base = "http://" + host


def get(path):
    with urllib.request.urlopen(base + path, timeout=15) as r:
        return json.loads(r.read())


def post(path, body, token=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read())


code = get("/v1/health?pair_code=1")["pair_code"]
print("pair code:", code)
paired = post("/v1/pair", {"pair_code": code, "device_name": "NAS-verify"})
dev = paired["device_id"]
print("paired:", dev)

base_t = datetime.datetime(2026, 9, 1, 12, 0, 0)
rows = [
    ("房东", "居住", "9月房租", 450000, "EXPENSE", 1, "工资卡"),
    ("公司", "工资", "9月工资", 1850000, "INCOME", 1, "工资卡"),
    ("盒马鲜生", "日用", "周末采购", 23640, "EXPENSE", 13, "微信"),
    ("国家大剧院", "娱乐", "音乐会票", 38000, "EXPENSE", 28, "支付宝"),
    ("美团外卖", "餐饮", "", 4230, "EXPENSE", 29, "微信"),
    ("京东超市", "购物", "日用补货", 45900, "EXPENSE", 30, "支付宝"),
    ("瑞幸咖啡", "餐饮", "生椰拿铁", 1850, "EXPENSE", 30, "微信"),
    ("滴滴", "交通", "加班打车", 3200, "EXPENSE", 8, "支付宝"),
    ("永辉超市", "日用", "", 15870, "EXPENSE", 7, "微信"),
    ("乐刻运动", "运动", "月卡", 19900, "EXPENSE", 3, "支付宝"),
    ("地铁通勤", "交通", "", 600, "EXPENSE", 2, "支付宝"),
]

changes = []
for i, (merchant, cat, note, amt, direction, day, acct) in enumerate(rows):
    d = {
        "amount_minor": amt,
        "direction": direction,
        "account_id": acct,
        "occurred_at": int((base_t + datetime.timedelta(days=day)).timestamp() * 1000),
        "merchant": merchant,
        "category_id": cat,
    }
    if note:
        d["note"] = note
    changes.append({
        "change_id": "nasc-%d" % i, "entity": "txn", "entity_id": "n%d" % i,
        "op": "upsert", "data": d, "lamport": i + 1, "origin": dev,
    })

res = post("/v1/push", {"device_id": dev, "changes": changes}, paired["token"])
print("applied:", len(res["applied"]), "rejected:", len(res["rejected"]))
print("health:", json.dumps(get("/v1/health"), ensure_ascii=False))
if len(sys.argv) > 2:
    s = get("/v1/panel/summary?token=" + sys.argv[2] + "&from=2026-09-01&to=2026-09-30")
    print("panel stats:", json.dumps(s["stats"], ensure_ascii=False))
