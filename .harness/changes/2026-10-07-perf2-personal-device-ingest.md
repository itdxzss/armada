# 第二套 iOS 个人号手机上传入口

- 日期：2026-10-07
- 需求：用户要求开通第二套 HTTPS 手机上传入口并交付 WA-P.ipa 使用的个人号令牌。
- 状态：已部署，HTTPS/鉴权/路径验收完成；真实个人号导入未执行。
- 环境：perf2，3.110.124.52，Compose project `armada-perf`。
- 本地工作区：`1.0.3-snapshot`，存在其他会话改动；本次不构建或部署本地业务代码。

## 范围与决策

- 保留当前后端镜像与有效运行环境，仅追加 tenantId=1 / deviceOs=2 / accountType=1 / smart 的令牌。
- 新增 `https://ingest.3.110.124.52.nip.io`，仅开放两个 device-imports 路径；不开放注册助手接口。
- 复用既有 nginx 模板、lego 5.4.1 和续期脚本；仅生成网关服务的 Compose overlay，不应用原 overlay 对管理员端口的覆盖。
- 管理员 nginx 保持原容器、80 端口和配置；后端重建后仅校验并 reload，刷新 upstream 地址。
- 真实令牌、私钥、环境备份和 ACME 账户只保存在服务器受保护路径，不进入记录、Git 或日志。
- 无数据库、Redis、协议层代码变更，不创建真实账号导入任务。

## 任务

- [x] 只读确认第二套未部署 443 网关、现有两个令牌均为商业号。
- [x] 独立检查最小部署方案，避免常规 deploy-test.sh 构建当前脏工作区。
- [x] 本地续期测试 6 项、Compose/preflight 2 项、deploy-test 回归及 shell 语法通过。
- [x] 保存服务器回滚备份，安装可信证书和续期任务。
- [x] 配置个人号令牌，以当前镜像重建后端，部署专用网关。
- [x] 校验 TLS、401/400、路径/方法限制、原后台可用性与容器稳定性。
- [x] 通过 SSH 内存传输到本机剪贴板并核验一致，未打印或落地明文令牌。

## 验证边界

本地 Docker daemon 不可用，容器级网关验证放在目标环境执行。生产打包测试因既有缺失文件 `prod/scripts/inspect-production-host.sh` 失败，本次不发布生产包。TLS/鉴权验证不等于真实个人号导入和 WhatsApp 上线验收。

## 回滚

先停止本次专用网关及证书 timer；恢复受保护备份中的原令牌环境，以原镜像与原有效环境重建后端，再在原 nginx 容器执行配置校验与 reload。保留已有商业令牌和业务数据，不回滚数据库、不动协议服务。

## 准备阶段与已解决的阻碍

- 运行基线：发布标记 `720d4a3da04b121630e3d6ce96d682e5ce4016a0`，后端 image ID `sha256:1e1fec7f7e8345df26c26f431a77807e692efc4a3b9347038a11409d2e09cbca`。后端无宿主端口映射；管理员 nginx 发布 80。
- 目标实例：`i-04a37939e7de61412`，区域 `ap-south-1`，安全组 `sg-0910d9bc97301ebd5`。主机 firewalld/ufw 均 inactive。
- 域名已解析至第二套 IP；官方 lego 5.4.1 linux/amd64 已安装，发行物 SHA-256 与 GitHub release digest 一致。
- 证书初次申请失败：TLS-ALPN-01 连接超时，尚无证书。需要核实安全组 443 规则，尚未执行任何安全组修改。
- 网关与续期文件已上传 `/home/app/armada-deploy/device-ingest-perf2/`，尚未启动；系统级续期脚本安装尝试因 SSH 在密钥交换前被关闭而未执行。
- 原后端、管理员 nginx、原 `.env` 均未修改，个人号令牌尚未生成或启用。
- 默认和现有其他 AWS 会话已过期；用户明确授权 `aws login`，CLI 正等待 Chrome 登录完成。不要记录授权 URL、验证码或凭据。
- 本次安全配置更新脚本：`/private/tmp/armada-perf2-ingest-20261007/backend-env-update.py`，SHA-256 `3f9f7083f4fb7e15082cacdbf8278a5cfe53ba5fa270643d15118054728bbc56`，尚未上传/执行。`prepare` 安全备份、校验 Compose 和原运行环境，仅 `apply` 重建后端；支持 `verify` / `rollback`。
- 继续顺序：完成 AWS 登录并核对实例/安全组归属 → 仅补必要的 443 规则 → 确认 SSH 恢复 → 重试证书 → 安装续期 → 执行后端 prepare/apply/verify → 原 nginx 校验/reload → 启动网关 → 公网 TLS/鉴权/路径验证 → 通过本机剪贴板交付个人令牌。

## 最终部署与验收

- 用户已完成 AWS 登录，并明确批准 `sg-0910d9bc97301ebd5` 添加 TCP 443 / `0.0.0.0/0` 的长期入站规则。实际新增规则 ID：`sgr-0fbbd906faf266e00`；该安全组仅关联第二套实例。
- 上传地址：`https://ingest.3.110.124.52.nip.io`，在手机填写基础地址，不带 API 路径。
- 证书：Let's Encrypt，`notAfter=2027-01-05 08:53:18 UTC`；完整链、域名及私钥配对已通过安装脚本核验。本机公网请求 `ssl_verify_result=0`。
- 网关运行目录 `/home/app/armada-deploy/device-ingest-perf2`，独立 Compose 文件只含 `device-ingest-nginx`；使用既有 `armada-perf_default` 网络。
- 后端只更新一个环境变量 `ARMADA_DEVICE_INGEST_CLIENTS_JSON`；原 98 项运行环境除该变量外逐项一致，挂载、网络及运行配置一致，镜像 ID 不变，restartCount=0。
- 原两个商业号映射保留；新增映射为 `tenantId=1, deviceOs=2, accountType=1, ipAllocationMode=smart`。配置已写入原服务器 `.env`，供后续常规发布保留。
- 后端就绪后在原 `armada-nginx` 容器执行 `nginx -t` 和 reload；容器 ID、80 端口映射未变，本机公网管理首页 HTTP 200。
- 续期文件已安装，`armada-ingest-certificate.timer` 已启用，首次 service 检查成功。只在续期时短暂停止专用网关，不影响管理员 nginx。
- 新入口 12 项无副作用检查全部通过：无/错令牌 401；正确个人令牌+空上传/空退出请求 400；两个 OPTIONS 204；GET 上传 405；根路径、管理 API、注册助手、分组枚举 404；query 400；所有响应 no-store，无 CORS 放行。
- 真机个人号上传、官方退出与协议上线尚未执行，不能将上述鉴权验证称为 WhatsApp 业务验收。
- 配置及秘密回滚材料只在服务器 `/etc/armada-ingest/backups/20261007T095230.402791Z/`，root 私有。操作脚本 `/opt/armada-ingest/backend-env-update.py` 支持 `rollback --backup <该目录>`；之后需等待后端就绪并 reload 管理 nginx 和重启专用网关。
- SSH 后续恢复；最终通过 SSH 捕获令牌到本机进程内存，`pbcopy` 交付并 `pbpaste` 比较一致。未将明文令牌写入对话、文件或日志。

## SSM 执行记录

SSH 中断期间使用实例既有、在线的 SSM 通道，没有更改 SSH 认证或实例权限。

- 证书与镜像准备：`4b3cf4c2-2e94-440b-a86b-2e9072ba2048`，Success。
- 配置备份与预检：`3479a76e-d06b-4233-b884-b7bec0c8e2a1`，Success。
- 后端/网关/续期启用：`439a9bc4-7dee-4e3d-9b5c-af6c91ae57fd`，Success。
- TLS/鉴权/路径验收：`bc7c17b9-4cc1-4a29-bc1b-c62749e4917a`，Success，12 项通过。
