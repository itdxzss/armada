# AGENTS.md - Armada 后端

本文件适用于整个 `armada/` 仓库。开始工作前先确认当前目录、分支、worktree 和 `git status`，不要覆盖其他会话的在途修改。

## 项目边界

- `armada-api/`：Java 17 + Spring Boot 3.3.5 单 Maven 工程，包根 `com.armada`。
- `armada-deploy/`：测试环境部署与生产离线包脚本。
- `docs/`：业务设计、实施方案和运维文档。
- 前端在同级 `wheel-saas-pure-web/`，协议层在同级 `armada-protocol/`；跨仓修改前进入对应仓库并读取其 `AGENTS.md`。

## Harness 必读路由

- 任何代码修改或代码评审：先读 `.harness/rules/编码规范.md` 和 `.harness/rules/工程结构.md`。
- 涉及表、列、索引、Flyway、Mapper SQL 或租户隔离：再读 `.harness/rules/数据模型规范.md` 和 `.harness/wiki/数据模型.md`。
- 新功能、多步骤或跨会话任务：再读 `.harness/rules/开发流程规范.md`、`.harness/changes/README.md`，并查找同主题 change 记录。
- 业务、接口和数据事实按需读取 `.harness/wiki/` 与 `docs/business/`；历史 change 只作背景，不能覆盖当前代码和用户本次要求。
- `.harness/agents/owner.md` 定义职责边界；任务开始时必须读取。

## 仓库技能

Codex 可从 `.agents/skills/` 自动发现以下 Armada 技能，任务匹配时必须使用：

- `request-analysis`：新功能、需求对账、影响分析。
- `unit-test-write`：Java 单测、H2 内存数据库、Mapper/SQL/Flyway 测试。
- `unit-test-ci`：测试与质量门禁选择、验证结果判读。
- `expert-reviewer`：合并或部署前的后端专家评审。
- `deploy-verify`：部署前检查、目标环境确认和部署后验证。

## 常用验证

```bash
cd armada-api && mvn test
cd armada-api && mvn -Dtest='<TestClass#method>' test
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

只运行与改动相称的验证，但没有真实输出不得声称通过。数据访问测试默认使用 test scope 的 H2
内存数据库和测试专属 MyBatis-Plus 配置，不要求连接真实数据库；真库 DbTest 仅作可选补充验证，执行前仍须确认目标环境。

## 红线

- 凭据、`.env`、私钥绝不提交、绝不外发、绝不在日志或回复中回显。
- 真库、远程、SSH、部署、批量数据修改前必须确认目标环境；生产环境必须再次明确确认。
- 数据相关生产逻辑只走真实 MySQL/MyBatis；禁止生产 mock、假数据、内存兜底和内存分页。
- 数据库结构变更只走 Flyway，禁止手工修改共享库。
- 保持 `Controller -> Service -> Mapper`，不引入 Repository；跨业务域只调用对方 Service。
- 不得以旧系统实现或历史 change 记录替代 Armada 当前代码、设计和测试事实。
