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

## 2026-09-17 test1 发布结果

用户授权后完成发布，CP-10、CP-8 已按 tenant 1 绑定独立身份。独立发布分支 release/cloud-registration-test1-20260917：后端 384a2644，前端 5a522b8a，两个发布工作树保持 clean；本地已提交，未推送，主工作区其他在途改动未发布。

发布目录后端59项、前端19项测试通过，前端类型/lint/build通过。部署脚本测试通过；生产离线包测试因既有缺失 prod/protocol/.env.example 失败，不属于 test1 路径。

首次部署前端静态资源受 umask 077 影响返回403；已用同一提交、umask 022 单独重发前端，脚本 exit 0。后端不重复发布。运行中 JAR/index SHA256 与本地构建一致，两个容器 running、restartCount=0。V200 已由 Flyway 成功执行，仅增加注册失败类别/详情两列；无手动 ALTER。

两台 HTTPS status 均返回设备过滤器的 HTTP404/code404（身份通过且无许可）；CP10令牌搭配CP8设备ID被401拒绝。原 token 身份及原免令牌测试身份全部保留，运行环境仅 ARMADA_DEVICE_REGISTRATION_CLIENTS_JSON 改变。

未创建注册许可、未启动执行器、未购号。新开的浏览器停在登录页，未完成登录后多选界面验收，也未验证真实收码/注册成功。入口 http://armada.65.2.123.53.nip.io/ 。两台本机配置在 helper/.cloud-registration/，批量清单 test1-fleet.json；秘密不在本记录内。

回滚备份 /home/app/armada-cloud-registration-backups/20260917-multiselect，包含旧运行JAR、前端dist、配置和旧镜像映射。回滚前后端/身份配置需配套；无需删除新增可空列，不能把停止执行器当作取消接码订单。脱敏证据见 armada/docs/operations/evidence/cloud-registration-test1-20260917/result.json。
