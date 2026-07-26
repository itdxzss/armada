---
name: unit-test-ci
description: Use when selecting or running Armada test suites and quality gates, interpreting Maven or deployment-script verification, or deciding whether evidence is sufficient to merge.
---

# CI 与质量门禁技能

按改动范围选择 Armada 当前真实存在的验证，不虚构自动门禁。

## 当前命令

```bash
cd armada-api && mvn test
cd armada-api && mvn -Dtest='<TestClass#method>' test
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
python3 .harness/wiki/test_api_docs.py
```

- Java 生产代码或普通单测改动：运行聚焦测试，再按风险运行 `mvn test`。
- Mapper XML、Flyway、租户隔离、分页或事务：必须补跑相应 H2 内存数据库测试；加载真实 Mapper XML、MyBatis-Plus 插件和 Spring 事务管理器。
- H2 不支持的 MySQL 专有语法：补跑 XML 校验及 SQL 结构/解析测试，并明确 H2 与 InnoDB 的剩余差异；真库 DbTest 只作用户授权后的可选补充。
- `armada-deploy/` 改动：运行 `bash -n` 语法检查和对应脚本测试。
- Controller/API 文档生成链路改动：运行 API 文档测试。

## 尚未机械化

当前仓库没有可确认的 ArchUnit/Checkstyle 自动门禁。以下内容必须人工对照 `.harness/rules/` 评审：

- `shared <- platform <- 业务域 <- boot` 依赖方向与跨域 Service 边界。
- `Controller -> Service -> Mapper`，禁止 Repository 和 Controller 直连 Mapper。
- 内存分页、生产 mock、`FOR UPDATE + LIMIT` 租户拦截器、裸 XML 比较符与 `account_type` 冻结。
- 数据模型聚合归属、重复事实、死列、宽表、Flyway 版本和回滚。

## 结果口径

- 记录执行命令、退出码、测试数和失败/跳过情况。
- 未执行、环境缺失、网络失败和真实测试失败必须分别说明。
- 只有真实输出支持时才能声称通过；数据库测试必须确认用例确实由 H2 执行，不能用 mock Mapper 或只验证调用发生来冒充 SQL 验证。
