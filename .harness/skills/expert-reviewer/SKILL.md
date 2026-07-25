---
name: expert-reviewer
description: Use when reviewing Armada backend changes before merge or deployment, especially changes involving SQL, tenant isolation, state transitions, Kafka, or cross-domain dependencies.
---

# 专家评审技能

合并 / 部署前对改动做专家评审。

## 评审输入

1. 先读 `AGENTS.md` 与 `.harness/rules/{编码规范,工程结构}.md`。
2. 涉及数据库时再读 `.harness/rules/数据模型规范.md` 与 `.harness/wiki/数据模型.md`。
3. 检查用户本次要求、设计文档、完整 diff、相关调用方和测试证据；不要只看变更摘要。

## 评审维度
1. **正确性**：边界、空值、并发、事务边界、幂等（尤其 Kafka 消费者必须幂等）。
2. **代码红线合规**：生产数据访问仍只走真实 MySQL/MyBatis、无生产 mock 假数据、无内存分页、`FOR UPDATE`+`LIMIT` 加 `@InterceptorIgnore(tenantLine)`、租户隔离、`account_type` 未被改写；无魔法值、无重复(DRY)、无空 catch、不返 null、方法≤100/类≤800/参数≤5/圈复杂度≤10/嵌套≤3。
3. **armada 结构红线**：包根 `com.armada`；依赖方向 `shared←platform←业务域←boot` 不反转；跨业务域只调对方 `Service`，不碰其 controller/mapper/entity；`Controller→Service→Mapper`(无 Repository，controller 不直连 mapper)；类落在所属业务域。
4. **传输对象口径**：entity=普通类(`model/entity`,无 Lombok)；`Query`=可变 class extends PageQuery；`DTO`/`VO`=record；转换走 MapStruct `Converter`，禁手写大段 set / 禁 BeanUtils。
5. **数据模型红线**：加列/加表前看全局并说清聚合归属与必要性；禁分歧(三镜像类)、禁死列；宽表(>~30 列或混关注点)按聚合拆；schema 变更走 Flyway,新列带 COMMENT。
6. **mapper XML**：无裸 `<>`，已过 xmllint、H2 MySQL 模式真实 Mapper 执行；H2 不支持的 MySQL 专有语法已有 SQL 结构/解析测试。
7. **简化 / 复用**：有没有重复造轮子、能不能更小更直接。
8. **证据**：是否有与风险相称的真实命令输出；Mapper/SQL/Flyway 不能只靠 mock 或调用验证，必须有 H2 实际执行或针对专有方言的结构/解析证据。真库 DbTest 仅作可选补充，不再是合并门禁。

## 输出

- 先列发现，按“阻断 / 重要 / 建议”排序；每条给出文件与行号、触发场景、影响和最小修复方向。
- 没有发现时明确说明剩余风险和未执行验证，不用总结冒充评审结果。
- 阻断项未解决不得建议合并或部署；不要擅自修改代码，除非用户同时要求修复。
