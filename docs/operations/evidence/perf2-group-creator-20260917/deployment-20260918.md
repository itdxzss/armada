# 第一、二套部署证据（2026-09-18）

用户明确要求部署本地主仓库当前代码。范围为 armada 后端与 Android 协议；包含既有未提交修改。未部署前端或 Web 协议，未 commit/push，未主动执行目标群刷新或批量数据回填。

## 来源与验证

从本地主仓库冻结工作文件到 `/private/tmp/group-creator-release-20260918/`，复制后复核源文件哈希；部署后构建相关源文件仍与快照一致。没有切换或重置共享主仓库。

- armada 基线 `41f5c464`，4684 文件，快照指纹 `228a874c0d92f06220495c6ad6f1aecfc2cc947d9a73bebaf566024323fb1c63`。
- Android 基线 `6019251`，792 文件，快照指纹 `dfcbfb385c718d4ea22906666b032a92d930b123c76b533a3df04bdc111e0af5`。
- 冻结快照 Java 41 类、570 tests，0 failures/errors/skipped；报告保存在快照目录 `surefire-reports/`。
- 部署脚本语法与 `deploy-test.test.sh` 通过。无关生产离线打包测试 `package-prod.test.sh` 因现有缺失文件 `armada-deploy/prod/scripts/inspect-production-host.sh` 失败，不影响本次测试环境部署。
- Go 验证沿用源码未变化的本地验证结果，已有 pkg/noise 基线失败见 local-validation-20260918.md。

## 部署与实际运行

| 环境 | 后端实际运行 JAR SHA-256 | Android 状态 | 标准深度检查 |
|---|---|---|---|
| test1 | `dad20f64d3f3df5ac00075212359ebfef48ac4cc3abdaf2068ae2c24d589b22c` | coordinator + 3 节点 healthy，3 节点在线 | `--env test1 --check` exit 0 |
| perf2 | `7b264357990509f94d0472d9412f9fcb2a4e807aa29a561e0b113802d8a2fdd5` | 主协议、callback、traffic-dashboard healthy | `--env perf2 --check` exit 0 |

后端两个运行中 JAR 均与本次对应部署制品哈希一致，容器 restartCount 均为 0。启动日志均确认启动成功，未见 Application run failed、迁移失败或 OOM。两套深度检查均通过，包括服务健康、协议连接、Kafka 与组件连通性。

Android 在 test1 四个实例及 perf2 主实例逐个检查源码与二进制：creator.go SHA-256 均为 `2d0ebd272c8bbde79059f674f1b5999e57bf671f29d6c7e85ab517218833525f`；运行制品包含新解析器的 `creator_participant`、`mapping_unavailable` 标记，restartCount 均为 0。

执行退出码：test1 后端、test1 Android、perf2 Android 发布脚本均为 0。perf2 后端已构建上传并重建启动，收尾 SSH 短暂断开导致发布脚本 exit 1；随后独立只读运行制品核对及标准 `--check` 均 exit 0，确认成功，未重复重启。

## 证据与验收边界

临时证据目录 `/private/tmp/group-creator-release-20260918/` 包含 source-manifest.json、backend-runtime.json、android-runtime.json、两套 be/zhuan/check 日志及 Java 测试报告。该目录为本机临时文件，不作为永久制品仓库。

部署与运行验证通过不等同于历史群补齐验收。本次没有主动触发原 23 个缺失群的刷新，不能承诺全部补齐；无原始创建者身份或无可信 LID 映射的群仍应保持未知。没有执行真实 MySQL 并发专项测试。
