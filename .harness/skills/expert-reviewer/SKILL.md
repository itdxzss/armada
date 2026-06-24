# 专家评审技能

合并 / 部署前对改动做专家评审。

## 评审维度
1. **正确性**：边界、空值、并发、事务边界、幂等（尤其 Kafka 消费者必须幂等）。
2. **代码红线合规**：真库唯一路径、无 mock 假数据、无内存分页、`FOR UPDATE`+`LIMIT` 加 `@InterceptorIgnore(tenantLine)`、租户隔离、`account_type` 未被改写；无魔法值、无重复(DRY)、无空 catch、不返 null、方法≤100/类≤800/参数≤5/圈复杂度≤10/嵌套≤3。
3. **armada 结构红线**：包根 `com.armada`；依赖方向 `shared←platform←业务域←boot` 不反转；跨业务域只调对方 `Service`，不碰其 controller/mapper/entity；`Controller→Service→Mapper`(无 Repository，controller 不直连 mapper)；类落在所属业务域。
4. **传输对象口径**：entity=普通类(`model/entity`,无 Lombok)；`Query`=可变 class extends PageQuery；`DTO`/`VO`=record；转换走 MapStruct `Converter`，禁手写大段 set / 禁 BeanUtils。
5. **数据模型红线**：加列/加表前看全局并说清聚合归属与必要性；禁分歧(三镜像类)、禁死列；宽表(>~30 列或混关注点)按聚合拆；schema 变更走 Flyway,新列带 COMMENT。
6. **mapper XML**：无裸 `<>`，已过 xmllint / 真库 DbTest。
7. **简化 / 复用**：有没有重复造轮子、能不能更小更直接。
8. **证据**：是否贴了真实验证输出（evidence-before-done），而非"应该没问题"。

## 输出
按严重度（阻断 / 建议）列问题；阻断项不过不合并。
