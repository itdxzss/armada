# test1 手机上传联调进度（2026-09-07）

用户要求打通手机到 test1 的分组查询、上传和控端上线。本记录更新至北京时间 11:45；手机查询分组、真实上传、按组入库和官方退出已实测完成。控端在手机退出后重新上线，协议层及数据库连续至少 60 秒均在线。首次自动上线发生过抢登，本次结论为 PASS_WITH_MANUAL_REONLINE。

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
- 标准包不写调试 boot 标记；设备旧 `wbx-boot.log` 时间较早，未将它作为本次扩展加载证据。本轮登录后已实际进入控端设置、查询分组并上传，扩展功能行为已验证。
- 本机证据保存在手机项目 `.automation/runs/20260907-ingest-e2e/`，产物旁附 `.manifest.json`。

## 最终服务端验证

- 用户允许自行选择账号归属，本次采用租户 A（ID 1）；默认 deviceOs=2、accountType=2、ipAllocationMode=mixed。真实令牌存于服务器受保护配置及该 V8 的 Keychain，已安全完成手机配置。
- 保留 71 项当前注入的应用运行值，修正并持久化现有 AUTH_SESSION_KEY_PREFIX；数据库、协议、Kafka、代理和调度配置均未漂移。
- 首次切换误用了本服务未启用的 `/actuator/health` 作为启动检查，自动回滚恢复了旧镜像与配置。改用真实带令牌分组查询检查就绪后，最终激活成功。未关闭 Flyway 校验。
- 最终公网验证：可信 TLS；合法令牌 GET 分组 200、47 项、仅 id/name；无令牌 401；非法上传 body 400；越界路径/编码别名/尾斜杠 404；查询参数 400；错误方法 405；两个 OPTIONS 均 204，无 CORS。
- API 分组 ID 集合与真实数据库租户 A 的未删除分组完全一致。
- 网关和后端日志中未发现本次令牌；请求详情及 SQL 参数日志关闭，后端没有 host ports。
- 公网复核曾遇到一次 TLS EOF；随后独立 curl 验证及整组接口重跑均通过。它不构成手机网络已经验收的证据。
- 脱敏公网检查见 `2026-09-07-device-ingest-public-verification.json`；服务器 `/home/app/armada-ingest-releases/f173d13d/` 内保留 activation-result.json、activation-config-check.json、live-verification.json。

## 真机验收（本轮）

- 指定 V8 登录后，仅启用总开关和 control-upload；实际查询到租户 A 的 47 个分组，选中“测试全参号”（ID 158）。
- 手机确认上传后显示控端已受理。真实导入为批次 73、账号 1717，手机号仅记录尾号 9821；批次和账号均属于 tenantId=1、groupId=158。
- 原始全参 1065 字节，以 sourceFormat=3 入库；转换后的 credential 为 credFormat=1（六段）、321 字节。验收记录仅保留格式与长度，原始凭据未离开服务器。
- 03:31:54Z 观察到 QUEUED；03:32:02Z 生成自动上线命令 2995648，来源 batch_online，Kafka SENT，重试 0。03:32:05Z 后端收到 ONLINE，导入 detail 达到 SETTLED，loginResult=1。
- 03:32:08Z 收到 LOGIN_REPLACED / rawCode=303；当时手机尚未退出。随后在手机接受“退出登录”，确认官方欢迎页与“同意并继续”，不是仅关闭 App。
- 03:40:08Z 在现有 test1 控端页面对该账号执行一次普通“上线”，命令 2995650，来源 manual_online，Kafka SENT，重试 0。03:40:11Z 消费并落库 ONLINE 回调。
- 手机退出后连续 16 次、间隔 4 秒检查，协议状态 code=0 / online，数据库 login_state=1；11:45（03:45:05Z）最终复查仍在线。浏览器操作结束后立即归还原标签页。
- 账号生命周期 account_state=6（被抢登）标签仍保留；当前登录态 login_state=1。现有 ONLINE 回调不会清除该生命周期标签，未手改数据库。
- 03:25Z 起本轮后端与上传网关日志未命中本次令牌或完整 raw payload；本机本轮 27 份日志/元数据等文件及手机 Git diff 未命中令牌。此检查不等于对全部历史日志的证明。
- 脱敏三层证据见 `2026-09-07-device-ingest-phone-verification.json`；手机项目 `.automation/runs/20260907-ingest-e2e/phone-officially-logged-out.png` 为当前退出页面证据，已同步更新 signed IPA manifest。

## 遗留边界

当前服务端在 QUEUED 后按 10 秒调度上线，手机在上传成功后才弹出退出确认，没有手机退出回执。因此手机迟迟未确认退出时，控端可能先上线后被抢登。本轮通过官方退出后的一次普通上线完成交接，不能宣称“一次点击即可无抢登全自动交接”。本轮未改业务代码，也未重发导入、手改账号状态或触碰其他账号。

## 本轮交付校验

仅更新本任务验收文档、脱敏 JSON 和本地 IPA manifest，核对字段与原始本轮证据并执行 JSON 解析、git diff --check。后端运行镜像仍为 f173d13d，不为文档更新重新部署；手机仍使用上文已验证的 signed IPA。
