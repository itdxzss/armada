# Armada Production Offline Install

生产机器不需要访问外网，不需要 git、Maven、pnpm、npm、ssh 或 rsync。机器上只需要提前安装好 Docker Engine 和 Docker Compose v2。

## App Machine

前后端机器安装 `armada-app-prod-<version>.tar.gz`：

```bash
tar -xzf armada-app-prod-<version>.tar.gz
cd armada-app-prod-<version>
umask 077
cp .env.example .env
chmod 600 .env
vim .env
./scripts/install.sh
```

必须替换 `.env` 里的 RDS、MSK、协议机内网地址、共享 API key 和推广 Token AES-256 密钥。密钥使用 `openssl rand -base64 32` 生成；密钥变化时必须同时更新 `PROMOTION_TRACKING_ENCRYPTION_KEY_ID`，旧密钥按轮换计划保留，不能在每次部署时重新生成。

## Protocol Machine

协议机器安装 `armada-protocol-prod-<version>.tar.gz`：

```bash
tar -xzf armada-protocol-prod-<version>.tar.gz
cd armada-protocol-prod-<version>
cp .env.example .env
vim .env
./scripts/install.sh
```

`API_KEYS` 必须和 app 机器的 `ARMADA_PROTOCOL_API_KEY` 一致。`PROTOCOL_PUBLIC_HOST` 填协议机内网 IP 或内网 DNS，worker 的 `PUBLIC_ENDPOINT` 会用它生成。

## Operations

查看状态：

```bash
./scripts/status.sh
```

查看日志：

```bash
./scripts/logs.sh
```

执行生产主机只读巡检（逐项输出 `PASS/WARN/FAIL/SKIP`，不自动修复）：

```bash
sudo ./scripts/inspect-production-host.sh \
  --service docker \
  --report "./inspection-$(hostname)-$(date +%Y%m%d-%H%M%S).tsv"
```

按机器角色补充 `--listen-port`、`--health-url`、`--cert` 和 `--backup-marker`。完整检查项、阈值与安全边界见仓库 `docs/operations/production-host-inspection.md` 或脚本 `--help`。

回滚到上一个已安装版本：

```bash
./scripts/rollback.sh
```

默认安装目录是 `/opt/armada-app` 或 `/opt/armada-protocol`。如果需要改目录：

```bash
ARMADA_INSTALL_ROOT=/data/armada-app ./scripts/install.sh
```
