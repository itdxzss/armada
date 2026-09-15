# 拉群数据包 test1 部署验证

时间：2026-09-15 01:43–01:47（Asia/Shanghai）。用户授权“部署到测试环境，你验证下”。

## 部署范围与来源

- 第一套环境 test1：`http://armada.65.2.123.53.nip.io/`。
- 命令：`./armada-deploy/deploy-test.sh --env test1 --all -y`，退出码 0。只更新 Armada 后端和前端，协议层未部署。
- 后端分支 `1.0.3-snapshot`，基线 `267bb14cfc610353231f6ce8a1cf6318cbb275e7` 加当前数据包改动。
- 前端分支 `1.0.3-snapshot`，基线 `6352c6c0e7cc2004237135a1ce12c0e6b50feed3` 加当前数据包改动。
- 本次没有 commit/push。构建前后源码哈希清单一致，未混入构建过程中的变更；清单 `/tmp/group-package-deploy-source-manifest.json`。
- 后端 JAR SHA-256：`de381379e3ff09b055a9c5ba7367800e70c5164ee151ed01e5fb755bf88827d0`；本地、远端文件和运行中 `/app/app.jar` 三者一致。
- 公网前端 index.html 与本地 dist 完全一致，SHA-256：`f592395eeb9f8be272f7ecaa97ab174af43176ab5e8de224661cc9fd021fbd21`。

## 验证结果

- 部署前数据库最新 V190；部署后 V191、V192 均 success=1。四张资源表、四个来源字段和来源索引均存在。
- 两个已有租户均生成 GroupDataPackage 页面及五个按钮权限；普通角色沿用显式授权，不自动扩权。
- 后端容器在 2026-09-14 17:43:05 UTC 启动，启动成功，复查 running=true、restarts=0；前端容器运行正常。
- Kafka 主消费批量参数保持 `SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS=500`，认证前缀保持 test1。未覆盖远端私密配置。
- `./armada-deploy/deploy-test.sh --env test1 --check` 退出码 0：Armada、Baileys、Kafka、Zhuan、跨组件配置只读检查通过。
- 新数据包列表和国家接口未登录均返回 HTTP 401、业务码 40104，未向匿名请求暴露数据。
- 从 test1 information_schema 只读导出 133 张表、2387 列、1696 条索引元数据，用原 `gen_datamodel.py` 刷新数据模型 wiki。
- 部署脚本语法和 deploy-test.test.sh 通过。package-prod.test.sh 因既有缺失 `prod/scripts/inspect-production-host.sh` 失败，属于未使用的生产打包链路；没有将其记为通过。

## 业务验收边界

技术部署已验证。浏览器扩展能打开第一套验收页面，但目前停在登录页，已请用户完成登录。尚未在 test1 完成登录后的导入、导出、任务领取和结束释放操作；不得将本地 H2 的闭环测试或迁移成功替代远程业务验收。

本次没有创建验收包/任务，没有启动真实 WhatsApp 拉群或发送消息；数据库读取与技术检查没有修改既有任务。待登录后只用带独立验收前缀的数据包执行 CRUD、去重/A 标记、追加/覆盖、草稿预览、autoStart=0 正式领取及结束/删除释放验证；真实入群结果另按受控账号和接收人验收。

## 回退与证据

- test1 同机备份：`/home/app/armada-deploy/backups/group-package-20260915-predeploy/`，包含旧运行 JAR 和前端/部署配置压缩包。
- 旧 JAR SHA-256：`167a15797e2a11796a31b4643fa7fefc24925a2eb95504c596fdce67a609fa7f`。
- 新迁移兼容旧代码，回退制品时保留 schema 与业务证据；不直接运行删除结构脚本。
- 部署日志：`/tmp/group-package-deploy-test1.log`；深度检查：`/tmp/group-package-test1-deep-check.log`；结构生成：`/tmp/group-package-schema-generation.log`。
- 远端数据库辅助脚本仅含通用环境读取与 mysql 调用，不含凭据；后续不再使用时删除本次 `/tmp/group-package-test1-db.sh`。
