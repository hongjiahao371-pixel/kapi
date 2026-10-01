"""NAS SSH 探查。密码走环境变量 NAS_PW,不落在源码里。

sudo 用 stdin 送密码,不把密码拼进命令行——命令行会进 ps 输出和 shell 历史。
"""
import os
import sys
import paramiko

HOST = os.environ.get("NAS_HOST", "192.168.5.10")
PORT = int(os.environ.get("NAS_PORT", "22"))
USER = os.environ.get("NAS_USER", "jvsheng")
PW = os.environ.get("NAS_PW", "")


def _exec(c, cmd, timeout, use_sudo):
    if use_sudo:
        # sudo -S 从 stdin 读密码,-p '' 去掉提示符
        cmd = "sudo -S -p '' " + cmd
    si, so, se = c.exec_command(cmd, timeout=timeout)
    if use_sudo:
        si.write(PW + "\n")
        si.flush()
    rc = so.channel.recv_exit_status()
    return rc, so.read().decode("utf-8", "replace"), se.read().decode("utf-8", "replace")


def run(cmds, timeout=90, use_sudo=False):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, port=PORT, username=USER, password=PW, timeout=timeout,
              allow_agent=False, look_for_keys=False)
    out = []
    for cmd in cmds:
        try:
            rc, o, e = _exec(c, cmd, timeout, use_sudo)
        except Exception as ex:
            rc, o, e = -1, "", f"{type(ex).__name__}: {ex}"
        out.append((cmd, rc, o.strip(), e.strip()))
    c.close()
    return out


def show(cmds, timeout=90, use_sudo=False):
    for cmd, rc, o, e in run(cmds, timeout, use_sudo):
        print(f"$ {cmd}")
        print(f"  [rc={rc}]")
        for line in o.splitlines():
            print("  " + line)
        if e:
            for line in e.splitlines()[:8]:
                print("  ! " + line)
        print()


if __name__ == "__main__":
    args = sys.argv[1:]
    as_sudo = False
    if args and args[0] == "--sudo":
        as_sudo = True
        args = args[1:]
    show(args or ["id", "hostname"], use_sudo=as_sudo)
