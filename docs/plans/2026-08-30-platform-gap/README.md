# Armada 平台缺口与执行路线包（2026-08-30）

本目录固化了四仓项目的 Kafka/Runner/交付链与非超链业务缺口分析。路线文档已通过独立静态复核，最终 verdict 为 `ACCEPT`。

## 文档地图

| 文档 | 用途 | 当前结论 |
|---|---|---|
| [S1-kafka-topic-governance-design.md](./S1-kafka-topic-governance-design.md) | Kafka/Redis Stream/outbox/Runner 治理设计 | 阶段一不合并 topic；replay 默认拒绝；先建 canonical manifest 和全量 inventory |
| [S2-non-hyperlink-gap-matrix.md](./S2-non-hyperlink-gap-matrix.md) | 非超链能力对账 | 38 个能力簇、7 个代码/发布 P0、4 个 P1 候选 |
| [D1-delivery-state-audit.md](./D1-delivery-state-audit.md) | 需求到交付闭环审计 | 当前缺少与 candidate 机械绑定的 `DELIVERABLE/ACCEPTED` 证据链 |
| [S3-execution-roadmap.md](./S3-execution-roadmap.md) | 今天/本周/下周可执行路线 | 62 个最多 4h 切片，本周最多两条实现流，13 个验证入口 |
| [V1-independent-verification.md](./V1-independent-verification.md) | 独立复核 | `ACCEPT`；依赖环 0、悬空依赖 0、超 4h 切片 0、未定义 validator 0 |

## 当前可靠结论

- **Observed**：当前只完成了静态证据盘点、设计和路线复核；没有完成本地实现、本地运行验证、test1 验证或真实协议验证。
- **Observed**：四仓当前均有在途改动，不能直接宣称为干净 candidate；`R0-03` 是进入实现前的硬门。
- **Inferred**：首批应先关闭七个 P0 与消息/交付横切基础，任一 P0 未达到本地出口，P1 顺延。
- **Unknown**：test1 的实际版本、Kafka topic/partition/retention/lag、Runner 安装与运行、环境健康和真实 WhatsApp 行为均未在本次任务中验证。
- **Observed**：全部超链赶超工作明确排除；共享基础变更只允许运行既有超链回归。

## 建议启动顺序

1. 执行 S3 的 `R0-01`～`R0-08` 及 `R0-B1/R0-B2`：冻结输入、owner、四仓 candidate 边界、acceptance contract 和两个 bootstrap linter。
2. 只开 Flow A 和 Flow B 两条实现流，先闭合 7 个 P0 及 Kafka/Runner/交付证据链。
3. 本地 `P0_LOCAL_CLOSED` 后，由 owner 从 P1-A～D 中最多选两条；默认建议是 P1-D + P1-A，但仍需 owner 签认。
4. 仅在 candidate、manifest、health 和 Runner 基础闭合后申请 test1 L2/L3 授权；真实账号/代理/联系人/消息/群操作另行申请 L4。

## 冻结哈希

```text
S1  8965e8cb53c6922768c89ccc091e680847c573355ce4462206424cfcdc0220c1
S2  ff8300f14bb422e7fcebe74a389b0e722b33b440923fa40c8bdf0e4ba7792cf8
D1  5f1ea2428132b62e734ce6d035e62c978147e61188a7307ab0ea8e97d943dcf8
S3  ab099c8d05585c0fc60d6c1082cb804e9a3792563536a212e3f2172775f93ae6
V1  8b152d9dffd7cccc5bafa792e70d126293b838fa88cc5c6cce78d149190326c2
```
