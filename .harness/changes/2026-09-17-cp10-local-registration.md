# 变更记录：云手机多选自动注册（由 CP-10 首台验证扩展）

- 日期 2026-09-17；分支 1.0.3-snapshot，开始时 HEAD 177b97bb。
- 用户要求：控端可选多台云手机；各机自动填 Armada 号码和短信；联调目标 test1，本轮不买号。
- 本地实现完成；未 commit/push、未部署；真实设备绑定和服务端重启等待明确范围授权。

## 设计与影响

复用既有逐机 prepare/status/start/result 和许可/任务存储，不新增采购接口或队列表。前端多选保存独立许可，逐台显示结果；提交不明只重试原请求；已有其他许可不覆盖；离开页面停止后续提交。本机 fleet 限并发启动逐机 worker，缺许可不占槽，单机失败隔离。

后端注册秘密配置可选追加 cloudPhoneId、displayName（同时存在），保留旧三字段配置。全局拒绝重复物理手机绑定。CloudRegistrationDeviceService 由启动层已校验配置装配，按请求 TenantContext 读取，Controller 不访问启动层实现或秘密。GET /api/account-registrations/cloud-phones 受 tenant:account:edit 保护，仅输出公开设备字段；普通设备和免令牌测试身份不进目录。

数据库、Mapper、Flyway、Redis 无修改；配置目录不是业务数据库的内存替代，不存许可或任务。现有事务/幂等采购仍走原 Mapper。前端和 helper 改动见对应仓库/目录。

## 验证与评审

- TDD：新增目录测试首次编译缺少 cloudDevices；实现后目录/认证测试通过。前端多选、部分失败、页面切换、外部许可冲突测试先失败后修复通过。fleet 缺模块测试先失败，实现后通过。
- Java 17：CloudRegistrationDevicesTest 4、DeviceRegistrationServiceTest 18、DeviceRegistrationPermitServiceTest 5、DeviceRegistrationTokensTest 13、AccountRegistrationMapperH2Test 19，共59。Mockito 在沙箱内无法 attach，允许本地 JVM 附加后原55测试通过；新目录纯测试在沙箱内通过，无真库连接。
- Python69、前端12；前端 typecheck/ESLint/Stylelint/build 通过。
- MoreLogin 只读目录返回15台；未启动或绑定新手机，未购号。CP10此前已安装官方包；当前就绪和真实短信流程未验收。
- 自审：租户/秘密字段隔离、旧认证兼容、同物理机重复拒绝、逐机锁和请求绑定、分批失败保留原请求、页面切换停止后续提交均有对应检查/测试。
- 残余：美国渠道/固定已验证官方 APK；额外页面人工处理，无执行器心跳；跨Mac操作不受本地锁保护；无真实付费验收。

## 配置、部署和回滚

此前 CP10-only 令牌传输、修改test1 .env和原镜像重启，被自动审批明确拒绝（缺少新增权限/重启范围授权），没有执行。用户随后要求多选，本记录取代单机方案，旧远程脚本不应执行。

发布需要多选后端新版本和前端对应改动，不能只给旧后端添加 metadata；旧解析器会拒绝。确认首批手机名单后批量产生独立身份，并将条目合并到已有配置，禁止整体覆盖或删除原身份。前后端大量既有脏文件不属于本次，不可一并发布。

本机停止调度不等于取消号码订单。回滚应用代码与该次配置备份必须配套，旧代码不接受带metadata的新配置；供应商订单单独核对。完整运行说明见 ../whatsapp-registration-helper/cloud-registration.md。

## 2026-09-17 发布准备

用户已明确授权部署 test1、绑定 CP-10/CP-8、不购号联调。使用 release/cloud-registration-test1-20260917 独立发布 worktree，仅移入本次变更及必需的设备 API/请求编号导出，不包含主工作区其他在途 UI 或凭据解析改动。后端基线177b97bb、前端基线58ef6db2。

相对 test1 当前运行包，存在基线已提交但尚未发布的 V200：注册明细新增 failure_kind/failure_detail 两个可空列，保留现有行；由 Flyway 执行，禁止手动 ALTER。迁移幂等守卫与 DDL/H2 行为已有测试。备份目录 /home/app/armada-cloud-registration-backups/20260917-multiselect；回滚代码与配置应配套，不删除失败事实字段。

部署脚本测试通过；生产离线包测试因既有缺失 prod/protocol/.env.example 失败，不走本轮 test1 路径。发布目录后端59项测试通过，前端云手机及已有注册回归待最终记录。
