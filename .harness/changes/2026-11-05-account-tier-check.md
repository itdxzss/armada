# 变更记录：账号档位检测（A → B → C，离线实施）

- 日期 / 分支 / worktree: 2026-11-05 / 1.0.3-snapshot / 当前工作树
- 需求来源: `docs/handoff/2026-11-05-账号档位检测-Codex任务书.md` 与对应设计文档。
- 状态: A / B / C 离线实施和验证完成，用户已授权提交和推送，禁止部署

## 目标（一句话）

完成 Web 档位查询、Armada 批次调度与前端草稿补全；仅离线验证。

## 缺口拆解 / 任务清单
- [x] A：Web 协议路由、契约、串行限流、Jest。
- [x] B：迁移、协议端口、批次/调度/API、H2 与 HTTP 替身测试。
- [x] C：原草稿逻辑/测试、菜单、账号列表入口、构建与视觉校验。

## 关键设计决策

- 按 A → B → C 顺序实施；前端草稿不改版式；全部保留在当前 `1.0.3-snapshot`，不提交。
- 迁移开工最高 V224；本功能 V225/V226，另一个会话后来使用 V227（互聊），未碰其内容。
- 调度默认 false，间隔 4000ms、每轮 20、冷却 300000ms（占位）、并发池 2；用户暂不需要决定最终限流值，须另行授权试跑后确定。
- 在线使用现有 `AccountLoginStateCode.ONLINE=1`；菜单 view/写接口 edit；没有调整角色权限。实际角色授权与真机参数仍待目标环境确认。
- 两表属于档位检测聚合，现有账号/互存表不承载历史档位。额外 request_fingerprint 支持幂等参数冲突校验；target_identity 是身份/凭据版本摘要；executor_round_count/last_attempt_at 保存跨批次、重启后的限流事实；不保存原始协议返回。
- 批次及账号短事务使用 READ_COMMITTED；锁查询 flushCache，确保等待锁后不复用 MyBatis 一级缓存的旧在途判断。
- 复查保持固定目标账号集合，当前同号码的新凭据在创建复查时重新冻结；删除/换号记录显式失败，不静默丢弃。
- H2 真实加载生产 Mapper XML、租户插件及 Spring 事务；迁移的 MySQL JSON 类型仅在测试中替换为 VARCHAR，索引 COMMENT 在测试去除，并用生产 SQL 结构测试覆盖。
- 数据模型通过 `account-tier-check/refresh-datamodel.py` 调用现有 `gen_datamodel.py` 生成本功能片段并合并，未修改其他会话的生成器改动。

## 验证（evidence-before-done）

- A：路由新测试 9/9；Jest 定向回归 54/54；类型检查、build、OpenAPI regenerate 退出 0。
- B：`JAVA_HOME=<本机 Temurin 21> mvn -o -f armada-api/pom.xml -DargLine=-javaagent:<本机 byte-buddy-agent-1.14.19.jar> -Dtest='AccountTierCheck*Test,AccountMutualContact*Test,AccountProtocolLookupServiceTest,ProtocolConfigurationTest,ProtocolHttpExecutorTest,RoutingAccountRuntimeStatusPortTest,WebAccountRuntimeStatusAdapterTest' test`：67 项通过，0 失败/错误/跳过；其中档位功能 29 项。输出 `/tmp/account-tier-check-backend-regression.log`。
- B：`xmllint --noout .../AccountTierCheckMapper.xml` 退出 0；`python3 .harness/wiki/test_api_docs.py` 1 项通过。
- C：现有 Node test runner + tsx 单测 12/12，Chrome 本地夹具 e2e 7/7（26.8s）；typecheck、定向 eslint/stylelint/Prettier、独立目录 Vite build 均退出 0。未另装 Vitest；任务书指定的参考测试实际用 node:test。
- C：原型五张截图逐一人工对照，沿用草稿布局；最终截图和详细报告在 `wheel-saas-pure-web/.harness/changes/account-tier-check/`。所有浏览器 API 均为本地虚构夹具，未声明/外部请求中止。
- 测试先红：策略类不存在；并发用例随后发现锁前 MyBatis 缓存问题，修复后通过。首次环境默认 JDK 27 的 Mockito attach 失败，后改用项目规定 JDK 21 + 已有 agent 启动参数，不安装依赖、不修改项目 JVM 配置。
- 全工作树 diff check 另有他人 `AndroidMessageSendBackendTest.java` 空白问题，未改；本功能单独检查。

## 部署

2026-11-05 用户已授权分别提交并推送三个仓库；不部署、不查询真实账号。提交与推送结果以 Git 记录及本次交付回复为准。三个仓库仍为 `1.0.3-snapshot`。本任务没有修改 Android 仓库。

## 遗留 / 跟进

- D 的真实服务联调、真机试跑及 E 的 Android 直接查询不在本次 A/B/C 离线完成范围，均未执行。
- 真机试跑须另外获得授权并确认环境；自查/探针一致性、PN/LID 差异、跨小时稳定性和每轮 20 条的边界仍待实测。
- 300000ms 冷却是占位值，不是用户已确认的最终参数；沿用现有在线状态与 view/edit 映射，目标环境角色配置尚未验证。
- 提交核对时修正离线文档生成脚本将 UNIQUE KEY 误当列的问题；重新生成后两表分别为 14 / 27 列，SQL 与业务代码未变。
- H2 仅验证本地持久化与并发逻辑，不代表 MySQL InnoDB 或真实网络验收。
- 共享 `application.yml`、数据模型文档和前端账号列表含其他会话改动；提交时必须逐段只纳入本功能。V225/V226 未撞号，原有生成器修改及其他会话代码保留。
