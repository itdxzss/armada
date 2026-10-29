# 第一套个人号上传令牌 — 2026-10-07

用户明确授权新增个人号令牌。现有商业号令牌保持不变；新增映射 tenantId=1、deviceOs=2（苹果）、accountType=1（个人）、ipAllocationMode=mixed。

- 持久配置：test1 后端部署目录受保护 .env 的 ARMADA_DEVICE_INGEST_CLIENTS_JSON。
- 旧配置只保留在服务器 /home/app/armada-deploy-release-backups/personal-ingest-20261007/env.before（0600）；记录不含令牌或令牌摘要。
- 仅以原镜像重建 armada-backend，其他环境变量和挂载逐项核对不变；重载业务 nginx 与导入网关以刷新上游地址。
- HTTPS 导入入口无副作用鉴权测试：个人令牌+空对象 HTTP 400；商业令牌+空对象 HTTP 400；随机无效令牌 HTTP 401。
- 后端 running，RestartCount=0。未创建真实导入任务，未导入或操作 WhatsApp 账号。

个人包：WA-P.ipa；服务地址 https://ingest.65.2.123.53.nip.io（不带接口路径）；分组名称填写租户 1 下已存在的分组。
