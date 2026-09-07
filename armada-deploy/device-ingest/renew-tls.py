#!/usr/bin/env python3
"""通过 443 续期设备入口证书；仅暂停指定 Compose 项目的专用网关。"""
import fcntl
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys


def run(*args):
    """捕获子进程输出，避免证书工具的详细信息进入 journal。"""
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=60).stdout.strip()


def restore(config):
    """恢复本次续期实际暂停的网关；systemd ExecStopPost 也调用此入口。"""
    marker = Path(config["acmePath"]) / "paused-containers.json"
    if not marker.exists():
        return
    for container in json.loads(marker.read_text()):
        labels = json.loads(run("docker", "inspect", "--format", "{{json .Config.Labels}}", container))
        if (labels.get("com.docker.compose.project") != config["composeProject"]
                or labels.get("com.docker.compose.service") != "device-ingest-nginx"):
            raise RuntimeError("REFUSE_TO_START_UNRELATED_CONTAINER")
        run("docker", "start", container)
    marker.unlink()


def pause(config):
    """先记录恢复清单再停止网关，不处理管理员 nginx 或其他占用 443 的服务。"""
    containers = run("docker", "ps", "-q",
                     "--filter", "label=com.docker.compose.project=" + config["composeProject"],
                     "--filter", "label=com.docker.compose.service=device-ingest-nginx").splitlines()
    marker = Path(config["acmePath"]) / "paused-containers.json"
    marker.write_text(json.dumps(containers))
    for container in containers:
        run("docker", "stop", "--time", "15", container)
    # 遇到非本任务的监听者直接失败；finally / ExecStopPost 恢复已暂停的网关。
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("0.0.0.0", 443))


def install(config):
    """验证可信链、域名、有效期及密钥配对后安装；私钥始终留在服务器。"""
    source = Path(config["acmePath"]) / "certificates"
    certificate = source / (config["hostname"] + ".crt")
    key = source / (config["hostname"] + ".key")
    issuer = source / (config["hostname"] + ".issuer.crt")
    run("openssl", "verify", "-verify_hostname", config["hostname"],
        "-untrusted", str(issuer), str(certificate))
    run("openssl", "x509", "-in", str(certificate), "-noout", "-checkend", "86400")
    ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(certificate, key)
    target = Path(config["tlsPath"])
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    # 网关暂停期间替换；挂载整个目录，使后续 nginx 读取能看到新文件。
    for origin, name in [(certificate, "fullchain.pem"), (key, "privkey.pem")]:
        pending = target / (name + ".new")
        shutil.copyfile(origin, pending)
        pending.chmod(0o600)
        pending.replace(target / name)


def renew(config):
    """每日检查；仅剩余不足 30 天时暂停入口并申请，失败也恢复旧服务。"""
    certificate = Path(config["tlsPath"]) / "fullchain.pem"
    due = subprocess.run(["openssl", "x509", "-in", str(certificate), "-noout",
                          "-checkend", str(30 * 86400)], capture_output=True, timeout=30)
    if due.returncode == 0:
        print("OK certificate not due; gateway unchanged")
        return
    try:
        pause(config)
        command = [config["legoPath"], "--log.level", "info", "--log.format", "json", "run",
                   "--accept-tos", "--account-id", config["accountId"], "--server", "letsencrypt",
                   "--path", config["acmePath"], "--domains", config["hostname"],
                   "--tls", "--tls.address", "0.0.0.0:443", "--http-timeout", "30",
                   "--renew-days", "30", "--ari-disable", "--no-random-sleep"]
        # systemd timer 已添加随机延迟；只在真实续期时占用 443。
        with (Path(config["acmePath"]) / "last-renew.log").open("w") as log:
            subprocess.run(command, check=True, stdin=subprocess.DEVNULL,
                           stdout=log, stderr=subprocess.STDOUT, timeout=300)
        install(config)
        print("OK certificate renewed and installed")
    finally:
        restore(config)


def main():
    os.umask(0o077)
    config = json.loads(Path("/etc/armada-ingest/acme.json").read_text())
    action = sys.argv[1] if len(sys.argv) > 1 else "renew"
    with open("/run/armada-ingest-acme.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        restore(config)
        if action == "install":
            install(config)
        elif action == "renew":
            renew(config)
        elif action != "restore":
            raise ValueError("INVALID_ACTION")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # 不回显命令输出、异常内容、运行配置或证书工具日志。
        print("FAIL certificate operation: " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
