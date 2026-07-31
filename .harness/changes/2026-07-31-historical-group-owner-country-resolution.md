# 变更记录：历史群群主国家严格识别

- 日期 / 分支 / worktree: 2026-07-31 / `1.0.2-snapshot` / 当前 Armada checkout
- 需求来源: 用户确认历史群国家表示群主/创建者国家；无法确认真实 PN 的 LID、内部身份和无效号码显示 `--`；设计见 `docs/superpowers/specs/2026-07-31-historical-group-owner-country-resolution-design.md`
- 状态: 已完成

## 目标（一句话）

只有已确认的 WhatsApp PN 才解析历史群群主国家，LID、UNKNOWN 和无效号码不再按区号前缀误判。

## 缺口拆解 / 任务清单

- [x] 使用 libphonenumber 严格解析群主号码国家，保留 IP 分配最长前缀算法。
- [x] 在协议防腐层归一化 PN、LID、UNKNOWN 并传播明确的 ownerPhone。
- [x] 群预览持久化实现 PN 写入、LID 清空、UNKNOWN 保留。
- [x] 两份 Mapper XML 使用 H2 MySQL mode 真跑三态 SQL。
- [x] 历史群接口对无效 owner 返回空国家字段，前端继续显示 `--`。
- [x] Android creator LID 与同响应 participant JID/phone_number 精确匹配时写入真实群主 PN。
- [x] 完成聚焦测试、安全全量测试、XML、构建和差异检查。

## 关键设计决策

- “国家”保持群主/创建者国家语义，不回退关联账号国家；同响应明确提供的 LID/PN 身份对属于
  可确认群主身份，不是号码前缀猜测。
- 身份只认显式 addressing mode 或 JID 后缀；冲突时按 UNKNOWN，不猜测。
- 不新增数据库列或 Flyway；用非持久化观察标记控制 owner_phone 三态更新。
- 不修改前端、armada-protocol、Android Zhuan HTTP 接口或 IP 代理国家算法。
- Mapper 参数遵守不超过 5 个的编码规范，账号群预览 upsert 改为传 `GroupLinkPreview` 对象。
- 2026-07-31 经用户指定第一套环境后完成只读核对：3 个在线群的 creator LID 均能精确匹配
  participant JID，且匹配项均带 phone_number；未写远程数据库。

## 验证（evidence-before-done）

- 合并远端最新代码后的最终聚焦回归：11 个测试类、98 个测试，0 failure / 0 error。
- LID/PN 增量严格按 RED/GREEN 验证：新增单测在实现前因实际结果仍为 LID 而失败，
  实现后单测 1/1 通过；随后扩展相关链路回归 8 个测试类、52 个测试全部通过。
- H2 MySQL mode：`MysqlModeMapperInMemoryTest` 15 个测试通过；真实加载
  `AccountGroupMembershipMapper.xml` 与 `GroupLinkPreviewMapper.xml`，覆盖 update 和 duplicate-key 三态。
- 合并远端后的本地安全全量：1437 个测试，1434 通过；3 个失败均位于本次未修改的
  `HistoricalGroupPullWorkerImplTest`（2 个旧接口参数断言）和
  `GroupCreationMarketingTaskMapperSqlShapeTest`（1 个旧 SQL 参数路径断言）。
- 构建：`mvn -q -DskipTests package` 通过。
- XML：两份变更 Mapper 经 `xmllint --noout` 校验通过。
- 静态检查：旧群国家前缀解析方法和两处任意截取 owner JID 的 helper 已移除；
  `git diff --check` 通过。
- 本次增量完成后重新执行 `mvn test`，但默认套件包含依赖本机 MySQL 的 `*DbTest`；
  当前环境以 `root` 无密码连接被拒绝并逐类长时间重试，因此在确认相同外部环境错误后中断，
  Maven 退出码 130。运行中也复现了与本次未修改代码有关的
  `HistoricalGroupPullWorkerImplTest` 两个旧接口参数断言失败；不能把这次全量运行描述为通过。

## 部署

- 不在本次范围；未部署、未修改远程数据。

## 遗留 / 跟进

- 第一套环境部署与刷新写入验收仍需用户另行授权；当前只读证据确认 3 个在线群可解析真实 PN，
  第四个历史群未出现在在线账号实时群响应中。
