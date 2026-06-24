# 单元测试编写技能

为 armada 写测试，TDD 节奏。

## 节奏
先写**失败**测试 → 最小实现转绿 → 重构。一次一个行为。

## 数据测试一律真库 DbTest（铁律）
- 数据相关逻辑用真库 DbTest，**禁内存 / mock 假数据**（见 `rules/编码规范.md`、`rules/数据模型规范.md`）。
- DbTest 跑法：`ARMADA_DB_IT=true` + `DB_HOST/PORT/USER/PASSWORD`，JDK 17。**单工程,无需 `-am`**。
- **DbTest 必须真跑**：缺 DB env 时 `@EnabledIf` 会静默跳过 → "本地绿" = 真库断言根本没跑(wheel 审计教训)。CI 须断言 skipped 计数为 0,或 env 缺失直接 fail。
- 只有真库才抓得到的坑：`FOR UPDATE+LIMIT` 租户拦截器语法错、mapper XML 裸 `<>`、租户隔离、分页 SQL 下推。

## 模型 B 安全网
按业务重建时,验收口径 = **TDD 新测试钉新行为 + 真库 DbTest + 业务端到端验收**,**不是**"老测试保持绿"(老测试钉的是旧行为,该改就改)。

## mock 的正当边界
`@Mock` / `@MockBean` / Mockito 仅限 `src/test` 作 test double，**不是**在生产代码造假数据的依据。

## 防掩盖
警惕「数字 id + `List<String>` + FakeMapper 恒返 1」这类三重掩盖——加 mapper 方法时给所有 Fake 补 stub，必要时做变异验证。
