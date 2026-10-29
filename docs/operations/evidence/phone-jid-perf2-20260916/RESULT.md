# 第二套环境：全参 phone 从 jid 回退

- 日期：2026-09-16
- 源码：主仓库 armada，分支 1.0.3-snapshot，基线 3493794f，叠加本次未提交的 3 文件修复。
- 范围：仅后端；phone 缺失、null 或空白时从纯数字 jid 获取，继续校验号码格式和 phone/jid 一致性；原始凭据不变。
- 发布目录：/private/tmp/armada-phone-perf2-20260916-01a0a9dd。只应用本次 scoped diff，未包含主仓库其他在途修改。
- 目标：perf2，3.110.124.52，Compose project armada-perf，数据库 schema armada_perf。
- 命令：ARMADA_DEPLOY_KEY=<既有 perf2 私钥路径> bash armada-deploy/deploy-test.sh --env perf2 --be -y。
- 部署退出码：0。

## 验证

- 发布目录 JDK 17 聚焦测试：FullParamsToSixConverterTest 27、AccountImportParserTest 44；共 71，失败/错误/跳过均 0。
- 部署脚本语法及 deploy-test.test.sh 通过。
- package-prod.test.sh 失败：基线缺少 armada-deploy/prod/scripts/inspect-production-host.sh。属于生产离线打包检查，不在此次 perf2 部署路径内；没有修改该问题。
- 专家评审：未发现本次修复的部署阻断项；未扩大 iOS 手机直传校验契约。
- 最终 JAR 本地读取用户文件：records=167、accepted=167、resolved=167、unique=167；未调用远程导入/登录。
- 与现行旧 JAR 比较：仅 FullParamsToSixConverter.class 与其 Result.class 字节变化；所有 migration 内容一致。
- 旧 JAR SHA-256：ca9a455cea9765a851e7886d50a106f938d8f9e08b3f646913733741e99a4c33。
- 新 JAR SHA-256：96f0c4339f7609d14b0dd77b3a4c91f6d3d9b7c2c54991bca152cb58d3962a98。本地、远端文件和运行容器一致。
- 容器启动：2026-09-16T11:12:40.521056337Z；最终检查运行 102 秒，running，restartCount=0；应用启动成功，启动 ERROR 行数 0，未发现迁移失败。
- /api/account-groups：HTTP 401 / code 40104，未登录时符合预期。
- /platform-config.json：HTTP 200，Title=第二套环境。
- 回滚备份：目标主机 /home/app/armada-deploy/backups/phone-jid-fallback-20260916/，含旧 JAR、Compose 和后端 Dockerfile。

## 交付边界

主仓库代码已修改，第二套环境后端已部署；未提交或推送 Git，未重新导入账号或发起真实登录。第一套环境未部署。
