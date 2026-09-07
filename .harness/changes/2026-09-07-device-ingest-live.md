# test1 手机上传联调进度（2026-09-07）

用户要求打通手机到 test1 的分组查询、上传和控端上线。本记录更新至北京时间 11:14；服务端已部署并通过公网验证，手机真实账号端到端验收尚未完成。

## 后端与入口

- 使用干净集成工作树 `device-import-ingest-main` 的 `f173d13d`；没有打包主工作区的其他在途修改。
- test1 为 `65.2.123.53`，新后端与正式 443 网关已启用；最终激活时间 2026-09-07T03:09:33Z，容器 restartCount=0。
- 候选镜像 `armada-device-ingest:test1-f173d13d` 已在 test1 构建，使用当前线上相同的运行时基础镜像，仅替换 JAR。
- 候选镜像 ID：`sha256:40e78ddb67d112f1927a08ee912fc6f51a288b3aa1339b640557888b59f5277b`。
- JAR SHA-256：`1000316877a6be0e4661d3d923412ed16de1edfc53e8e877f8c23d4317e9122c`；上传后及镜像内校验一致。
- 发布目录：`/home/app/armada-ingest-releases/f173d13d`。旧镜像已保留为 `armada-backend:pre-ingest-f173d13d`；部署前配置及容器快照保存在该目录受保护的 `rollback/` 中，不复制到 Git。
- 真实数据库迁移预检：失败迁移 0，已解析迁移 checksum 冲突 0，历史 missing 版本 172，待执行 179。保持现有 `*:missing` 设置，不能关闭 checksum 校验。V179 来自当前发布基线，已由 Flyway 执行成功；最终失败迁移数为 0。
- 已复核专用安全组 TCP 443 对 IPv4 公网开放；管理端 80 保持既有 IP 白名单。现有 SSH 22 规则未变，不能声称整台主机只开放 443。
- 可信证书与续期已就绪，见 `2026-09-07-device-ingest-certificate.md`。正式网关已激活，证书续期 timer 保持 active。

## 手机构建、签名和安装

- 从 `wa-biz-compat-v8-extension` 当前工作区源码重新构建标准 IPA，包含既有未提交改动；未启用 smoke 或 credential-dump 构建模式。
- 新 unsigned SHA-256：`f70d4c63283bd49d425ab144d7e8ec1fe9d4f482d404200e9105debb1ec09304`。
- 通过 USB 上传，全能签资源列表确认本次 `V8-ingest-20260907-1100-import.ipa`。它与本次 unsigned 为同一份文件；使用新文件名排除了先前同名缓存干扰。
- 全能签 7.7.0 使用现有有效证书完成签名，未升级签名工具、未导出签名私钥。
- 产物：`/Users/daishuaishuai/IdeaProjects/wa-biz-compat-v8-extension/dist/V8-ingest-20260907-1100-signed.ipa`，165690031 字节。
- signed SHA-256：`6e92b86a529e78d4667b8dbb87b5780500727c5c50d26bd0379a9ea345d01616`。
- ZIP、深度严格 codesign、描述文件覆盖目标设备、实际签名叶证书属于描述文件并仍有效均通过；实际证书有效至 2026-10-31。
- Bundle ID `net.whatsapp.WhatsAppSMB.codextest263472`，版本 26.34.72，build 1053546873；已覆盖安装到当前连接的 iPhone，并确认持续显示正常欢迎页。另一份 Bundle ID `1` 没有操作。
- 标准包不写调试 boot 标记；设备旧 `wbx-boot.log` 时间较早，未将它作为本次扩展加载证据。控端设置入口和账号业务操作尚未进行。
- 本机证据保存在手机项目 `.automation/runs/20260907-ingest-e2e/`，产物旁附 `.manifest.json`。

## 最终服务端验证

- 用户允许自行选择账号归属，本次采用租户 A（ID 1）；默认 deviceOs=2、accountType=2、ipAllocationMode=mixed。真实令牌只在服务器受保护配置中保存，手机配置尚待进行。
- 保留 71 项当前注入的应用运行值，修正并持久化现有 AUTH_SESSION_KEY_PREFIX；数据库、协议、Kafka、代理和调度配置均未漂移。
- 首次切换误用了本服务未启用的 `/actuator/health` 作为启动检查，自动回滚恢复了旧镜像与配置。改用真实带令牌分组查询检查就绪后，最终激活成功。未关闭 Flyway 校验。
- 最终公网验证：可信 TLS；合法令牌 GET 分组 200、47 项、仅 id/name；无令牌 401；非法上传 body 400；越界路径/编码别名/尾斜杠 404；查询参数 400；错误方法 405；两个 OPTIONS 均 204，无 CORS。
- API 分组 ID 集合与真实数据库租户 A 的未删除分组完全一致。
- 网关和后端日志中未发现本次令牌；请求详情及 SQL 参数日志关闭，后端没有 host ports。
- 公网复核曾遇到一次 TLS EOF；随后独立 curl 验证及整组接口重跑均通过。它不构成手机网络已经验收的证据。
- 脱敏公网检查见 `2026-09-07-device-ingest-public-verification.json`；服务器 `/home/app/armada-ingest-releases/f173d13d/` 内保留 activation-result.json、activation-config-check.json、live-verification.json。

## 手机待办

指定 V8 仍显示“同意并继续”，尚未登录 WhatsApp Business。用户已询问登录的含义，已解释需要在 V8 内用测试手机号完成 WhatsApp 登录，才能取得真实账号参数；当前不能宣称手机上传和控端 ONLINE 已打通。

1. 用户登录测试账号后，安全填入域名与服务器令牌，保存到该 V8 的 Keychain，实测手机查询分组。
2. 选择分组，完成真实上传、数据库归组、官方登出与当前轮次控端 ONLINE。
3. 分别记录手机请求、HTTP 受理、数据库、调度及协议回调证据。手机端真实分组请求、上传、登出和控端上线目前均 NOT_RUN。
