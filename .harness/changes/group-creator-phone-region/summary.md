# 群创建者手机号归属区

## 目标

在群组列表“创建信息”中增加国家标签和“归属区（推断）”。归属区依据创建者已确认 WhatsApp PN 手机号的原始分配号段推断，不表示账号持有人当前所在地。

## 决策与边界

- 只接受协议返回的明确 PN JID 或有效国际手机号；LID、未知 JID、无效号码不推断。
- 国家继续由 libphonenumber 对完整号码校验后解析。
- 归属区采用“国家 ISO2 + 国内号码最长前缀”匹配；没有数据时返回空，不做账号、代理 IP 或登录位置兜底。
- 携号转网、跨区域使用和号码转售会使归属区与当前所在地不同，前端固定标注“推断”。
- 通用国家先使用 libphonenumber 离线号段地理描述；如果只得到国家名则按未知处理。
- 印度移动号码另外使用 telecom circle 号段表补齐州级/区域级结果，后续国家也可按同一表结构增量维护。

## 数据与兼容

- 新增全局主数据表 `country_phone_region_prefix_mapping`，不含 `tenant_id`。
- `group_link_preview` 增加 `creator_phone_region_code`、`creator_phone_region_name` 快照列。
- V128 对已有印度创建者快照按 `+91` 后四位号段进行一次回填。
- 列表只读取快照，不在分页查询期间动态解析或访问外部服务。

## 验收

- 有匹配号段：列表返回并展示国家、归属区（推断）、创建者、建群时间。
- 有有效国家但无号段：国家正常展示，归属区显示 `-`。
- LID/无效号码：国家和归属区均不伪造。
- 同一国家存在不同长度前缀时使用最长匹配。

## 数据来源与许可

印度移动号段来自 [hstsethi/in-mob-prefix](https://github.com/hstsethi/in-mob-prefix)，固定提交 `153ba809d514e74f62a1dc88fb10f0cb1a562e0e`，许可为 CC BY 4.0。导入时去掉运营商字段和归属区为空的记录，并把 telecom circle 代码映射为中文展示名。

## 验证记录

- 后端相关单测和 SQL 契约测试共 32 项通过。
- 前端群列表静态组件契约测试 4 项通过。
- 真实 MySQL 列表投影测试已补断言，但本机没有可用 Docker 环境，Testcontainers 未能启动；需在 CI 或具备 Docker 的开发机补跑。
- 前端 `pnpm typecheck` 未执行完成：当前工作区未安装 `node_modules/typescript`；本次直接 Node 测试已通过。
