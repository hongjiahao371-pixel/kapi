"""本地起一份 kapi-sync 并灌入演示账单，用来看面板。

只在开发机上跑，用来验证面板的真实渲染；NAS 上跑的是同一份二进制，不带这些数据。
"""
import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

# 项目根从**本文件的位置**推出来，不写死绝对路径。
# 原来写的是 r"C:\Users\19819\Documents\kapi"，换台机器就找不到文件了——
# 而且失败方式是「No such file or directory」，完全看不出是路径写死的问题。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def find_binary():
    """Windows 用 .exe，macOS/Linux 用仓库里现成的 linux-* 二进制或本地构建产物。"""
    src = os.path.join(ROOT, "kapi-sync")
    ext = ".exe" if platform.system() == "Windows" else ""
    for name in ("kapi-sync" + ext, "dist/kapi-sync-linux-amd64", "dist/kapi-sync-linux-arm64"):
        p = os.path.join(src, name)
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    raise SystemExit(
        "找不到可执行的 kapi-sync。先构建：cd kapi-sync && go build -o kapi-sync ./cmd/kapi-sync"
    )


EXE = find_binary()
DATA = os.path.join(ROOT, "panel-dev-data")
ADDR = "127.0.0.1:8799"
BASE = "http://" + ADDR
TOKEN = "devtoken"
PANEL = "devpanel123"

if os.path.isdir(DATA):
    shutil.rmtree(DATA)
os.makedirs(DATA, exist_ok=True)

# creationflags 是 Windows 独有的关键字，在 macOS/Linux 上直接 TypeError。
popen_kw = {}
if platform.system() == "Windows":
    popen_kw["creationflags"] = 0x08000000  # CREATE_NO_WINDOW

env = dict(os.environ, KAPI_PANEL_TOKEN=PANEL)
proc = subprocess.Popen([EXE, "-addr", ADDR, "-data", DATA], env=env,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        **popen_kw)


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=10) as r:
        return json.loads(r.read())


def post(path, body, token=None):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


for _ in range(60):
    try:
        get("/v1/health")
        break
    except Exception:
        time.sleep(0.3)
else:
    print("server did not start")
    sys.exit(1)

code = get("/v1/health?pair_code=1")["pair_code"]
paired = post("/v1/pair", {"pair_code": code, "device_name": "panel-dev"})
dev = paired["device_id"]
print("paired", dev, "cursor", paired["cursor"])

import datetime
base = datetime.datetime(2026, 9, 1, 12, 0, 0)


def ms(d):
    return int(d.timestamp() * 1000)


rows = [
    ("星巴克", "瑞幸咖啡", "生椰拿铁", "餐饮", 1800, "EXPENSE", 3, "微信", 0),
    ("地铁通勤", "地铁", "", "交通", 600, "EXPENSE", 5, "支付宝", 1),
    ("盒马鲜生", "盒马鲜生", "周末采购", "日用", 23640, "EXPENSE", 12, "微信", 0),
    ("国家大剧院", "大麦", "音乐会票", "娱乐", 38000, "EXPENSE", 27, "支付宝", 0),
    ("美团外卖", "美团", "", "餐饮", 4230, "EXPENSE", 28, "微信", 0),
    ("京东超市", "京东", "日用补货", "购物", 45900, "EXPENSE", 30, "支付宝", 0),
    ("瑞幸咖啡", "瑞幸", "下午茶", "餐饮", 1850, "EXPENSE", 31, "微信", 0),
    ("公司", "工资", "9月工资", "工资", 1850000, "INCOME", 1, "工资卡", 0),
    ("房东", "房租", "9月房租", "居住", 450000, "EXPENSE", 1, "工资卡", 0),
    ("滴滴", "滴滴出行", "加班打车", "交通", 3200, "EXPENSE", 8, "支付宝", 0),
    ("超市", "永辉", "", "日用", 15870, "EXPENSE", 7, "微信", 0),
    ("健身房", "乐刻", "月卡", "运动", 19900, "EXPENSE", 2, "支付宝", 0),
]

changes = []
for i, (merchant, cat, note, _c, amt, direction, day, acct, deleted) in enumerate(rows):
    d = {
        "amount_minor": amt, "direction": direction,
        "account_id": acct, "occurred_at": ms(base + datetime.timedelta(days=day)),
    }
    if note:
        d["note"] = note
    if deleted:
        changes.append({"change_id": "c%d" % i, "entity": "txn", "entity_id": "d%d" % i,
                        "op": "delete", "data": {}, "lamport": 100 + i, "origin": dev})
        continue
    d["merchant"] = merchant
    d["category_id"] = cat
    changes.append({"change_id": "c%d" % i, "entity": "txn", "entity_id": "t%d" % i,
                    "op": "upsert", "data": d, "lamport": i + 1, "origin": dev})

res = post("/v1/push", {"device_id": dev, "changes": changes}, paired["token"])
print("push applied", len(res["applied"]), "rejected", len(res["rejected"]))

h = get("/v1/health")
print("health:", json.dumps(h, ensure_ascii=False))
s = get("/v1/panel/summary?token=%s" % PANEL)
print("stats:", json.dumps(s["stats"], ensure_ascii=False))
print("panel token:", PANEL)
print("PID", proc.pid)
with open(os.path.join(ROOT, "panel-dev.pid"), "w") as f:
    f.write(str(proc.pid))
