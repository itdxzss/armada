# test1 设备上传入口证书

## 目标与授权

用户明确要求为 `ingest.65.2.123.53.nip.io` 申请证书；沿用本会话已确认的 test1（65.2.123.53）。本次范围为签发、服务器安装、续期和 TLS 验证，无后端、数据库、协议或真实账号变更。

## 结果

- Let’s Encrypt 正式证书签发成功，issuer 为 YE1，SAN 精确覆盖该域名。
- 有效期 UTC `2026-09-07 00:41:39` 至 `2026-12-06 00:41:38`。
- 证书 SHA-256：`2f46c894748c6d8b01beaa0d36e09fd9bb66eb5e56abcaabdf613fdd4dec0d57`。
- 私钥和 ACME 账户密钥仅在 test1 受保护目录；工作区只保留代码、公开证书元数据及脱敏结果。
- `INGEST_TLS_DIR=/etc/armada-ingest/tls`；目录 0700、文件 0600。
- `armada-ingest-certificate.timer` 已启用，service 首次执行 `Result=success / ExecMainStatus=0`。
- 正式网关使用证书目录只读挂载，支持续期时替换文件；此 Compose 改动在本地验证，正式网关尚未部署。

## 验证

1. 官方 lego 5.4.1 Linux amd64 release 与官方 checksums 一致：archive SHA-256 `ebb33f1bead5a7c99dd46f1c5734b44cf1eab5b5c12faf397cd14d50a5916419`。
2. 先在独立目录通过测试 CA 的 TLS-ALPN-01，再用正式 CA 签发；只占用 443，不增加 80 的规则或路径。
3. 服务器 OpenSSL 校验可信链、域名及有效期；SSLContext 验证证书与私钥匹配。
4. 从本机通过公网连接，系统默认信任校验成功，TLS 1.3、hostname 匹配、证书指纹一致；根路径返回 404。
5. 公网验证用临时 nginx、虚空上游，不接业务后端；systemd RuntimeMaxSec=180、ExecStopPost 清理，调用成功/失败路径均有 finally 清理。结束后容器已不存在。
6. 后端 StartedAt 仍为 `2026-09-01T08:58:36.919932714Z`，管理员 nginx 仍为 `2026-09-01T15:42:23.327071174Z`，二者 RestartCount 均为 0。
7. `python3 -B armada-deploy/device-ingest/renew-tls.test.py`：6 项通过，包括失败恢复、错误容器拒绝、幂等恢复、真实 OpenSSL 安装及不匹配密钥拒绝。
8. `python3 -B armada-deploy/device-ingest.test.py`：10 项 nginx/Compose 回归通过；首次沙箱内 Docker 访问失败，授权执行后通过。
9. systemd unit 验证及真实 service 检查通过。未强制重复签发正式证书；实际到期续期尚未发生，失败恢复路径由本地测试覆盖。

自动审批曾拒绝不包含成功路径清理的临时容器方案。改为硬超时和双重退出清理后获准并完成验证，没有绕过审批。

## 评审与运维

按 deploy-verify / expert-reviewer 检查目录权限、可信链、正式/测试 CA 隔离、端口占用、容器恢复和脚本差异；无阻断项。真正续期时仅短暂停用专用上传网关；失败保留旧证书并恢复网关。systemd 超时后执行恢复入口，不输出令牌、私钥或 ACME 详细日志。

操作、路径、恢复规则和停止续期方法见 [部署说明](../../armada-deploy/device-ingest/README.md#7-test1-证书与续期)。不撤销新证书、不删除被引用的密钥；停用 timer 可停止未来续期。没有 schema/API/Redis 变更。

## 遗留

- 正式 443 网关、新后端及令牌租户映射尚未启用；不能据本次证书验证宣称分组查询或上传可用。
- 不包含新 IPA、真机查询/上传、真实账号落组、协议上线或官方登出验收。
- TLS-ALPN-01 续期仍依赖该域名继续解析到 test1、443 可达、专用网关使用约定的 Compose 标签；变更部署项目名须同步配置。

参考：[lego TLS-ALPN-01](https://go-acme.github.io/lego/obtain/tlsalpn01/index.html)、[lego 5.4.1](https://github.com/go-acme/lego/releases/tag/v5.4.1)、[Let’s Encrypt 验证方式](https://letsencrypt.org/docs/challenge-types/)。
