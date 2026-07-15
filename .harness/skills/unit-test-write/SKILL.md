---
name: unit-test-write
description: Use when writing or changing Armada Java tests, especially for Mapper SQL, Flyway migrations, tenant isolation, or database-backed behavior.
---

# 单元测试编写技能

为 Armada 写测试，按行为选择纯单测或真库 DbTest。

## 节奏
先写**失败**测试 → 最小实现转绿 → 重构。一次一个行为。

## 测试类型

- Service 纯业务逻辑可以在 `src/test` 用 Mockito 隔离外部依赖，但必须断言业务结果和关键交互。
- Mapper、XML SQL、Flyway、租户拦截器、事务与真实分页必须使用真库 DbTest，禁止用 H2、内存实现或恒定返回值替代。
- 回归缺陷先写能复现问题的失败测试，再实现修复。

## 真库 DbTest

`armada-api/dbtest.sh` 从 gitignored 的 `armada-api/.env` 注入数据库变量，不得回显凭据：

```bash
cd armada-api
./dbtest.sh 'AccountListMapperDbTest#methodName'
```

- 运行前确认 `.env` 指向允许使用的环境；共享库或远程库必须先向用户确认。
- 脚本返回成功且输出表明目标测试真实执行后，才能声称 DbTest 通过。
- `.env` 缺失、连接失败或测试未执行时，明确报告阻塞，不得用普通 `mvn test` 冒充真库验证。
- 真库重点覆盖 `FOR UPDATE + LIMIT`、Mapper XML 解析、租户隔离、Flyway、分页 SQL 下推与事务回滚。

## 模型 B 安全网
业务行为变更的验收口径是 **TDD 新测试 + 必要的真库 DbTest + 业务验收**，不能只报告既有测试保持绿色。

## mock 的正当边界
`@Mock` / `@MockBean` / Mockito 仅限 `src/test` 作 test double，不得因此在生产代码引入 Fake/Stub/InMemory 实现。

## 防掩盖
Mapper 行为不要只验证“调用发生”；应断言真实 SQL 结果、租户边界、空集、排序、分页和失败回滚。
