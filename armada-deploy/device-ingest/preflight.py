#!/usr/bin/env python3
"""设备导入网关部署前的本地配置检查；只输出检查代码，不输出环境变量值。"""
import ipaddress
import os
from pathlib import Path
import re
import sys


def validate(environment):
    """验证公网路由配置和私网绑定；令牌业务映射的校验仍由后端启动门禁负责。"""
    errors = []
    if not environment.get("ARMADA_DEVICE_INGEST_CLIENTS_JSON", "").strip():
        errors.append("INGEST_MAPPING_MISSING")
    hostname = environment.get("INGEST_HOSTNAME", "")
    labels = hostname.split(".")
    if len(labels) < 2 or len(hostname) > 253 or any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels):
        errors.append("INGEST_HOSTNAME_INVALID")
    try:
        address = ipaddress.ip_address(environment.get("ARMADA_ADMIN_BIND_IP", ""))
        permitted = [ipaddress.ip_network(value) for value in
                     ["127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]]
        if not any(address in network for network in permitted):
            errors.append("ADMIN_BIND_MUST_BE_PRIVATE_IPV4_OR_LOOPBACK")
    except ValueError:
        errors.append("ADMIN_BIND_MUST_BE_PRIVATE_IPV4_OR_LOOPBACK")
    directory = Path(environment.get("INGEST_TLS_DIR", ""))
    if not directory.is_absolute():
        errors.append("TLS_DIRECTORY_MUST_BE_ABSOLUTE")
    for name in ["fullchain.pem", "privkey.pem"]:
        path = directory / name
        if not path.is_file() or not os.access(path, os.R_OK):
            errors.append("TLS_FILE_MISSING_OR_UNREADABLE")
    return sorted(set(errors))


if __name__ == "__main__":
    failures = validate(os.environ)
    for failure in failures:
        print("FAIL " + failure, file=sys.stderr)
    if failures:
        sys.exit(1)
    print("PASS device-ingest preflight; secret values omitted")
