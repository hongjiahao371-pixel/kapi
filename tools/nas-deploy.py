"""把 kapi-sync 部署到 NAS：交叉编译 → 上传 → docker compose 起 → 真机验证。

设计成幂等：重复跑不会起两份容器，也不会清掉已有数据。数据目录只在**第一次**
部署时创建。

密码只从环境变量 NAS_PW 读，不写进任何文件、不拼进命令行。
"""
import os
import posixpath
import subprocess
import sys
import time

import paramiko

ROOT = r"C:\Users\19819\Documents\kapi"
SRC = os.path.join(ROOT, "kapi-sync")
DEPLOY = os.path.join(SRC, "deploy")
DIST = os.path.join(SRC, "dist")
VERSION = "0.2.0"

HOST = os.environ.get("NAS_HOST", "192.168.5.10")
PORT = int(os.environ.get("NAS_PORT", "22"))
USER = os.environ.get("NAS_USER", "jvsheng")
PW = os.environ.get("NAS_PW", "")

REMOTE_DIR = "/volume1/docker/kapi-sync"
# 绿联 UGOS 的 /home/jvsheng/.docker 是 root 所有并且带 ACL，普通用户在里面
# 建不了 buildx 子目录，`docker build` 会报
# "open /home/jvsheng/.docker/buildx/current: permission denied"；
# 紧接着 compose 会以为镜像没建好，转去 Docker Hub 拉，而这台 NAS 外网不通，
# 于是失败信息变成一个和真实原因毫无关系的 timeout。把 DOCKER_CONFIG 挪到
# 部署目录下面就绕开了。
DOCKER_ENV = "DOCKER_CONFIG=" + REMOTE_DIR + "/.docker"


def sh(args, cwd=None):
    print("+", " ".join(args))
    r = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr, file=sys.stderr)
        raise SystemExit("build failed: " + " ".join(args))
    return r.stdout


def build_linux():
    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, "kapi-sync-linux-amd64")
    # CGO_ENABLED=0 产出静态二进制，scratch 镜像里不需要任何 libc。
    # -X 把版本号打进二进制，NAS 上 `docker logs` 看到的和本机跑的是同一个值。
    env = dict(os.environ,
               CGO_ENABLED="0", GOOS="linux", GOARCH="amd64",
               GOFLAGS="-trimpath")
    print("+ cross build linux/amd64")
    r = subprocess.run(
        ["go", "build", "-ldflags", "-s -w -X kapi/sync/internal/store.Version=" + VERSION,
         "-o", out, "./cmd/kapi-sync"],
        cwd=SRC, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout, r.stderr, file=sys.stderr)
        raise SystemExit("cross build failed")
    print("  ->", out, os.path.getsize(out), "bytes")
    return out


def conn():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, port=PORT, username=USER, password=PW, timeout=30,
              allow_agent=False, look_for_keys=False)
    return c


def run(c, cmd, timeout=180, sudo=False):
    if sudo:
        cmd = "sudo -S -p '' " + cmd
    si, so, se = c.exec_command(cmd, timeout=timeout)
    if sudo:
        si.write(PW + "\n")
        si.flush()
    rc = so.channel.recv_exit_status()
    return rc, so.read().decode("utf-8", "replace").strip(), se.read().decode("utf-8", "replace").strip()


def upload(c, local, remote):
    """把文件送到 NAS。

    不用 SFTP：绿联 UGOS 的 sshd 把 SFTP 子系统关在 chroot 里，SFTP 侧看到的
    绝对路径和普通 shell 看到的不是一套，对 /volume1/... 写文件一律 ENOENT，
    而同一条路径用 `touch` 走 exec 是成功的——这会让人以为是自己 mkdir 漏了，
    其实完全不是。所以这里直接开一条 `cat > 文件` 的 exec 通道把字节喂进去。
    """
    si, so, se = c.exec_command("cat > %s" % shq(remote), timeout=300)
    with open(local, "rb") as f:
        while True:
            chunk = f.read(256 * 1024)
            if not chunk:
                break
            si.write(chunk)
    si.channel.shutdown_write()
    rc = so.channel.recv_exit_status()
    err = se.read().decode("utf-8", "replace").strip()
    if rc != 0:
        raise SystemExit("upload %s failed rc=%d %s" % (remote, rc, err))
    print("  %-16s %8d bytes" % (os.path.basename(remote), os.path.getsize(local)))


def shq(p):
    return "'" + p.replace("'", "'\\''") + "'"


def main():
    if not PW:
        raise SystemExit("NAS_PW 没有设置")

    # 部署前先确认本地这一份是过的
    print("== go test ==")
    sh(["go", "test", "./..."], cwd=SRC)
    print("== go vet ==")
    sh(["go", "vet", "./..."], cwd=SRC)

    binary = build_linux()

    c = conn()
    print("== connect %s ==" % HOST)

    rc, o, e = run(c, "uname -m; docker --version; docker compose version")
    print(o)
    if rc != 0:
        print(e, file=sys.stderr)

    # 建目录。-p 幂等；data 只在不存在时建，docker 也不会去动已有内容。
    for d in (REMOTE_DIR, posixpath.join(REMOTE_DIR, "data")):
        rc, o, e = run(c, "mkdir -p %s" % shq(d))
        if rc != 0:
            raise SystemExit("mkdir %s failed: %s" % (d, e))

    print("== upload ==")
    upload(c, binary, posixpath.join(REMOTE_DIR, "kapi-sync"))
    run(c, "chmod 755 %s" % shq(posixpath.join(REMOTE_DIR, "kapi-sync")))
    for f in ("Dockerfile", "docker-compose.yml"):
        upload(c, os.path.join(DEPLOY, f), posixpath.join(REMOTE_DIR, f))

    # 面板令牌默认每次启动随机生成，打进日志。不写死的话，**每次容器重启**浏览器里
    # 存的令牌就失效，用户得重新去翻 docker logs —— 而"我什么都没改，怎么突然
    # 进不去了"是这类产品最常见的差评。所以第一次部署时生成一个固定的写进 .env，
    # 之后 compose 会一直用它。
    env_path = posixpath.join(REMOTE_DIR, ".env")
    rc, o, e = run(c, "test -f %s && echo EXISTS || echo MISSING" % shq(env_path))
    if "EXISTS" not in o:
        token = os.urandom(16).hex()
        si, so, se = c.exec_command("cat > %s" % shq(env_path), timeout=60)
        si.write("# 面板访问令牌。删掉这一行 = 每次启动随机生成，浏览器里存的令牌会失效。\n"
                 "KAPI_PANEL_TOKEN=%s\n" % token)
        si.channel.shutdown_write()
        so.channel.recv_exit_status()
        run(c, "chmod 600 %s" % shq(env_path))
        print("  生成固定面板令牌 %s" % token)
    else:
        print("  沿用已有 .env")

    # 上传完先在 NAS 上验一下二进制本身能不能跑。scratch 镜像里没有 shell，
    # 镜像起不来时很难区分是二进制坏了还是镜像配错了，这一步把两种情况分开。
    print("== binary sanity on NAS ==")
    rc, o, e = run(c, "%s -version" % posixpath.join(REMOTE_DIR, "kapi-sync"), timeout=60)
    print("  rc=%d out=%s err=%s" % (rc, o, e))
    if rc != 0:
        raise SystemExit("binary does not run on NAS")

    print("== build image ==")
    rc, o, e = run(c, "cd %s && %s docker compose build 2>&1 | tail -20" % (REMOTE_DIR, DOCKER_ENV), timeout=600)
    print(o)
    if rc != 0:
        print(e, file=sys.stderr)
        raise SystemExit("docker build failed")

    print("== up ==")
    rc, o, e = run(c, "cd %s && %s docker compose up -d 2>&1 | tail -20" % (REMOTE_DIR, DOCKER_ENV), timeout=300)
    print(o)
    if rc != 0:
        print(e, file=sys.stderr)
        raise SystemExit("compose up failed")

    # 起容器到能应答 /v1/health 之间给足时间：scratch 镜像启动很快，
    # 但 NAS 第一次拉起容器会有几秒开销。
    print("== wait for health ==")
    ok = False
    for i in range(40):
        time.sleep(1.5)
        rc, o, e = run(c, "curl -s -m 3 http://127.0.0.1:8787/v1/health", timeout=15)
        if rc == 0 and o.startswith("{"):
            print("  after %.1fs: %s" % ((i + 1) * 1.5, o))
            ok = True
            break
    if not ok:
        print("== logs ==")
        rc, o, e = run(c, "docker logs --tail 40 kapi-sync 2>&1", timeout=60)
        print(o, e)
        raise SystemExit("health check failed")

    print("== banner ==")
    rc, o, e = run(c, "docker logs kapi-sync 2>&1 | head -20", timeout=60)
    print(o)

    print("== panel reachable from LAN ==")
    rc, o, e = run(c, "curl -s -o /dev/null -w '%%{http_code}' -m 5 http://127.0.0.1:8787/panel/")
    print("  GET /panel/ ->", o)
    rc, o, e = run(c, "curl -s -m 5 'http://127.0.0.1:8787/v1/panel/summary'")
    print("  未带令牌 ->", o[:160])

    rc, o, e = run(c, "docker ps --filter name=kapi-sync --format '{{.Names}} {{.Status}} {{.Ports}}'")
    print("== container ==")
    print(o)
    # 把实际生效的令牌打出来，省得再去翻 docker logs
    rc, o, e = run(c, "grep -h KAPI_PANEL_TOKEN %s 2>/dev/null | cut -d= -f2" % shq(env_path))
    if o.strip():
        print("PANEL_TOKEN=%s" % o.strip())
    c.close()
    print("OK")


if __name__ == "__main__":
    main()
