# 有限取号重试：5 秒 / 50 次

用户明确授权调整：一次号码采购允许总共 50 次取号尝试（含首次），NO_NUMBERS 响应后至少等待 5 秒。后台既有调度和负载可能增加实际间隔。取得号码后不补购；未知结果、余额/鉴权等其他错误不重试。旧终态任务不自动复活。

## 设计与影响
- 复用 AccountRegistrationWorker，覆盖普通任务和 IOS_DEVICE 许可；PENDING + next_purchase_at 表示等待重试，不新增手机状态。
- account_registration_item 聚合新增 purchase_attempts、next_purchase_at（V199）：次数和调度期限是现有列无法表达的持久化事实；不复用 cancel_after 或错误码存计数。数据库中断后 PURCHASING 仍转 UNKNOWN，不重发。
- 每次外部请求前保存计数/意图；仅 acquireNumber 明确 NO_NUMBERS 恢复 PENDING。查询报价失败不纳入采购重试。
- 固定商家及价格保持；手机截止时间、禁用与取消门禁每次检查。取消发生在 HTTP 期间且返回无号时，允许调度取消该待重试项。
- API 添加 purchaseAttempts、nextPurchaseAt；控端两类页面显示计数/50及等待说明。现有 IPA 可继续轮询，无须打包。
- Redis 无变更。保留其他会话改动，不 commit/push。

## 验证
- 新测试先因缺失字段编译失败，随后实现。
- 本地第一次广泛测试受 sandbox 本地端口限制，使用 JDK17 获准重跑；最终结果见交付记录。
- 含次数50边界、5秒门禁、成功停止、取消、许可过期、非库存错误、结果未知/崩溃不重复购买，以及真实 H2 SQL/租户隔离/重试调度。

## 回滚
恢复部署前制品；新增两列可保留，旧代码忽略。回滚前停止新任务，等待正在执行的采购落定，防止旧调度忽略 next_purchase_at 提前尝试。不要删除已购或未知订单。结构清理仅在独立授权迁移时执行。

## 部署前评审与本地结果
- 自审：租户隔离、先意图后 HTTP、50 次硬上限、固定价格/商家、NO_NUMBERS 唯一可重试条件、取消竞争、许可过期和未知结果不重购均有对应覆盖，未发现阻断项。
- JDK17 注册/接码聚焦测试 195 通过；补充取消竞争 H2 用例后共 196（H2 15）。
- 前端 Node 测试 19 通过，vue-tsc 与变更文件 ESLint 通过。初次误用 Vitest（未安装）及未加载项目 test-double loader，已改用现有 Node/tsx + loader 成功验证。
- XML 校验、git diff --check、部署脚本回归通过。MySQL PREPARE 等待实际 Flyway 验证。

## 第一套部署结果
- `bash armada-deploy/deploy-test.sh --env test1 --all -y` 成功；后端及前端健康检查成功。源码基于已确认的当前工作区，未 commit/push。
- 运行 JAR SHA256 `0812009b755728379ff6ca37d5f762330faf93a11fc99343c092bd46e9efb17d`，容器 running / restartCount 0；Flyway V199 success=1。
- 手机 status/options HTTP200、业务码0、no-store；status 返回 purchaseAttempts，错设备401。旧许可仍 FAILED，没有自动复活。
- 真实结构元数据经正式 gen_datamodel.py 更新文档。新计数对旧任务默认0（迁移前未记录，不代表旧任务从未请求）。
- 本次未新增真实采购；等待用户选择测试沿用202/$1.22还是网页$0.88价档。
