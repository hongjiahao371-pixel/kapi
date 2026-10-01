#!/usr/bin/env python3
"""清空 NAS 上的 kapi-sync 测试数据。

只删 ./data 目录下的 log.jsonl 和 meta.json——那是这个服务全部的状态，
没有别的地方藏数据。容器和镜像都保留，所以 compose 的 .env（面板令牌）
不用重来。

【为什么需要它】NAS 上原来是 nas-seed.py 灌的合成数据，其中 E2E 测试
故意推了一笔 1000 万元的支出。面板是给人看真实账单的，留着这些行只会
让人以为 NAS 里已经有钱了。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nas import run  # noqa: E402

DEPLOY_DIR = "/volume1/docker/kapi-sync"


def sh(cmd):
    """跑一条命令，打印输出，返回 (rc, stdout)。"""
    _cmd, rc, out, err = run([cmd])[0]
    if out:
        for line in out.splitlines():
            print("  " + line)
    if err:
        for line in err.splitlines()[:6]:
            print("  ! " + line)
    return rc, out


def main() -> int:
    # 停容器再删：log.jsonl 是 append-only 的，正在被写的时候删掉它，
    # 重建出来的 meta.json 快照会指向一个已经不存在的日志尾部。
    print("== stop ==")
    sh(f"cd {DEPLOY_DIR} && docker compose stop kapi-sync")

    print("== wipe data ==")
    rc, out = sh(
        f"cd {DEPLOY_DIR} && ls -l data/ ; "
        f"rm -f data/log.jsonl data/meta.json && echo WIPED ; ls -la data/"
    )
    if "WIPED" not in out:
        print("!! 数据目录没被清空，中止（不重启容器）")
        return 1

    print("== start ==")
    sh(f"cd {DEPLOY_DIR} && docker compose start kapi-sync")

    print("== wait for health ==")
    for i in range(30):
        time.sleep(1)
        _c, out = sh("curl -s --max-time 3 http://127.0.0.1:8787/v1/health || true")
        if '"ok":true' in out:
            print(f"  ready after {i + 1}s")
            return 0
    print("!! 30 秒内没起来")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
