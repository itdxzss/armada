# 养群本地验证记录

日期：2026-09-10。以下为本地实际执行结果；没有访问真实账号、群、数据库或部署环境。

## 后端

在 armada-api 执行（Mockito agent 使用本机已有依赖）：

```sh
mvn -q -Dtest='Script*Test,GroupScriptCandidateMapperH2Test,GroupListCurrentMapperSqlShapeTest,ResourceAssetMapperH2Test,MarketingTemplateServiceImplTest' -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar test
```

该轮 70 项通过。随后补充纯图片允许空内容且仍锁公共素材、缺图空消息拒绝两项，单独重跑 MarketingTemplateServiceImplTest，23 项通过。最终这十个测试类的当前报告合计 **72 项通过，失败/错误/跳过均为 0**。

| 测试类 | 数量 | 主要边界 |
|---|---:|---|
| ScriptRoleAssignmentTest | 3 | 随机无重复、完整角色匹配、不可满足拒绝 |
| ScriptQualificationTest | 4 | 5 人缺 4、严格容量门槛、离线/未确认/发言权限、入群后重查与绑定稳定 |
| ScriptMarketingContentTest | 5 | 消息内容、图片和按钮转换及既有协议能力 |
| ScriptMarketingExecutionTest | 18 | 启动零提交、按提交时刻推进、暂停原命令、截止、最后一条超时、迟到结果、旧任务兼容及事务并发 |
| ScriptMarketingMigrationTest | 2 | V180 约束；V187 三菜单、保留原任务菜单 ID/路由/按钮 |
| ScriptDefinitionTest | 3 | 定义与任务快照独立、本人/租户边界、SQL 分页/状态与无效配置 |
| GroupScriptCandidateMapperH2Test | 3 | 真实成员/发言查询、租户与分组隔离、群列表去重计数 |
| GroupListCurrentMapperSqlShapeTest | 4 | 既有群查询 SQL 形状及性能约束 |
| ResourceAssetMapperH2Test | 7 | 公共图片引用、定义软删、保留任务引用与租户范围 |
| MarketingTemplateServiceImplTest | 23 | 公共模板原行为及纯图片空内容边界 |

H2 测试加载生产 Mapper XML、MyBatis-Plus 租户插件和事务管理器；执行服务的发送端口使用事务内本地 outbox 夹具，不连接协议服务。资格服务规则测试使用跨域 Service 替身，实际资格 SQL 另由 H2 测试覆盖。

方言适配已显式记录：H2 JSON 字符串使用 LONGTEXT / 测试 JSON_CONTAINS 别名；V186 从权威迁移提取六条 ALTER 后执行；V187 UPDATE JOIN 先解析原 SQL，仅此语法转为 H2 等价更新。**没有把 H2 方言适配当作真实 MySQL Flyway 验收。**

新增匹配测试先因缺少实现编译失败，随后实现通过。未生成覆盖率报告，不宣称全仓覆盖率或核心逻辑覆盖率达到某个百分比。

额外复核公共图片改动的关联契约：单独执行 `HyperlinkAssetTaskIntegrationContractTest,MarketingTemplateFileLockContractTest`，通过；上述 72 项统计未包含这两个类。

## 前端和静态检查

- `pnpm run typecheck`：通过。
- `pnpm run build`：通过，19.76 秒。现有依赖数据版本提示未阻断构建。
- `node --import tsx --test src/views/task/script-marketing/form.test.ts`：5/5 通过。
- 本次所有 TS/Vue 文件 ESLint（max-warnings 0）、Prettier、Vue 文件 Stylelint：通过；仅作用于本次文件。
- `CHOKIDAR_USEPOLLING=1 pnpm exec playwright test --config=playwright.script-marketing.config.ts`：5/5 通过，29.2 秒。所有业务 API 被本地夹具拦截，未使用真实环境。
- 页面用例覆盖：共享素材→剧本定义→任务副本；缺口完整报告、重查不自动启动及显式启动；角色顺序和推手留空绑定；快速切换详情/记录后迟到响应不串任务。
- 最后为剧本选择增加迟到响应保护，随后重跑 typecheck、该文件 ESLint，以及素材→剧本→任务页面用例（1/1，通过），避免较早选择覆盖后一次选择。
- 创建页截图已经人工查看，角色、分组、逐项间隔和保存入口可见。截图使用本地样本数据。
- 后端本次 7 个 Mapper XML 通过 `xmllint --noout --nonet`。
- `python3 .harness/wiki/test_api_docs.py`：1/1 通过。

## 未执行

未 commit/push；未运行共享库迁移、部署、真实协议发送/回执或进群任务。发布验收需先确认目标环境。数据库 wiki 依赖迁移后的真实 schema 导出，未手工伪造生产现状。
