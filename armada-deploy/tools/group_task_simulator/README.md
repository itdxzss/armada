# Group Task Stateful Protocol Simulator

这是拉群测试的零外发、有状态协议模拟器。它只存在于 `armada-deploy/tools`，不进入生产 Java 运行时。

## 当前能力

- 启动端点只接受精确值 `sim://local`，HTTP、HTTPS、WebSocket 和其他 scheme 均 fail-closed。
- 保存群成员、管理员、加人权限、入群审批、当前邀请码状态和父子任务投影。
- 按 operationId 记录 dispatch、accept、mutation、result；同一 operation 重放不产生第二次 mutation。
- 按业务键识别不同 operation 对同一联系人、成员、管理员或设置产生的重复物理动作。
- 群收到 `CHAT_SUSPENDED/GROUP_BANNED` 后只发生一次风险迁移，并把当前邀请码标为 `QUARANTINED`。
- 内置 test1 `#199` 的脱敏 `PROPOSED_ORACLE` 回放夹具：8 个业务动作后投递 5 个风险事件。
- 支持本地开放环负载和无节流并发突发；输出吞吐、execution 延迟、调度滞后、最大 in-flight、终态完整率和副作用账本。

## 本地运行

```bash
armada-deploy/tools/group-task-simulator.sh replay \
  --scenario armada-deploy/tools/group_task_simulator/scenarios/pl-s01c-task-199.json
```

退出码 `0` 且输出 `passed=true` 表示模拟世界事实与夹具 expected 一致；不代表 test1、真实 WhatsApp 或 Armada 全链通过。

本地混合协议并发突发：

```bash
armada-deploy/tools/group-task-simulator.sh load \
  --executions 500 \
  --concurrency 50 \
  --command-rate 0 \
  --backend MIXED \
  --replay-every 2
```

`--command-rate 0` 表示无节流，并通过首批并发闸门验证实际 in-flight。设置为正数时按包含重试的总 command/s 开放环投放。
`--replay-every 2` 表示每两个业务动作重放一次原 operation，但使用新的 commandId，用于验证重试不会产生第二次 mutation。

## 本地验证

```bash
PYTHONPATH=armada-deploy/tools python3 -m unittest \
  armada-deploy/tools/tests/test_group_task_simulator.py
bash armada-deploy/tools/group-task-simulator.test.sh
```

## 尚未覆盖

- Armada 真实任务 API、scheduler、DB、Outbox/Kafka 和回调入口集成。
- 邀请码轮换、审批等待、UNKNOWN 三分法、延迟/丢失/乱序和 worker crash DSL。
- 速拉群、建群营销的建群、禁言、退群和营销发送动作。
- Armada 进程、JVM、DB、Kafka 和 worker 的资源指标采集；当前负载结果只是 simulator 微基准。
- Web/Android 真实协议兼容、WhatsApp 公网和平台风控。

在这些能力补齐前，本工具只能签署 simulator core，不得填写业务 E2E 或性能 PASS。
