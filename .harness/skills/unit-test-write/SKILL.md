---
name: unit-test-write
description: Use when writing or changing Armada Java tests, especially for Mapper SQL, Flyway migrations, tenant isolation, or database-backed behavior.
---

# 单元测试编写技能

为 Armada 写测试，按行为选择纯单测或 H2 内存数据库测试。

## 节奏
先写**失败**测试 → 最小实现转绿 → 重构。一次一个行为。

## 测试类型

- Service 纯业务逻辑可以在 `src/test` 用 Mockito 隔离外部依赖，但必须断言业务结果和关键交互。
- Mapper、XML SQL、租户拦截器、事务与分页默认使用 test scope 的 H2 MySQL 模式，加载真实 Mapper XML、MyBatis-Plus 插件和 Spring 事务管理器；禁止 mock Mapper、内存业务实现或恒定返回值替代 SQL 执行。
- Flyway 优先在 H2 MySQL 模式执行；H2 无法解析的 MySQL 专有迁移语法，至少补脚本结构、关键列/索引和 SQL 形状测试。
- 回归缺陷先写能复现问题的失败测试，再实现修复。

## H2 内存数据库测试

H2 依赖必须为 Maven `test` scope，测试使用独立配置，不能污染生产配置：

```bash
cd armada-api
mvn -Dtest='MysqlModeMapperInMemoryTest#methodName' test
```

- 测试配置应提供 H2 `DataSource`、`MybatisSqlSessionFactoryBean`、真实 Mapper XML、生产 MyBatis-Plus 插件、`SqlSessionTemplate` 和 `DataSourceTransactionManager`。
- 每个用例独立初始化 schema/fixture，断言最终数据库结果、租户边界、空集、排序、分页和失败回滚。
- 行锁/并发测试使用至少两个独立 Spring 事务和线程，先证明等待，再提交持锁事务并断言最终状态；不得只检查 SQL 文本含 `FOR UPDATE`。
- H2 不等同 MySQL InnoDB；间隙锁、死锁检测和 H2 不支持的方言应明确记录剩余风险，并用 SQL 结构/解析测试覆盖可静态确认的部分。
- 测试类使用普通 `*Test` 命名，确保 `mvn test` 默认执行；不依赖 `.env` 或外部数据库。

## 可选真库补充验证

真库 DbTest 不再是单元测试或本地完成门禁。只有用户明确要求、H2 无法覆盖且风险值得验证时才执行；运行前必须确认目标环境，不得回显 `.env` 或凭据，也不得把未执行的真库验证声称为通过。

## 模型 B 安全网
业务行为变更的验收口径是 **TDD 新测试 + H2 内存数据库测试 + 业务验收**，不能只报告既有测试保持绿色。

## mock 的正当边界
`@Mock` / `@MockBean` / Mockito 仅限 `src/test` 作 test double，不得因此在生产代码引入 Fake/Stub/InMemory 实现。

## 防掩盖
Mapper 行为不要只验证“调用发生”；应断言真实 SQL 结果、租户边界、空集、排序、分页和失败回滚。
